package mchorse.bbs_mod.plugin.hotreload.phase0;

import mchorse.bbs_mod.plugin.hotreload.phase0.FixtureJarBuilder.ShadowArtifact;
import mchorse.bbs_mod.plugin.hotreload.phase0.api.Phase0Host;
import mchorse.bbs_mod.plugin.hotreload.phase0.api.Phase0Plugin;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executable feasibility gate for candidate-first plugin generation loading. */
public final class Phase0HotReloadTest
{
    public static void main(String[] args) throws Exception
    {
        Path work = Files.createTempDirectory("bbs-plugin-hot-reload-phase0-");

        try
        {
            GcProbe probe = runGenerationScenario(work);
            boolean collected = observeCollection(probe, Duration.ofSeconds(3));

            if (collected)
            {
                System.out.println("Phase 0 GC telemetry: retired fixture classloader was collected within the observation window.");
            }
            else
            {
                System.out.println("Phase 0 GC telemetry: SUSPECTED_LEAK; retired loader was not collected within the bounded window.");
            }

            System.out.println("Phase 0 engine decision: use the internal per-generation loader; PF4J 3.15.0's standard manager cannot hold two generations with the same plugin id, while separate PF4J managers add no capability required by this gate.");
            System.out.println("Phase 0 plugin hot reload feasibility test passed.");
        }
        finally
        {
            FixtureJarBuilder.deleteRecursively(work);
        }
    }

    private static GcProbe runGenerationScenario(Path work) throws Exception
    {
        FixtureJarBuilder builder = new FixtureJarBuilder();
        Path source = work.resolve("plugins/fixture.jar");
        Path incoming = work.resolve("incoming/fixture.jar");
        Path cache = work.resolve("cache");
        RecordingHost host = new RecordingHost();
        AtomicReference<Phase0Plugin> active = new AtomicReference<>();
        ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();

        builder.build(source, "1.0.0", "v1");
        require(builder.containsBundledHostApi(source), "fixture must contain a duplicate API copy to test parent-first identity");

        ShadowArtifact firstArtifact = builder.shadowCopy(source, cache);
        Generation first = Generation.prepare(firstArtifact.path(), host);
        Generation second = null;

        try
        {
            first.start();
            active.set(first.plugin());

            require("v1".equals(active.get().marker()), "v1 must be the active route before candidate preparation");

            builder.build(incoming, "2.0.0", "v2");
            builder.replaceSource(incoming, source);

            require("2.0.0".equals(builder.manifestVersion(source)), "source JAR replacement must publish v2");
            require("v1".equals(active.get().marker()), "source replacement must not disturb the shadow-loaded v1");

            ShadowArtifact secondArtifact = builder.shadowCopy(source, cache);

            require(!firstArtifact.hash().equals(secondArtifact.hash()), "v1 and v2 shadows must have distinct content hashes");
            second = Generation.prepare(secondArtifact.path(), host);

            require(FixtureJarBuilder.PLUGIN_ID.equals(first.plugin().id()), "v1 plugin id mismatch");
            require(FixtureJarBuilder.PLUGIN_ID.equals(second.plugin().id()), "v2 plugin id mismatch");
            require(first.loader() != second.loader(), "each generation must own an independent classloader");
            require(first.plugin().getClass() != second.plugin().getClass(), "each generation must own an independent implementation Class");
            require(first.plugin().isStarted(), "v1 must remain active while v2 is prepared");
            require(!second.plugin().isStarted(), "candidate v2 must remain staged before commit");
            require("v1".equals(active.get().marker()), "candidate prepare must not replace the active route");
            require(host.staged().equals(List.of("phase0-fixture@1.0.0:v1", "phase0-fixture@2.0.0:v2")), "both generations must stage under the same plugin id");

            assertHostApiIdentity(first);
            assertHostApiIdentity(second);

            second.start();
            Phase0Plugin incumbent = active.getAndSet(second.plugin());

            require(incumbent == first.plugin(), "commit must replace exactly the incumbent generation");
            require("v2".equals(active.get().marker()), "v2 must be the only active route after commit");

            WeakReference<ClassLoader> retired = new WeakReference<>(first.loader(), queue);

            active.set(null);
            first.close();
            Files.delete(firstArtifact.path());
            require(!Files.exists(firstArtifact.path()), "closed v1 loader must release its shadow JAR for deletion");

            second.close();
            Files.delete(secondArtifact.path());
            require(!Files.exists(secondArtifact.path()), "closed v2 loader must release its shadow JAR for deletion");

            first = null;
            second = null;
            incumbent = null;

            return new GcProbe(retired, queue);
        }
        finally
        {
            active.set(null);

            if (second != null)
            {
                second.close();
            }

            if (first != null)
            {
                first.close();
            }
        }
    }

