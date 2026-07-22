package mchorse.bbs_mod.plugin.watch;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Signals that an artifact changed while its fingerprint was being captured.
 */
public final class UnstablePluginArtifactException extends IOException
{
    private static final long serialVersionUID = 1L;

    public UnstablePluginArtifactException(Path path)
    {
        super("Plugin artifact changed while it was being fingerprinted: " + path);
    }
}
