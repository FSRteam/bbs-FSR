package mchorse.bbs_mod.plugin.watch;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable watcher output. Consumers enqueue it on the serial lifecycle
 * controller; the watcher never performs lifecycle work itself.
 */
public final class PluginWatchIntent
{
    private final Action action;
    private final Trigger trigger;
    private final PluginApplyMode applyMode;
    private final Path artifactPath;
    private final PluginArtifactFingerprint fingerprint;

    private PluginWatchIntent(
        Action action,
        Trigger trigger,
        PluginApplyMode applyMode,
        Path artifactPath,
        PluginArtifactFingerprint fingerprint
    )
    {
        this.action = Objects.requireNonNull(action, "action");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.applyMode = Objects.requireNonNull(applyMode, "applyMode");
        this.artifactPath = artifactPath == null ? null : artifactPath.toAbsolutePath().normalize();
        this.fingerprint = fingerprint;

        if (action == Action.UPSERT && (this.artifactPath == null || fingerprint == null))
        {
            throw new IllegalArgumentException("UPSERT requires an artifact path and fingerprint");
        }

        if (action == Action.DELETE && (this.artifactPath == null || fingerprint != null))
        {
            throw new IllegalArgumentException("DELETE requires only an artifact path");
        }

        if (action == Action.RECONCILE && (this.artifactPath != null || fingerprint != null))
        {
            throw new IllegalArgumentException("RECONCILE must not carry a path or fingerprint");
        }
    }

    public static PluginWatchIntent upsert(
        Path path,
        PluginArtifactFingerprint fingerprint,
        Trigger trigger,
        PluginApplyMode applyMode
    )
    {
        return new PluginWatchIntent(Action.UPSERT, trigger, applyMode, path, fingerprint);
    }

    public static PluginWatchIntent delete(Path path, Trigger trigger, PluginApplyMode applyMode)
    {
        return new PluginWatchIntent(Action.DELETE, trigger, applyMode, path, null);
    }

    public static PluginWatchIntent reconcile(Trigger trigger, PluginApplyMode applyMode)
    {
        return new PluginWatchIntent(Action.RECONCILE, trigger, applyMode, null, null);
    }

    public Action action()
    {
        return this.action;
    }

    public Trigger trigger()
    {
        return this.trigger;
    }

    public PluginApplyMode applyMode()
    {
        return this.applyMode;
    }

    public Optional<Path> artifactPath()
    {
        return Optional.ofNullable(this.artifactPath);
    }

    public Optional<PluginArtifactFingerprint> fingerprint()
    {
        return Optional.ofNullable(this.fingerprint);
    }

    public enum Action
    {
        UPSERT,
        DELETE,
        RECONCILE
    }

    public enum Trigger
    {
        STARTUP,
        WATCH_CREATE,
        WATCH_MODIFY,
        WATCH_DELETE,
        OVERFLOW,
        INVALID_WATCH_KEY,
        PERIODIC_RECONCILE,
        MANUAL_RESCAN,
        WATCHER_FAILURE
    }
}
