package mchorse.bbs_mod.audio;

public class UnsupportedAudioFormatException extends AudioDecodeException
{
    public UnsupportedAudioFormatException(String message)
    {
        super(message);
    }

    public UnsupportedAudioFormatException(String source, String message)
    {
        super(source, message);
    }

    public UnsupportedAudioFormatException(String source, String message, Throwable cause)
    {
        super(source, message, cause);
    }
}
