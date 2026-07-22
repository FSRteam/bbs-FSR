package mchorse.bbs_mod.plugin.artifact;

/** Checked failure for bounded manifest/artifact processing. */
public class PluginArtifactException extends Exception
{
    private static final long serialVersionUID = 1L;

    private final PluginArtifactStatus status;
    private final String code;

    public PluginArtifactException(PluginArtifactStatus status, String code, String message)
    {
        super(message);
        this.status = status;
        this.code = code;
    }

    public PluginArtifactException(PluginArtifactStatus status, String code, String message, Throwable cause)
    {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public PluginArtifactStatus status()
    {
        return this.status;
    }

    public String code()
    {
        return this.code;
    }
}
