package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Vector3f;
import org.lwjgl.openal.AL10;

import java.util.Objects;

/** One OpenAL source. Film voice identity is managed by SoundManager, never by asset Link. */
public class SoundPlayer
{
    /**
     * Distance every source starts with. Sound forms compute their own
     * attenuation and override this per source; anything else keeps the value
     * film audio has always used.
     */
    public static final float DEFAULT_MAX_DISTANCE = 60F;

    private int source = -1;
    private SoundBuffer buffer;
    private final SoundBackend backend;
    private boolean unique;
    private boolean managed;

    /** Legacy constructor retained for source and binary compatibility. */
    public SoundPlayer(SoundBuffer buffer)
    {
        this(buffer, false);
    }

    /** Attach the buffer layout appropriate for listener-relative or spatial playback. */
    public SoundPlayer(SoundBuffer buffer, boolean spatial)
    {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.backend = buffer.getBackend();
        int bufferHandle = buffer.getBuffer(spatial);

        if (bufferHandle <= 0)
        {
            throw new IllegalStateException("Cannot attach a deleted sound buffer");
        }

        int created = this.backend.createSource();

        if (created <= 0)
        {
            throw new IllegalStateException("OpenAL returned an invalid source handle");
        }

        this.source = created;

        try
        {
            this.backend.setSourceBuffer(this.source, bufferHandle);
            this.backend.setSourceMaxDistance(this.source, DEFAULT_MAX_DISTANCE);
            this.setRelative(false);
        }
        catch (RuntimeException | Error failure)
        {
            try
            {
                this.delete();
            }
            catch (RuntimeException | Error cleanup)
            {
                failure.addSuppressed(cleanup);
            }

            throw failure;
        }
    }

    public SoundPlayer unique()
    {
        this.unique = true;

        return this;
    }

    SoundPlayer managed()
    {
        this.managed = true;

        return this;
    }

    public int getSource()
    {
        return this.source;
    }

    public SoundBuffer getBuffer()
    {
        return this.buffer;
    }

    public boolean isUnique()
    {
        return this.unique;
    }

    public boolean isDeleted()
    {
        return this.source < 0;
    }

    public boolean canBeRemoved()
    {
        return !this.unique && !this.managed && this.isStopped();
    }

    /* Properties */

    public void setVolume(float volume)
    {
        if (this.source >= 0)
        {
            this.backend.setSourceVolume(this.source, volume);
        }
    }

    /**
     * Distance beyond which OpenAL stops attenuating further. Sound forms need
     * this wider than the default so their own falloff curve, rather than
     * OpenAL's, decides where the sound dies out.
     */
    public void setMaxDistance(float distance)
    {
        if (this.source >= 0)
        {
            this.backend.setSourceMaxDistance(this.source, distance);
        }
    }

    public void setPitch(float pitch)
    {
        if (this.source >= 0)
        {
            this.backend.setSourcePitch(this.source, pitch);
        }
    }

    public void setRelative(boolean relative)
    {
        if (this.source >= 0)
        {
            this.backend.setSourceRelative(this.source, relative);
        }
    }

    public void setLooping(boolean looping)
    {
        if (this.source >= 0)
        {
            this.backend.setSourceLooping(this.source, looping);
        }
    }

    public void setPosition(Vector3f vector)
    {
        this.setPosition(vector.x, vector.y, vector.z);
    }

    public void setPosition(float x, float y, float z)
    {
        if (this.source >= 0)
        {
            this.backend.setSourcePosition(this.source, x, y, z);
        }
    }

    public void setVelocity(Vector3f vector)
    {
        this.setVelocity(vector.x, vector.y, vector.z);
    }

    public void setVelocity(float x, float y, float z)
    {
        if (this.source >= 0)
        {
            this.backend.setSourceVelocity(this.source, x, y, z);
        }
    }

    /* Playback */

    public void play()
    {
        if (this.source >= 0)
        {
            this.backend.playSource(this.source);
        }
    }

    public void pause()
    {
        if (this.source >= 0)
        {
            this.backend.pauseSource(this.source);
        }
    }

    public void stop()
    {
        if (this.source >= 0)
        {
            this.backend.stopSource(this.source);
        }
    }

    public int getSourceState()
    {
        if (this.source < 0 || this.backend.isSourceStopped(this.source))
        {
            return AL10.AL_STOPPED;
        }

        if (this.backend.isSourcePlaying(this.source))
        {
            return AL10.AL_PLAYING;
        }

        if (this.backend.isSourcePaused(this.source))
        {
            return AL10.AL_PAUSED;
        }

        return AL10.AL_INITIAL;
    }

    public boolean isPlaying()
    {
        return this.source >= 0 && this.backend.isSourcePlaying(this.source);
    }

    public boolean isPaused()
    {
        return this.source >= 0 && this.backend.isSourcePaused(this.source);
    }

    public boolean isStopped()
    {
        return this.source < 0 || this.backend.isSourceStopped(this.source);
    }

    public float getPlaybackPosition()
    {
        return this.source < 0 ? 0F : this.backend.getSourcePosition(this.source);
    }

    public void setPlaybackPosition(float seconds)
    {
        SoundBuffer current = this.buffer;

        if (this.source < 0 || current == null)
        {
            return;
        }

        seconds = MathUtils.clamp(seconds, 0F, current.getDuration());
        this.backend.setSourcePositionSeconds(this.source, seconds);
    }

    /** Idempotent teardown in the required source order: stop, detach, delete. */
    public void delete()
    {
        int handle = this.source;

        if (handle < 0)
        {
            this.buffer = null;
            return;
        }

        Throwable failure = null;

        boolean detached = this.buffer == null;
        boolean deleted = false;

        try
        {
            this.backend.stopSource(handle);
        }
        catch (RuntimeException | Error e)
        {
            failure = e;
        }

        try
        {
            this.backend.setSourceBuffer(handle, 0);
            detached = true;
        }
        catch (RuntimeException | Error e)
        {
            failure = appendFailure(failure, e);
        }

        try
        {
            this.backend.deleteSource(handle);
            deleted = true;
        }
        catch (RuntimeException | Error e)
        {
            failure = appendFailure(failure, e);
        }
        finally
        {
            if (detached)
            {
                this.buffer = null;
            }

            if (deleted)
            {
                this.source = -1;
                this.buffer = null;
            }
        }

        if (failure instanceof RuntimeException runtime)
        {
            throw runtime;
        }
        else if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next)
    {
        if (first == null)
        {
            return next;
        }

        first.addSuppressed(next);

        return first;
    }
}
