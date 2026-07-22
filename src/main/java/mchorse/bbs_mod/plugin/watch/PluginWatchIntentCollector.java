package mchorse.bbs_mod.plugin.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Serial debounce and stability state machine shared by the WatchService
 * daemon and deterministic tests.
 */
public final class PluginWatchIntentCollector
{
    private final Path root;
    private final long debounceNanos;
    private final long stabilityNanos;
    private final BooleanSupplier autoApply;
    private final Consumer<PluginWatchIntent> sink;
    private final Consumer<IOException> errorSink;
    private final Map<Path, PendingChange> pending = new HashMap<>();
    private final Map<Path, String> deliveredHashes = new HashMap<>();
    private final Set<Path> deliveredMissing = new HashSet<>();

    public PluginWatchIntentCollector(
        Path root,
        Duration debounce,
        Duration stabilityInterval,
        BooleanSupplier autoApply,
        Consumer<PluginWatchIntent> sink,
        Consumer<IOException> errorSink
    )
    {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.debounceNanos = requirePositive(debounce, "debounce");
        this.stabilityNanos = requirePositive(stabilityInterval, "stabilityInterval");
        this.autoApply = Objects.requireNonNull(autoApply, "autoApply");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
    }

    public void onFileEvent(Path path, FileEvent event, long nowNanos)
    {
        Path artifact = this.requireContainedArtifact(path);

        if (!PluginDirectoryReconciler.isPluginArtifact(artifact))
        {
            return;
        }

        this.deliveredMissing.remove(artifact);

        PendingChange current = this.pending.get(artifact);
        PluginWatchIntent.Trigger trigger = trigger(event, current);

        this.pending.put(artifact, new PendingChange(trigger, add(nowNanos, this.debounceNanos), null));
    }

    public void poll(long nowNanos)
    {
        List<Map.Entry<Path, PendingChange>> ready = new ArrayList<>();

        for (Map.Entry<Path, PendingChange> entry : this.pending.entrySet())
        {
            if (nowNanos - entry.getValue().dueNanos >= 0)
            {
                ready.add(entry);
            }
        }

        ready.sort(Comparator.comparing((entry) -> entry.getKey().toString()));

        for (Map.Entry<Path, PendingChange> entry : ready)
        {
            PendingChange change = this.pending.get(entry.getKey());

            if (change != entry.getValue())
            {
                continue;
            }

            this.poll(entry.getKey(), change, nowNanos);
        }
    }

    public OptionalLong nextDeadlineNanos()
    {
        long next = Long.MAX_VALUE;

        for (PendingChange change : this.pending.values())
        {
            next = Math.min(next, change.dueNanos);
        }

        return next == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(next);
    }

    public void requestReconciliation(PluginWatchIntent.Trigger trigger, boolean forceApply)
    {
        this.sink.accept(PluginWatchIntent.reconcile(trigger, this.applyMode(forceApply)));
        this.pending.clear();
    }

    public void replaceBaseline(PluginDirectorySnapshot snapshot)
    {
        Objects.requireNonNull(snapshot, "snapshot");

        if (!this.root.equals(snapshot.root()))
        {
            throw new IllegalArgumentException("Snapshot belongs to a different plugin directory");
        }

        this.pending.clear();
        this.deliveredHashes.clear();
        this.deliveredMissing.clear();

        for (Map.Entry<Path, PluginArtifactFingerprint> entry : snapshot.artifacts().entrySet())
        {
            this.deliveredHashes.put(snapshot.resolve(entry.getKey()), entry.getValue().sha256());
        }
    }

    public void clearPending()
    {
        this.pending.clear();
    }

