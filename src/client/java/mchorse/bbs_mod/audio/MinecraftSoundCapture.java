package mchorse.bbs_mod.audio;

import com.mojang.blaze3d.audio.ListenerTransform;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Records scene sound events and listener transforms for an export timeline. */
public final class MinecraftSoundCapture implements SoundEventListener
{
    private final List<CapturedSound> sounds = new ArrayList<>();
    private final List<ListenerFrame> frames = new ArrayList<>();
    private final List<CapturedSound> loops = new ArrayList<>();
    private boolean active;
    private boolean listenerRegistered;
    private volatile Result result = Result.idle();

    public boolean isActive() { return this.active; }
    public List<CapturedSound> getSounds() { return this.sounds; }
    public List<ListenerFrame> getFrames() { return this.frames; }
    /** Immutable terminal snapshot consumed by the export lifecycle. */
    public Result getResult() { return this.result; }

    public void begin()
    {
        this.end();
        this.sounds.clear();
        this.frames.clear();
        this.result = Result.capturing();
        this.active = true;

        try
        {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            this.listenerRegistered = true;
            manager.addListener(this);
        }
        catch (RuntimeException | LinkageError error)
        {
            this.recordFailure(Failure.LISTENER_REGISTRATION, error);

            try
            {
                this.end();
            }
            catch (RuntimeException | LinkageError cleanupFailure)
            {
                appendSuppressed(error, cleanupFailure);
            }

            throw error;
        }
    }

    public void end()
    {
        if (!this.active && !this.listenerRegistered && this.loops.isEmpty()) return;
        this.active = false;

        Throwable failure = null;

        try
        {
            if (this.listenerRegistered)
            {
                Minecraft.getInstance().getSoundManager().removeListener(this);
                this.listenerRegistered = false;
            }
        }
        catch (RuntimeException | LinkageError error)
        {
            failure = error;
            this.recordFailure(Failure.LISTENER_REMOVAL, error);
        }
        finally
        {
            try
            {
                for (CapturedSound loop : this.loops)
                {
                    loop.endFrame = this.frames.size();
                    loop.instance = null;
                }
            }
            catch (RuntimeException | LinkageError error)
            {
                if (failure == null) failure = error;
                else appendSuppressed(failure, error);
                this.recordFailure(Failure.LOOP_CLEANUP, error);
            }
            finally
            {
                this.loops.clear();
                this.finishResult();
            }
        }

        if (failure != null)
        {
            Throwable primary = this.result.failed() && this.result.cause() != null
                ? this.result.cause() : failure;
            if (primary instanceof RuntimeException runtimeException) throw runtimeException;
            if (primary instanceof LinkageError linkageError) throw linkageError;
        }
    }

    /** Call once immediately before each encoded frame. */
    public void captureFrame()
    {
        if (!this.active) return;

        SoundManager manager;

        try
        {
            manager = Minecraft.getInstance().getSoundManager();
            Iterator<CapturedSound> iterator = this.loops.iterator();
            while (iterator.hasNext())
            {
                CapturedSound loop = iterator.next();
                if (!hasEnded(manager, loop))
                {
                    loop.track.add(new LoopFrame(clampVolume(loop.instance.getVolume()),
                        clampPitch(loop.instance.getPitch()), loop.instance.getX(), loop.instance.getY(), loop.instance.getZ()));
                    continue;
                }
                loop.endFrame = this.frames.size();
                loop.instance = null;
                iterator.remove();
            }
        }
        catch (RuntimeException error)
        {
            this.recordFailure(Failure.LOOP_TRACKING, error);
            return;
        }

        try
        {
            ListenerTransform transform = manager.getListenerTransform();
            Vec3 position = transform.position();
            Vec3 right = transform.right();
            this.frames.add(new ListenerFrame(position.x, position.y, position.z, right.x, right.y, right.z));
        }
        catch (RuntimeException error)
        {
            this.recordFailure(Failure.FRAME_CAPTURE, error);
        }
    }

    private static boolean hasEnded(SoundManager manager, CapturedSound loop)
    {
        if (loop.instance instanceof TickableSoundInstance tickable && tickable.isStopped()) return true;
        if (manager.isActive(loop.instance)) { loop.seenActive = true; return false; }
        return loop.seenActive;
    }

    @Override
    public void onPlaySound(SoundInstance instance, WeighedSoundEvents event, float range)
    {
        if (!this.active) return;
        try
        {
            this.capture(instance, range);
        }
        catch (RuntimeException error)
        {
            this.recordFailure(Failure.SOUND_EVENT, error);
        }
    }

    private void recordFailure(Failure failure, Throwable cause)
    {
        this.active = false;

        Result current = this.result;
        if (current.status() == Status.FAILED)
        {
            appendSuppressed(current.cause(), cause);
            this.result = current.withCounts(this.sounds.size(), this.frames.size());
            return;
        }

        this.result = Result.failed(failure, cause, this.sounds.size(), this.frames.size());
    }

