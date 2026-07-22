package mchorse.bbs_mod.audio;

public class MalformedAudioException extends AudioDecodeException
{
    public MalformedAudioException(String message)
    {
        super(message);
    }

    public MalformedAudioException(String source, String message)
    {
        super(source, message);
    }

    public MalformedAudioException(String source, String message, Throwable cause)
    {
        super(source, message, cause);
    }
}
