package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.MinecraftSoundCapture.CapturedSound;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.ListenerFrame;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.LoopFrame;
import mchorse.bbs_mod.audio.ogg.VorbisReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Offline stereo mixer for captured Minecraft sounds and the film track. */
public final class MinecraftSoundMixer
{
    private static final float CENTER_GAIN = (float) Math.sqrt(0.5D);
    private static final long MAX_SAMPLES = 500_000_000L;

    private MinecraftSoundMixer() {}

    public static boolean mixToFile(File file, List<CapturedSound> sounds, List<ListenerFrame> frames,
                                    Wave filmAudio, int sampleRate, double frameRate, int totalFrames)
    {
        if (file == null || totalFrames <= 0 || sampleRate <= 0 || frameRate <= 0D) return false;
        long totalSamples = (long) Math.ceil(totalFrames / frameRate * sampleRate);
        if (totalSamples <= 0 || totalSamples > MAX_SAMPLES) return false;
        if (filmAudio != null && filmAudio.bitsPerSample != 16) filmAudio = filmAudio.convertTo16();
        Map<ResourceLocation, Wave> cache = new HashMap<>();
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(file)))
        {
            WaveWriter.writeHeader(stream, 2, sampleRate, 16, (int) (totalSamples * 4L));
            byte[] packed = new byte[8192];
            for (long sample = 0; sample < totalSamples; sample += packed.length / 4)
            {
                int count = (int) Math.min(packed.length / 4L, totalSamples - sample);
                for (int i = 0; i < count; i++)
                {
                    long absolute = sample + i;
                    float left = 0F, right = 0F;
                    if (filmAudio != null) {
                        float[] film = filmSample(filmAudio, absolute, sampleRate);
                        left += film[0]; right += film[1];
                    }
                    for (CapturedSound sound : sounds)
                    {
                        int start = (int) Math.floor(sound.frame / frameRate * sampleRate);
                        int end = sound.endFrame < 0 ? (int) totalSamples : (int) Math.floor(sound.endFrame / frameRate * sampleRate);
                        if (absolute < start || absolute >= end) continue;
                        Wave wave = read(cache, sound.location);
                        if (wave == null || wave.data.length == 0) continue;
                        LoopFrame state = sound.getTrackFrame((int) Math.floor((absolute / (double) sampleRate) * frameRate));
                        float gain = state == null ? sound.volume : state.volume;
                        float pitch = state == null ? sound.pitch : state.pitch;
                        int channels = Math.max(1, wave.numChannels);
                        long sourceFrames = wave.data.length / (long) (channels * 2);
                        if (sourceFrames <= 0) continue;
                        long sourceFrame = (long) ((absolute - start) * pitch * wave.sampleRate / sampleRate);
                        if (sound.loop)
                        {
                            sourceFrame %= sourceFrames;
                            if (sourceFrame < 0) sourceFrame += sourceFrames;
                        }
                        if (sourceFrame < 0 || sourceFrame >= sourceFrames) continue;
                        int sourceIndex = (int) (sourceFrame * channels * 2L);
                        if (sourceIndex < 0 || sourceIndex + channels * 2 > wave.data.length) continue;
                        float[] sampleValue = sourceSample(wave, sourceIndex);
                        ListenerFrame listener = frames.isEmpty() ? null : frames.get(MathUtils.clamp((int) Math.floor(absolute * frameRate / sampleRate), 0, frames.size() - 1));
                        float[] pan = gains(sound, state, listener, gain);
                        left += sampleValue[0] * pan[0]; right += sampleValue[1] * pan[1];
                    }
                    int offset = i * 4;
                    short l = (short) Math.round(MathUtils.clamp(left, -1F, 1F) * Short.MAX_VALUE);
                    short r = (short) Math.round(MathUtils.clamp(right, -1F, 1F) * Short.MAX_VALUE);
                    packed[offset] = (byte) l; packed[offset + 1] = (byte) (l >> 8);
                    packed[offset + 2] = (byte) r; packed[offset + 3] = (byte) (r >> 8);
                }
                stream.write(packed, 0, count * 4);
            }
            return true;
        }
        catch (Exception e)
        {
            file.delete();
            return false;
        }
    }

    private static float[] gains(CapturedSound sound, LoopFrame state, ListenerFrame listener, float volume)
    {
        if (sound.relative || listener == null) return new float[] { volume * CENTER_GAIN, volume * CENTER_GAIN };
        double x = state == null ? sound.x : state.x, y = state == null ? sound.y : state.y, z = state == null ? sound.z : state.z;
        double dx = x - listener.x, dy = y - listener.y, dz = z - listener.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float gain = volume;
        if (sound.attenuate && sound.range > 0F) gain *= MathUtils.clamp(1F - (float) (distance / sound.range), 0F, 1F);
        if (distance < 0.001D) return new float[] { gain * CENTER_GAIN, gain * CENTER_GAIN };
        double pan = MathUtils.clamp((dx * listener.rightX + dy * listener.rightY + dz * listener.rightZ) / distance, -1D, 1D);
        double angle = (pan + 1D) * Math.PI / 4D;
        return new float[] { gain * (float) Math.cos(angle), gain * (float) Math.sin(angle) };
    }

    private static float[] filmSample(Wave wave, long index, int outputRate)
    {
        long frame = index * wave.sampleRate / outputRate;
        int channels = Math.max(1, wave.numChannels), pos = (int) (frame * channels * 2);
        if (pos < 0 || pos + channels * 2 > wave.data.length) return new float[] { 0F, 0F };
        return sourceSample(wave, pos);
    }

    private static float[] sourceSample(Wave wave, int pos)
    {
        int channels = Math.max(1, wave.numChannels);
        short first = (short) ((wave.data[pos] & 0xff) | (wave.data[pos + 1] << 8));
        if (channels == 1) { float value = first / (float) Short.MAX_VALUE; return new float[] { value, value }; }
        short second = (short) ((wave.data[pos + 2] & 0xff) | (wave.data[pos + 3] << 8));
        return new float[] { first / (float) Short.MAX_VALUE, second / (float) Short.MAX_VALUE };
    }

    private static Wave read(Map<ResourceLocation, Wave> cache, ResourceLocation location)
    {
        if (cache.containsKey(location)) return cache.get(location);
        Wave wave = null;
        try
        {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isPresent())
            {
                try (InputStream stream = resource.get().open())
                { wave = VorbisReader.read(new Link(location.getNamespace(), location.getPath()), stream); }
            }
        }
        catch (Exception ignored) {}
        cache.put(location, wave);
        return wave;
    }
}