    private void finishResult()
    {
        Result current = this.result;
        if (current.status() == Status.CAPTURING)
        {
            this.result = Result.success(this.sounds.size(), this.frames.size());
        }
        else if (current.status() == Status.FAILED)
        {
            this.result = current.withCounts(this.sounds.size(), this.frames.size());
        }
    }

    private static void appendSuppressed(Throwable first, Throwable later)
    {
        if (first == null || later == null || first == later) return;
        for (Throwable suppressed : first.getSuppressed())
        {
            if (suppressed == later) return;
        }
        first.addSuppressed(later);
    }

    public enum Status
    {
        IDLE,
        CAPTURING,
        SUCCESS,
        FAILED
    }

    public enum Failure
    {
        NONE,
        LISTENER_REGISTRATION,
        SOUND_EVENT,
        LOOP_TRACKING,
        FRAME_CAPTURE,
        LISTENER_REMOVAL,
        LOOP_CLEANUP
    }

    public record Result(Status status, Failure failure, Throwable cause, int soundCount, int frameCount)
    {
        private static Result idle()
        {
            return new Result(Status.IDLE, Failure.NONE, null, 0, 0);
        }

        private static Result capturing()
        {
            return new Result(Status.CAPTURING, Failure.NONE, null, 0, 0);
        }

        private static Result success(int soundCount, int frameCount)
        {
            return new Result(Status.SUCCESS, Failure.NONE, null, soundCount, frameCount);
        }

        private static Result failed(Failure failure, Throwable cause, int soundCount, int frameCount)
        {
            return new Result(Status.FAILED, failure, cause, soundCount, frameCount);
        }

        private Result withCounts(int soundCount, int frameCount)
        {
            return new Result(this.status, this.failure, this.cause, soundCount, frameCount);
        }

        public boolean success()
        {
            return this.status == Status.SUCCESS;
        }

        public boolean failed()
        {
            return this.status == Status.FAILED;
        }

        public boolean hasPartialData()
        {
            return this.soundCount > 0 || this.frameCount > 0;
        }
    }

    private void capture(SoundInstance instance, float range)
    {
        SoundSource source = instance.getSource();
        if (source == SoundSource.MUSIC) return;
        if (source == SoundSource.MASTER && instance.isRelative() && instance.getAttenuation() == SoundInstance.Attenuation.NONE) return;
        Sound sound = instance.getSound();
        if (sound == null) return;
        float volume = clampVolume(instance.getVolume());
        float pitch = clampPitch(instance.getPitch());
        boolean loop = instance.isLooping() && instance.getDelay() == 0;
        if (volume <= 0F && !loop) return;
        CapturedSound captured = new CapturedSound(sound.getPath(), this.frames.size(), instance.getX(), instance.getY(), instance.getZ(),
            instance.isRelative(), instance.getAttenuation() == SoundInstance.Attenuation.LINEAR, volume, pitch, range, loop);
        this.sounds.add(captured);
        if (loop) { captured.instance = instance; this.loops.add(captured); }
    }

    private static float clampVolume(float value) { return MathUtils.clamp(value, 0F, 1F); }
    private static float clampPitch(float value) { return MathUtils.clamp(value, 0.5F, 2F); }

    public static final class CapturedSound
    {
        public final ResourceLocation location;
        public final int frame;
        public final double x, y, z;
        public final boolean relative, attenuate, loop;
        public final float volume, pitch, range;
        public final List<LoopFrame> track;
        public int endFrame = -1;
        private SoundInstance instance;
        private boolean seenActive;

        public CapturedSound(ResourceLocation location, int frame, double x, double y, double z, boolean relative,
                             boolean attenuate, float volume, float pitch, float range, boolean loop)
        {
            this.location = location; this.frame = frame; this.x = x; this.y = y; this.z = z;
            this.relative = relative; this.attenuate = attenuate; this.volume = volume; this.pitch = pitch;
            this.range = range; this.loop = loop; this.track = loop ? new ArrayList<>() : null;
        }

        public LoopFrame getTrackFrame(int frameIndex)
        {
            if (this.track == null || this.track.isEmpty()) return null;
            return this.track.get(MathUtils.clamp(frameIndex - this.frame, 0, this.track.size() - 1));
        }
    }

    public static final class LoopFrame
    {
        public final float volume, pitch;
        public final double x, y, z;
        public LoopFrame(float volume, float pitch, double x, double y, double z)
        { this.volume = volume; this.pitch = pitch; this.x = x; this.y = y; this.z = z; }
    }

    public static final class ListenerFrame
    {
        public final double x, y, z, rightX, rightY, rightZ;
        public ListenerFrame(double x, double y, double z, double rightX, double rightY, double rightZ)
        { this.x = x; this.y = y; this.z = z; this.rightX = rightX; this.rightY = rightY; this.rightZ = rightZ; }
    }
}
