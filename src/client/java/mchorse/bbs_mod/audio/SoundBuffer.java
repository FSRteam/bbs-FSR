package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.resources.Link;

import java.util.Objects;

/** Decoded OpenAL buffer cached by asset Link. Sources are owned separately. */
public class SoundBuffer
{
    private final Link id;
    private final SoundBackend backend;
    private int buffer;
    private int spatialBuffer;
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

        int createdBuffer = -1;
        int createdSpatialBuffer = -1;

        try
        {
            createdBuffer = backend.createBuffer(wave);

            if (createdBuffer <= 0)
            {
                throw new IllegalStateException("OpenAL returned an invalid buffer handle");
            }

            /* OpenAL only applies positional attenuation and panning to mono
             * buffers. Keep authored stereo for listener-relative playback,
             * and derive the spatial variant from the same decoded Wave. */
            if (format.layout() == ChannelLayout.STEREO)
            {
                createdSpatialBuffer = backend.createBuffer(wave.convertLayout(ChannelLayout.MONO));

                if (createdSpatialBuffer <= 0)
                {
                    throw new IllegalStateException("OpenAL returned an invalid spatial buffer handle");
                }
            }
            else
            {
                createdSpatialBuffer = createdBuffer;
            }

            this.buffer = createdBuffer;
            this.spatialBuffer = createdSpatialBuffer;
        }
        catch (RuntimeException | Error failure)
        {
            if (createdSpatialBuffer > 0 && createdSpatialBuffer != createdBuffer)
            {
                try
                {
                    backend.deleteBuffer(createdSpatialBuffer);
                }
                catch (RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(cleanup);
                }
            }

            if (createdBuffer > 0)
            {
                try
                {
                    backend.deleteBuffer(createdBuffer);
                }
                catch (RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(cleanup);
                }
            }

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

    /** Select the original-layout or mono positional OpenAL buffer. */
    public int getBuffer(boolean spatial)
    {
        return spatial ? this.spatialBuffer : this.buffer;
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
        return this.buffer < 0 && this.spatialBuffer < 0;
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
        if (this.isDeleted() && this.waveform == null)
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

                if (this.spatialBuffer == handle)
                {
                    this.spatialBuffer = -1;
                }
            }
            catch (RuntimeException | Error e)
            {
                failure = e;
            }
        }

        int spatialHandle = this.spatialBuffer;

        if (spatialHandle > 0 && spatialHandle != handle)
        {
            try
            {
                this.backend.deleteBuffer(spatialHandle);
                this.spatialBuffer = -1;
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

        if (this.isCleanupComplete())
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
        return this.isDeleted() && this.waveform == null;
    }
}