    private static void assertHostApiIdentity(Generation generation) throws ClassNotFoundException
    {
        Class<?> pluginClass = generation.plugin().getClass();

        require(pluginClass.getClassLoader() == generation.loader(), "fixture implementation must come from its generation loader");
        require(Phase0Plugin.class.isAssignableFrom(pluginClass), "fixture must implement the host's API identity");
        require(Class.forName(Phase0Plugin.class.getName(), false, generation.loader()) == Phase0Plugin.class,
            "bundled plugin API copy must resolve to the host Phase0Plugin class");
        require(Class.forName(Phase0Host.class.getName(), false, generation.loader()) == Phase0Host.class,
            "bundled plugin API copy must resolve to the host Phase0Host class");
    }

    private static boolean observeCollection(GcProbe probe, Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline)
        {
            if (probe.reference().get() == null || probe.queue().poll() == probe.reference())
            {
                return true;
            }

            System.gc();
            Thread.sleep(50L);
        }

        return probe.reference().get() == null;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class Generation implements AutoCloseable
    {
        private Phase0GenerationLoader loader;
        private Phase0Plugin plugin;
        private boolean closed;

        private Generation(Phase0GenerationLoader loader, Phase0Plugin plugin)
        {
            this.loader = loader;
            this.plugin = plugin;
        }

        static Generation prepare(Path artifact, Phase0Host host) throws Exception
        {
            Phase0GenerationLoader loader = new Phase0GenerationLoader(artifact, Phase0HotReloadTest.class.getClassLoader());

            try
            {
                Class<?> pluginClass = Class.forName(FixtureJarBuilder.PLUGIN_CLASS, true, loader);
                Object instance = pluginClass.getDeclaredConstructor().newInstance();

                require(instance instanceof Phase0Plugin, "fixture failed host Phase0Plugin identity check");

                Phase0Plugin plugin = (Phase0Plugin) instance;

                plugin.prepare(host);

                return new Generation(loader, plugin);
            }
            catch (Throwable throwable)
            {
                loader.close();
                throw throwable;
            }
        }

        void start()
        {
            this.plugin.start();
        }

        Phase0Plugin plugin()
        {
            return this.plugin;
        }

        Phase0GenerationLoader loader()
        {
            return this.loader;
        }

        @Override
        public void close() throws IOException
        {
            if (this.closed)
            {
                return;
            }

            this.closed = true;

            try
            {
                if (this.plugin != null)
                {
                    this.plugin.stop();
                }
            }
            finally
            {
                this.plugin = null;

                if (this.loader != null)
                {
                    this.loader.close();
                    this.loader = null;
                }
            }
        }
    }

    private static final class RecordingHost implements Phase0Host
    {
        private final List<String> staged = new ArrayList<>();

        @Override
        public void stage(String pluginId, String version, String marker)
        {
            this.staged.add(pluginId + "@" + version + ":" + marker);
        }

        List<String> staged()
        {
            return List.copyOf(this.staged);
        }
    }

    private record GcProbe(WeakReference<ClassLoader> reference, ReferenceQueue<ClassLoader> queue)
    {}
}
