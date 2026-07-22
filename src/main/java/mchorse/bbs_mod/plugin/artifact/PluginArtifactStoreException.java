package mchorse.bbs_mod.plugin.artifact;

/** Checked failure while publishing a validated shadow artifact or retention pointer. */
public class PluginArtifactStoreException extends Exception
{
    private static final long serialVersionUID = 1L;

    private final String code;

    public PluginArtifactStoreException(String code, String message)
    {
        super(message);
        this.code = code;
    }

    public PluginArtifactStoreException(String code, String message, Throwable cause)
    {
        super(message, cause);
        this.code = code;
    }

    public String code()
    {
        return this.code;
    }
}