    private void poll(Path artifact, PendingChange change, long nowNanos)
    {
        if (!Files.exists(artifact, LinkOption.NOFOLLOW_LINKS))
        {
            boolean previouslyObserved = this.deliveredHashes.containsKey(artifact);
            boolean explicitDelete = change.trigger == PluginWatchIntent.Trigger.WATCH_DELETE;

            if ((explicitDelete || previouslyObserved) && !this.deliveredMissing.contains(artifact))
            {
                this.sink.accept(PluginWatchIntent.delete(
                    artifact,
                    PluginWatchIntent.Trigger.WATCH_DELETE,
                    this.applyMode(false)
                ));
                this.deliveredMissing.add(artifact);
            }

            this.pending.remove(artifact);
            this.deliveredHashes.remove(artifact);

            return;
        }

        PluginArtifactFingerprint fingerprint;

        try
        {
            fingerprint = PluginArtifactFingerprint.capture(artifact);
        }
        catch (NoSuchFileException e)
        {
            this.pending.put(artifact, new PendingChange(change.trigger, add(nowNanos, this.stabilityNanos), null));

            return;
        }
        catch (IOException e)
        {
            this.errorSink.accept(e);
            this.pending.put(artifact, new PendingChange(change.trigger, add(nowNanos, this.stabilityNanos), null));

            return;
        }

        if (!fingerprint.equals(change.sample))
        {
            this.pending.put(artifact, new PendingChange(
                change.trigger,
                add(nowNanos, this.stabilityNanos),
                fingerprint
            ));

            return;
        }

        if (!fingerprint.sha256().equals(this.deliveredHashes.get(artifact)))
        {
            this.sink.accept(PluginWatchIntent.upsert(
                artifact,
                fingerprint,
                change.trigger,
                this.applyMode(false)
            ));
            this.deliveredHashes.put(artifact, fingerprint.sha256());
        }

        this.pending.remove(artifact);
        this.deliveredMissing.remove(artifact);
    }

    private Path requireContainedArtifact(Path path)
    {
        Objects.requireNonNull(path, "path");

        Path artifact = path.isAbsolute()
            ? path.toAbsolutePath().normalize()
            : this.root.resolve(path).toAbsolutePath().normalize();

        if (!artifact.startsWith(this.root) || artifact.getParent() == null || !artifact.getParent().equals(this.root))
        {
            throw new IllegalArgumentException("Watch event escaped the top-level plugin directory: " + path);
        }

        return artifact;
    }

    private PluginApplyMode applyMode(boolean forceApply)
    {
        if (forceApply)
        {
            return PluginApplyMode.MANUAL_APPLY;
        }

        return this.autoApply.getAsBoolean() ? PluginApplyMode.AUTO_APPLY : PluginApplyMode.RELOAD_PENDING;
    }

    private static PluginWatchIntent.Trigger trigger(FileEvent event, PendingChange current)
    {
        if (current != null && current.trigger == PluginWatchIntent.Trigger.WATCH_CREATE && event == FileEvent.MODIFY)
        {
            return current.trigger;
        }

        return switch (event)
        {
            case CREATE -> PluginWatchIntent.Trigger.WATCH_CREATE;
            case MODIFY -> PluginWatchIntent.Trigger.WATCH_MODIFY;
            case DELETE -> PluginWatchIntent.Trigger.WATCH_DELETE;
        };
    }

    private static long requirePositive(Duration duration, String name)
    {
        Objects.requireNonNull(duration, name);

        if (duration.isZero() || duration.isNegative())
        {
            throw new IllegalArgumentException(name + " must be positive");
        }

        try
        {
            return duration.toNanos();
        }
        catch (ArithmeticException e)
        {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }

    private static long add(long value, long increment)
    {
        long result = value + increment;

        if (((value ^ result) & (increment ^ result)) < 0)
        {
            return increment > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }

        return result;
    }

    public enum FileEvent
    {
        CREATE,
        MODIFY,
        DELETE
    }

    private record PendingChange(
        PluginWatchIntent.Trigger trigger,
        long dueNanos,
        PluginArtifactFingerprint sample
    )
    {}
}
