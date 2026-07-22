package mchorse.bbs_mod.plugin.watch;

import mchorse.bbs_mod.plugin.content.PluginContentKind;
import mchorse.bbs_mod.plugin.content.PluginContentSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PluginContentWatcherTest
{
    public static void main(String[] args) throws Exception
    {
        contentSnapshotOwnsItsValues();
        reconciliationUsesContentHash();
        collectorWaitsForTwoStableSamples();
        collectorRetriesFailedIntentDelivery();
        watcherHandlesAtomicRenameAndDelete();

        System.out.println("Plugin content/watcher tests passed");
    }

    private static void contentSnapshotOwnsItsValues()
    {
        byte[] source = "hello".getBytes(StandardCharsets.UTF_8);
        String artifactHash = "a".repeat(64);
        PluginContentSnapshot snapshot = PluginContentSnapshot.builder("example", "1.0", 7, artifactHash)
            .put(PluginContentKind.LANGUAGE, "assets/example/lang/en_us.json", source)
            .build();

        source[0] = 'X';
        byte[] returned = snapshot.entry("assets/example/lang/en_us.json").orElseThrow().content();

        check(returned[0] == 'h', "snapshot retained the caller's mutable byte array");
        returned[0] = 'Y';
        check(snapshot.entry("assets/example/lang/en_us.json").orElseThrow().content()[0] == 'h',
            "snapshot exposed its internal byte array");
        check(snapshot.generation() == 7 && snapshot.totalBytes() == 5,
            "snapshot identity or size was corrupted");

        expectFailure(() -> PluginContentSnapshot.builder("example", "1.0", 1, artifactHash)
            .put(PluginContentKind.DATA, "../escape.json", new byte[0]));
        expectFailure(() -> PluginContentSnapshot.builder("example", "1.0", 1, artifactHash)
            .put(PluginContentKind.DATA, "data/example.json", new byte[0])
            .put(PluginContentKind.UI, "data/example.json", new byte[0]));
    }

    private static void reconciliationUsesContentHash() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-plugin-reconcile-");

        try
        {
            Path retained = root.resolve("retained.jar");
            Path removed = root.resolve("removed.jar");

            Files.writeString(retained, "one");
            Files.writeString(removed, "remove");

            PluginDirectorySnapshot first = PluginDirectoryReconciler.scan(root);

            Files.setLastModifiedTime(retained, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(retained).toMillis() + 1_000L
            ));
            Files.delete(removed);
            Files.writeString(root.resolve("added.jar"), "add");

            PluginDirectorySnapshot second = PluginDirectoryReconciler.scan(root);
            List<PluginWatchIntent> intents = PluginDirectoryReconciler.reconcile(
                first,
                second,
                PluginWatchIntent.Trigger.PERIODIC_RECONCILE,
                PluginApplyMode.AUTO_APPLY
            );

            check(intents.size() == 2, "mtime-only change produced a reload or a real change was lost");
            check(intents.stream().anyMatch((intent) -> intent.action() == PluginWatchIntent.Action.UPSERT
                    && intent.artifactPath().orElseThrow().endsWith("added.jar")),
                "reconciliation missed a new artifact");
            check(intents.stream().anyMatch((intent) -> intent.action() == PluginWatchIntent.Action.DELETE
                    && intent.artifactPath().orElseThrow().endsWith("removed.jar")),
                "reconciliation missed a deleted artifact");

            LinkedHashMap<Path, PluginArtifactFingerprint> noArtifacts = new LinkedHashMap<>();
            LinkedHashSet<Path> uncertain = new LinkedHashSet<>();

            uncertain.add(Path.of("retained.jar"));

            PluginDirectorySnapshot incomplete = new PluginDirectorySnapshot(root, noArtifacts, uncertain);

            check(PluginDirectoryReconciler.reconcile(
                first,
                incomplete,
                PluginWatchIntent.Trigger.OVERFLOW,
                PluginApplyMode.AUTO_APPLY
            ).stream().noneMatch((intent) -> intent.artifactPath().orElseThrow().endsWith("retained.jar")),
                "uncertain fingerprint was misclassified as a deletion");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void collectorWaitsForTwoStableSamples() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-plugin-collector-");

        try
        {
            Path artifact = root.resolve("example.jar");
            List<PluginWatchIntent> intents = new ArrayList<>();
            List<java.io.IOException> errors = new ArrayList<>();
            AtomicBoolean autoApply = new AtomicBoolean(true);
            PluginWatchIntentCollector collector = new PluginWatchIntentCollector(
                root,
                Duration.ofNanos(10),
                Duration.ofNanos(5),
                autoApply::get,
                intents::add,
                errors::add
            );

            collector.onFileEvent(root.resolve("not-visible-yet.jar"), PluginWatchIntentCollector.FileEvent.CREATE, 0);
            collector.poll(10);
            check(intents.isEmpty(), "not-yet-visible create event was misclassified as a deletion");

            Files.writeString(artifact, "partial");
            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.CREATE, 0);
            collector.poll(10);
            check(intents.isEmpty(), "collector emitted after only one stable sample");

            Files.writeString(artifact, "complete");
            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.MODIFY, 11);
            collector.poll(21);
            collector.poll(26);

            check(errors.isEmpty(), "collector reported an unexpected fingerprint error");
            check(intents.size() == 1 && intents.get(0).action() == PluginWatchIntent.Action.UPSERT,
                "collector did not coalesce a partial write into one upsert");
            check(intents.get(0).applyMode() == PluginApplyMode.AUTO_APPLY,
                "autoApply=true did not produce AUTO_APPLY");
            check(intents.get(0).fingerprint().orElseThrow().sameContent(PluginArtifactFingerprint.capture(artifact)),
                "collector emitted a stale partial-write fingerprint");

            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.MODIFY, 30);
            collector.poll(40);
            collector.poll(45);
            check(intents.size() == 1, "duplicate event for an unchanged hash emitted another upsert");

            autoApply.set(false);
            Files.writeString(artifact, "pending");
            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.MODIFY, 50);
            collector.poll(60);
            collector.poll(65);
            check(intents.get(1).applyMode() == PluginApplyMode.RELOAD_PENDING,
                "autoApply=false did not produce RELOAD_PENDING");

            Files.delete(artifact);
            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.DELETE, 70);
            collector.poll(80);
            check(intents.get(2).action() == PluginWatchIntent.Action.DELETE,
                "collector missed deletion after a stable upsert");
            check(intents.get(2).applyMode() == PluginApplyMode.RELOAD_PENDING,
                "delete bypassed disabled autoApply policy");

            Files.writeString(artifact, "superseded");
            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.CREATE, 90);
            collector.requestReconciliation(PluginWatchIntent.Trigger.OVERFLOW, false);
            check(intents.get(3).action() == PluginWatchIntent.Action.RECONCILE
                    && intents.get(3).trigger() == PluginWatchIntent.Trigger.OVERFLOW,
                "overflow did not supersede path events with reconciliation");
            collector.poll(1_000);
            check(intents.size() == 4, "path intent survived an overflow reconciliation request");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void watcherHandlesAtomicRenameAndDelete() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-plugin-watch-");
        LinkedBlockingQueue<PluginWatchIntent> intents = new LinkedBlockingQueue<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(
            root,
            true,
            new PluginDirectoryWatcher.Config(
                Duration.ofMillis(30),
                Duration.ofMillis(20),
                Duration.ofSeconds(30)
            ),
            intents::add,
            errors::add
        );

        try
        {
            watcher.start();
            PluginWatchIntent startup = await(intents, PluginWatchIntent.Action.RECONCILE);

            check(startup.trigger() == PluginWatchIntent.Trigger.STARTUP,
                "watcher did not request startup reconciliation");

            Path temporary = root.resolve("example.jar.tmp");
            Path artifact = root.resolve("example.jar");

            Files.writeString(temporary, "atomic");
            Files.move(temporary, artifact, StandardCopyOption.ATOMIC_MOVE);

            PluginWatchIntent upsert = await(intents, PluginWatchIntent.Action.UPSERT);

            check(upsert.artifactPath().orElseThrow().equals(artifact.toAbsolutePath().normalize()),
                "atomic rename emitted the wrong artifact path");

            Files.delete(artifact);

            PluginWatchIntent delete = await(intents, PluginWatchIntent.Action.DELETE);

            check(delete.artifactPath().orElseThrow().equals(artifact.toAbsolutePath().normalize()),
                "watcher missed the deleted artifact");

            watcher.setAutoApply(false);
            watcher.requestRescan(false);
            PluginWatchIntent pending = await(intents, PluginWatchIntent.Action.RECONCILE);

            check(pending.applyMode() == PluginApplyMode.RELOAD_PENDING,
                "manual rescan did not preserve disabled autoApply policy");

            watcher.requestRescan(true);
            PluginWatchIntent forced = await(intents, PluginWatchIntent.Action.RECONCILE);

            check(forced.applyMode() == PluginApplyMode.MANUAL_APPLY,
                "confirmed manual rescan was not marked for apply");
            check(errors.isEmpty(), "watcher reported unexpected errors: " + errors);
        }
        finally
        {
            watcher.close();
            deleteTree(root);
        }
    }

    private static void collectorRetriesFailedIntentDelivery() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-plugin-delivery-");

        try
        {
            Path artifact = root.resolve("delivery.jar");
            AtomicInteger attempts = new AtomicInteger();
            List<PluginWatchIntent> delivered = new ArrayList<>();
            PluginWatchIntentCollector collector = new PluginWatchIntentCollector(
                root,
                Duration.ofNanos(10),
                Duration.ofNanos(5),
                () -> true,
                (intent) ->
                {
                    if (attempts.getAndIncrement() == 0)
                    {
                        throw new IllegalStateException("synthetic queue failure");
                    }

                    delivered.add(intent);
                },
                (error) -> {}
            );

            Files.writeString(artifact, "delivery");
            collector.onFileEvent(artifact, PluginWatchIntentCollector.FileEvent.CREATE, 0);
            collector.poll(10);

            try
            {
                collector.poll(15);
                throw new AssertionError("synthetic intent delivery failure was swallowed");
            }
            catch (IllegalStateException e)
            {
                check(e.getMessage().contains("synthetic"), "collector threw the wrong delivery failure");
            }

            collector.poll(16);

            check(attempts.get() == 2 && delivered.size() == 1,
                "failed delivery advanced the collector baseline instead of retrying");
            check(delivered.get(0).fingerprint().orElseThrow().sameContent(PluginArtifactFingerprint.capture(artifact)),
                "retried delivery carried the wrong fingerprint");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static PluginWatchIntent await(
        LinkedBlockingQueue<PluginWatchIntent> intents,
        PluginWatchIntent.Action action
    ) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() - deadline < 0)
        {
            PluginWatchIntent intent = intents.poll(250, TimeUnit.MILLISECONDS);

            if (intent != null && intent.action() == action)
            {
                return intent;
            }
        }

        throw new AssertionError("Timed out waiting for " + action + "; queued=" + Arrays.toString(intents.toArray()));
    }

    private static void deleteTree(Path root) throws Exception
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }

        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void expectFailure(Runnable runnable)
    {
        try
        {
            runnable.run();
        }
        catch (IllegalArgumentException e)
        {
            return;
        }

        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
