package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceDemand;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceFrame;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceListener;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceSubscription;
import mchorse.bbs_mod.api.registry.BBSRegistrationStatus;
import mchorse.bbs_mod.plugin.runtime.PluginGenerationGate;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class BBSRenderSurfaceHotSubscriptionTest
{
    private static final Set<BBSRenderSurfaceKind> KINDS = EnumSet.of(BBSRenderSurfaceKind.FILM_PREVIEW);

    private BBSRenderSurfaceHotSubscriptionTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("BBSRenderSurfaceHotSubscriptionTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        rejectsMismatchedOwnerFence();
        routesOnlyTheCurrentOpenGeneration();
        removesOnlyTheExactSubscription();
    }

    private static void rejectsMismatchedOwnerFence()
    {
        PluginOwner owner = new PluginOwner("surface-owner-mismatch", 1L);
        PluginGenerationGate otherGate = new PluginGenerationGate(
            new PluginOwner("surface-owner-mismatch", 2L)
        );
        BBSRenderSurfaceSubscription subscription = BBSRenderSurfaceRegistry.subscribe(
            owner,
            otherGate.fence(),
            new CountingListener()
        );

        check(subscription.registration().status() == BBSRegistrationStatus.REJECTED,
            "an owner/fence mismatch was accepted");
        check(!subscription.active(), "a rejected subscription reported active");
        subscription.close();
        subscription.close();
    }

    private static void routesOnlyTheCurrentOpenGeneration() throws Exception
    {
        PluginOwner incumbentOwner = new PluginOwner("surface-generation-route", 1L);
        PluginOwner candidateOwner = new PluginOwner("surface-generation-route", 2L);
        PluginGenerationGate incumbentGate = new PluginGenerationGate(incumbentOwner);
        PluginGenerationGate candidateGate = new PluginGenerationGate(candidateOwner);
        CountingListener incumbent = new CountingListener();
        CountingListener candidate = new CountingListener();
        BBSRenderSurfaceSubscription incumbentSubscription = BBSRenderSurfaceRegistry.subscribe(
            incumbentOwner,
            incumbentGate.fence(),
            incumbent
        );
        BBSRenderSurfaceSubscription candidateSubscription = BBSRenderSurfaceRegistry.subscribe(
            candidateOwner,
            candidateGate.fence(),
            candidate
        );

        try
        {
            check(incumbentSubscription.registration().accepted(), "incumbent registration failed");
            check(candidateSubscription.registration().accepted(), "candidate registration failed");
            check(!incumbentSubscription.active() && !candidateSubscription.active(),
                "a staged generation became routable before activation");

            incumbentGate.activate();
            awaitDemand(incumbentOwner);
            publish(1L);

            check(incumbent.frames.get() == 1, "active incumbent missed its frame callback");
            check(candidate.frames.get() == 0, "staged candidate received a frame callback");
            check(candidate.demands.get() == 0, "staged candidate demand callback was sampled");

            candidateGate.activate();
            awaitDemand(candidateOwner);
            publish(2L);

            check(!incumbentSubscription.active() && candidateSubscription.active(),
                "route did not expose exactly one current generation");
            check(incumbent.frames.get() == 1,
                "incumbent remained routed after the newer generation activated");
            check(candidate.frames.get() == 1,
                "newer active generation did not receive the frame callback");

            incumbentGate.beginDrain();
            incumbentSubscription.close();
            incumbentGate.close();
            publish(3L);

            check(candidate.frames.get() == 2,
                "closing the incumbent removed or fenced the replacement generation");

            candidateGate.beginDrain();
            publish(4L);
            check(candidate.frames.get() == 2,
                "draining generation received a newly admitted callback");
        }
        finally
        {
            incumbentSubscription.close();
            candidateSubscription.close();
            closeGate(incumbentGate);
            closeGate(candidateGate);
        }
    }

    private static void removesOnlyTheExactSubscription() throws Exception
    {
        PluginOwner owner = new PluginOwner("surface-exact-close", 1L);
        PluginGenerationGate gate = new PluginGenerationGate(owner);
        CountingListener original = new CountingListener();
        CountingListener replacement = new CountingListener();
        BBSRenderSurfaceSubscription first = BBSRenderSurfaceRegistry.subscribe(owner, gate.fence(), original);
        BBSRenderSurfaceSubscription duplicate = BBSRenderSurfaceRegistry.subscribe(owner, gate.fence(), replacement);

        try
        {
            check(first.registration().accepted(), "first exact-owner registration failed");
            check(duplicate.registration().status() == BBSRegistrationStatus.DUPLICATE,
                "duplicate exact-owner registration was accepted");

            gate.activate();
            awaitDemand(owner);
            duplicate.close();
            publish(5L);
            check(original.frames.get() == 1,
                "closing a rejected duplicate removed the accepted subscription");

            first.close();
            BBSRenderSurfaceSubscription second = BBSRenderSurfaceRegistry.subscribe(owner, gate.fence(), replacement);

            try
            {
                check(second.registration().accepted(), "closed owner could not be registered again");
                awaitDemand(owner);
                first.close();
                publish(6L);
                check(replacement.frames.get() == 1,
                    "a stale close removed the replacement subscription");

                second.close();
                publish(7L);
                check(replacement.frames.get() == 1,
                    "closed subscription received a later frame callback");
            }
            finally
            {
                second.close();
            }
        }
        finally
        {
            first.close();
            duplicate.close();
            closeGate(gate);
        }
    }

    private static void awaitDemand(PluginOwner owner) throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(2L).toNanos();

        while (System.nanoTime() - deadline < 0L)
        {
            BBSRenderSurfaceCapturePlan plan = BBSRenderSurfaceRegistry.capturePlan(KINDS);

            if (plan != null && BBSRenderSurfaceRegistry.demandRefreshIdle(owner))
            {
                return;
            }

            Thread.sleep(5L);
        }

        throw new AssertionError("timed out waiting for render-surface demand from " + owner);
    }

    private static void publish(long sequence)
    {
        BBSRenderSurfaceFrame frame = new BBSRenderSurfaceFrame(
            KINDS,
            1L,
            sequence,
            System.nanoTime(),
            32,
            18,
            true,
            new byte[] {1, 2, 3}
        );

        BBSRenderSurfaceRegistry.publish(frame, generation -> generation == 1L, generation -> generation == 1L);
    }

    private static void closeGate(PluginGenerationGate gate) throws InterruptedException
    {
        gate.beginDrain();
        check(gate.awaitDrained(Duration.ofSeconds(1L)), "test generation did not drain");
        gate.close();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class CountingListener implements BBSRenderSurfaceListener
    {
        private final AtomicInteger demands = new AtomicInteger();
        private final AtomicInteger frames = new AtomicInteger();

        @Override
        public BBSRenderSurfaceDemand demand()
        {
            this.demands.incrementAndGet();
            return BBSRenderSurfaceDemand.desktop(KINDS);
        }

        @Override
        public void onFrame(BBSRenderSurfaceFrame frame)
        {
            this.frames.incrementAndGet();
        }
    }
}
