package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.resources.Link;

import java.util.Objects;

/** Decoded OpenAL buffer cached by asset Link. Sources are owned separately. */
public class SoundBuffer
{
    private final Link id;
    private final SoundBackend backend;
    private int buffer;
    private final float duration;
    private Waveform waveform;

    /** Legacy constructor retained for source and binary compatibility. */
    public SoundBuffer(Link id, Wave wave, Waveform waveform)
    {
        this(id, wave, waveform, OpenALSoundBackend.INSTANCE);
    }

    public SoundBuffer(Link id, Wave wave, Waveform waveform, SoundBackend backend)
    {
        /* Legacy editor/player buffers are intentionally not asset-keyed. */
        this.id = id;
        Objects.requireNonNull(wave, "wave");
        this.backend = Objects.requireNonNull(backend, "backend");
        PcmFormat format = wave.getFormat();

        if (format.channels() != 1 && format.channels() != 2)
        {
            throw new IllegalArgumentException("Only mono and stereo playback are supported");
        }

        /* Validate OpenAL upload support before a native buffer is allocated. */
        wave.getALFormat();
        this.duration = wave.getDuration();
        this.waveform = waveform;

        try
        {
            this.buffer = backend.createBuffer(wave);

            if (this.buffer <= 0)
            {
                throw new IllegalStateException("OpenAL returned an invalid buffer handle");
            }
        }
        catch (RuntimeException | Error failure)
        {
            if (waveform != null)
            {
                try
                {
                    waveform.delete();
                }
                catch (RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(cleanup);
                }

                this.waveform = null;
            }

            throw failure;
        }
    }

    public Link getId()
    {
        return this.id;
    }

    public int getBuffer()
    {
        return this.buffer;
    }

    public float getDuration()
    {
        return this.duration;
    }

    public Waveform getWaveform()
    {
        return this.waveform;
    }

    public boolean isDeleted()
    {
        return this.buffer < 0;
    }

    SoundBackend getBackend()
    {
        return this.backend;
    }

    void setWaveform(Waveform waveform)
    {
        if (this.waveform == waveform)
        {
            return;
        }

        if (this.waveform != null)
        {
            this.waveform.delete();
        }

        this.waveform = waveform;
    }

    /** Idempotent buffer deletion. Manager callers release every referencing source first. */
    public void delete()
    {
        if (this.buffer < 0 && this.waveform == null)
        {
            return;
        }

        Throwable failure = null;
        int handle = this.buffer;

        if (handle > 0)
        {
            try
            {
                this.backend.deleteBuffer(handle);
                this.buffer = -1;
            }
            catch (RuntimeException | Error e)
            {
                failure = e;
            }
        }

        Waveform currentWaveform = this.waveform;

        if (currentWaveform != null)
        {
            try
            {
                currentWaveform.delete();
                this.waveform = null;
            }
            catch (RuntimeException | Error e)
            {
                if (failure == null)
                {
                    failure = e;
                }
                else
                {
                    failure.addSuppressed(e);
                }
            }
        }

        if (this.buffer < 0 && this.waveform == null)
        {
            return;
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

    /** Native and waveform resources have both completed cleanup. */
    public boolean isCleanupComplete()
    {
        return this.buffer < 0 && this.waveform == null;
    }
}
