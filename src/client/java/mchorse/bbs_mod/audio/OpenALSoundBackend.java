package mchorse.bbs_mod.audio;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

final class OpenALSoundBackend implements SoundBackend
{
    static final OpenALSoundBackend INSTANCE = new OpenALSoundBackend();

    private OpenALSoundBackend()
    {}

    @Override
    public int createBuffer(Wave wave)
    {
        clearError();
        int handle = AL10.alGenBuffers();
        ByteBuffer data = null;

        try
        {
            checkError("create sound buffer");
            data = MemoryUtil.memAlloc(wave.data.length);
            data.put(wave.data).flip();
            clearError();
            AL10.alBufferData(handle, wave.getALFormat(), data, wave.sampleRate);
            checkError("upload sound buffer");

            return handle;
        }
        catch (RuntimeException | Error failure)
        {
            if (handle > 0)
            {
                try
                {
                    clearError();
                    AL10.alDeleteBuffers(handle);
                    checkError("delete failed sound buffer");
                }
                catch (RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(cleanup);
                }
            }

            throw failure;
        }
        finally
        {
            if (data != null)
            {
                MemoryUtil.memFree(data);
            }
        }
    }

    @Override
    public void deleteBuffer(int buffer)
    {
        clearError();
        AL10.alDeleteBuffers(buffer);
        checkError("delete sound buffer");
    }

    @Override
    public int createSource()
    {
        clearError();
        int source = AL10.alGenSources();

        try
        {
            checkError("create sound source");
        }
        catch (RuntimeException | Error failure)
        {
            if (source > 0)
            {
                try
                {
                    clearError();
                    AL10.alDeleteSources(source);
                    checkError("delete failed sound source");
                }
                catch (RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(cleanup);
                }
            }

            throw failure;
        }

        return source;
    }

    @Override
    public void setSourceBuffer(int source, int buffer)
    {
        clearError();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        checkError(buffer == 0 ? "detach sound source buffer" : "attach sound source buffer");
    }

    @Override
    public void setSourceMaxDistance(int source, float distance)
    {
        clearError();
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, distance);
        checkError("set sound source distance");
    }

    @Override
    public void setSourceVolume(int source, float volume)
    {
        clearError();
        AL10.alSourcef(source, AL10.AL_GAIN, volume);
        checkError("set sound source volume");
    }

    @Override
    public void setSourcePitch(int source, float pitch)
    {
        clearError();
        AL10.alSourcef(source, AL10.AL_PITCH, pitch);
        checkError("set sound source pitch");
    }

    @Override
    public void setSourceRelative(int source, boolean relative)
    {
        clearError();
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
        checkError("set sound source relativity");
    }

    @Override
    public void setSourceLooping(int source, boolean looping)
    {
        clearError();
        AL10.alSourcei(source, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
        checkError("set sound source looping");
    }

    @Override
    public void setSourcePosition(int source, float x, float y, float z)
    {
        clearError();
        AL10.alSource3f(source, AL10.AL_POSITION, x, y, z);
        checkError("set sound source position");
    }

    @Override
    public void setSourceVelocity(int source, float x, float y, float z)
    {
        clearError();
        AL10.alSource3f(source, AL10.AL_VELOCITY, x, y, z);
        checkError("set sound source velocity");
    }

    @Override
    public void playSource(int source)
    {
        clearError();
        AL10.alSourcePlay(source);
        checkError("play sound source");
    }

    @Override
    public void pauseSource(int source)
    {
        clearError();
        AL10.alSourcePause(source);
        checkError("pause sound source");
    }

    @Override
    public void stopSource(int source)
    {
        clearError();
        AL10.alSourceStop(source);
        checkError("stop sound source");
    }

    @Override
    public boolean isSourcePlaying(int source)
    {
        return sourceState(source) == AL10.AL_PLAYING;
    }

    @Override
    public boolean isSourcePaused(int source)
    {
        return sourceState(source) == AL10.AL_PAUSED;
    }

    @Override
    public boolean isSourceStopped(int source)
    {
        int state = sourceState(source);

        return state == AL10.AL_STOPPED || state == AL10.AL_INITIAL;
    }

    @Override
    public float getSourcePosition(int source)
    {
        clearError();
        float position = AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET);
        checkError("query sound source position");

        return position;
    }

    @Override
    public void setSourcePositionSeconds(int source, float seconds)
    {
        clearError();
        AL10.alSourcef(source, AL11.AL_SEC_OFFSET, seconds);
        checkError("seek sound source");
    }

    @Override
    public void deleteSource(int source)
    {
        clearError();
        AL10.alDeleteSources(source);
        checkError("delete sound source");
    }

    private static int sourceState(int source)
    {
        clearError();
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        checkError("query sound source state");

        return state;
    }

    private static void clearError()
    {
        while (AL10.alGetError() != AL10.AL_NO_ERROR)
        {}
    }

    private static void checkError(String operation)
    {
        int error = AL10.alGetError();

        if (error != AL10.AL_NO_ERROR)
        {
            throw new IllegalStateException("OpenAL failed to " + operation + " (AL error 0x"
                + Integer.toHexString(error) + ")");
        }
    }
}
