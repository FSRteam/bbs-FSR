package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.ogg.VorbisReader;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.FFMpegUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class AudioReader
{
    public static boolean isVideo(String pathLower)
    {
        return pathLower.endsWith(".mp4") || pathLower.endsWith(".mov") || pathLower.endsWith(".mkv")
            || pathLower.endsWith(".webm") || pathLower.endsWith(".avi") || pathLower.endsWith(".m4v");
    }

    public static Wave read(AssetProvider provider, Link link) throws IOException
    {
        if (link == null)
        {
            throw new AudioDecodeException("Audio link is null");
        }

        String source = link.toString();

        if (provider == null)
        {
            throw new AudioDecodeException(source, "Asset provider is null");
        }

        if (link.path == null)
        {
            throw new UnsupportedAudioFormatException(source, "Audio path is missing");
        }

        String pathLower = link.path.toLowerCase(Locale.ROOT);

        if (isVideo(pathLower))
        {
            return readVideoAudio(provider, link);
        }

        if (!pathLower.endsWith(".wav") && !pathLower.endsWith(".ogg"))
        {
            throw new UnsupportedAudioFormatException(source, "Unsupported audio extension; expected .wav or .ogg");
        }

        /* System.out.println("Reading: " + link); */

        try (InputStream asset = provider.getAsset(link))
        {
            if (asset == null)
            {
                throw new AudioDecodeException(source, "Asset provider returned no stream");
            }

            try
            {
                Wave wave = pathLower.endsWith(".wav")
                    ? new WaveReader().read(asset, source)
                    : VorbisReader.read(link, asset);

                if (wave == null)
                {
                    throw new MalformedAudioException(source, "Decoder returned no audio");
                }

                return wave;
            }
            catch (IOException e)
            {
                throw e;
            }
            catch (RuntimeException e)
            {
                throw new MalformedAudioException(source, "Unexpected decoder failure", e);
            }
        }
    }

    /**
     * Extract a video file's audio track as 16-bit stereo PCM by piping it through ffmpeg.
     * A missing file, an absent ffmpeg binary, or a video without an audio track all fail
     * the same way an unreadable audio file would - callers already log and cache that.
     */
    private static Wave readVideoAudio(AssetProvider provider, Link link) throws IOException
    {
        String source = link.toString();
        File file = provider.getFile(link);

        if (file == null || !file.isFile())
        {
            throw new AudioDecodeException(source, "Asset provider returned no video file");
        }

        ProcessBuilder builder = new ProcessBuilder(
            FFMpegUtils.getFFMPEG(),
            "-i", file.getAbsolutePath(),
            "-vn", "-sn", "-dn",
            "-ac", "2", "-ar", "44100",
            "-acodec", "pcm_s16le", "-f", "s16le",
            "pipe:1"
        );

        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process;

        try
        {
            process = builder.start();
        }
        catch (IOException e)
        {
            throw new AudioDecodeException(source, "Failed to start ffmpeg for audio extraction", e);
        }

        byte[] data;

        try (InputStream stream = process.getInputStream())
        {
            data = stream.readAllBytes();
        }
        finally
        {
            process.destroy();
        }

        if (data.length == 0)
        {
            throw new MalformedAudioException(source, "Video file has no decodable audio track");
        }

        return new Wave(1, 2, 44100, 16, data);
    }
}
