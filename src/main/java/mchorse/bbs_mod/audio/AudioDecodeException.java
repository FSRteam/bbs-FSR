package mchorse.bbs_mod.audio;

import java.io.IOException;

/** A bounded, actionable failure while decoding an audio container. */
public class AudioDecodeException extends IOException
{
    private final String source;

    public AudioDecodeException(String message)
    {
        this(null, message, null);
    }

    public AudioDecodeException(String message, Throwable cause)
    {
        this(null, message, cause);
    }

    public AudioDecodeException(String source, String message)
    {
        this(source, message, null);
    }

    public AudioDecodeException(String source, String message, Throwable cause)
    {
        super(source == null || source.isBlank() ? message : source + ": " + message, cause);
        this.source = source;
    }

    public String source()
    {
        return this.source;
    }
}
