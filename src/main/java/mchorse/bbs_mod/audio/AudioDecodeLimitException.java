package mchorse.bbs_mod.audio;

public class AudioDecodeLimitException extends AudioDecodeException
{
    public AudioDecodeLimitException(String message)
    {
        super(message);
    }

    public AudioDecodeLimitException(String source, String message)
    {
        super(source, message);
    }

    public AudioDecodeLimitException(String source, String message, Throwable cause)
    {
        super(source, message, cause);
    }
}
