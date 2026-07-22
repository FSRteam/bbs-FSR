package mchorse.bbs_mod.plugin.artifact;

/** Result classification used before any plugin entrypoint is loaded. */
public enum PluginArtifactStatus
{
    VALID,
    INVALID,
    INCOMPATIBLE,
    RESTART_REQUIRED
}
