package mchorse.bbs_mod.ui.film.audio;

import java.nio.ByteBuffer;

/** Narrow OpenAL capture seam. Implementations must read interleaved signed PCM16 frames. */
public interface CaptureBackend
{
    /** Thrown by a backend when it can explicitly prove a requested layout is unsupported. */
    class UnsupportedModeException extends Exception
    {
        public UnsupportedModeException(String message)
        {
            super(message);
        }

        public UnsupportedModeException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    String defaultDevice() throws Exception;

    long open(String deviceName, CaptureSpec spec) throws Exception;

    void start(long device) throws Exception;

    int availableFrames(long device) throws Exception;

    void read(long device, ByteBuffer destination, int frames) throws Exception;

    void stop(long device) throws Exception;

    void close(long device) throws Exception;
}
