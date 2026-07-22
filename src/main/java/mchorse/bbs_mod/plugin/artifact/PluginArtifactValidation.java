package mchorse.bbs_mod.plugin.artifact;

import mchorse.bbs_mod.api.plugin.BBSPluginManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable validation output. Invalid candidates carry diagnostics but never a loadable artifact. */
public record PluginArtifactValidation(Path source, PluginArtifactStatus status,
                                       BBSPluginManifest manifest, String sha256,
                                       long sizeBytes, int entryCount,
                                       List<PluginArtifactIssue> issues,
                                       PluginValidatedArtifact artifact)
{
    public PluginArtifactValidation
    {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        status = Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues == null ? List.of() : issues);

        if (artifact != null && status != PluginArtifactStatus.VALID)
        {
            throw new IllegalArgumentException("only VALID results may expose a loadable artifact");
        }
    }

    public boolean accepted()
    {
        return this.status == PluginArtifactStatus.VALID && this.artifact != null;
    }

    public Optional<PluginValidatedArtifact> loadableArtifact()
    {
        return Optional.ofNullable(this.artifact);
    }

    public static PluginArtifactValidation failure(Path source, PluginArtifactStatus status,
                                                   String code, String message)
    {
        return new PluginArtifactValidation(source, status, null, "", 0, 0,
            List.of(new PluginArtifactIssue(code, message)), null);
    }
}
