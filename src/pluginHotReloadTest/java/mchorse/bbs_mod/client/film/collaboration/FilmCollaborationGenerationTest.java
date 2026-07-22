package mchorse.bbs_mod.client.film.collaboration;

import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationListener;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationSubscription;
import mchorse.bbs_mod.api.registry.BBSRegistrationStatus;
import mchorse.bbs_mod.plugin.runtime.PluginGenerationGate;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;

import java.util.concurrent.atomic.AtomicInteger;

/** Focused generation-routing coverage for the Film hot-plugin adapter. */
public final class FilmCollaborationGenerationTest
{
    private FilmCollaborationGenerationTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("FilmCollaborationGenerationTest: all tests passed");
    }

    public static void runAll()
    {
        rejectsMismatchedOwnerFence();
        stagedReplacementKeepsIncumbentUntilActivation();
        staleCloseCannotRetireReplacementRoute();
        rejectedDuplicateCannotRemoveAcceptedRoute();
    }

    private static void rejectsMismatchedOwnerFence()
    {
        PluginOwner owner = new PluginOwner("film-owner-mismatch", 1L);
        PluginGenerationGate otherGate = new PluginGenerationGate(
            new PluginOwner("film-owner-mismatch", 2L)
        );
        BBSFilmCollaborationSubscription subscription = BBSFilmCollaborationRegistry.subscribe(
            owner,
            otherGate.fence(),
            listener(new AtomicInteger())
        );

        check(subscription.registration().status() == BBSRegistrationStatus.REJECTED,
            "an owner/fence mismatch was accepted");
        check(!subscription.active(), "a rejected subscription reported active");
        subscription.close();
        closeGate(otherGate);
    }

    private static void stagedReplacementKeepsIncumbentUntilActivation()
    {
        PluginOwner firstOwner = new PluginOwner("film-stage", 1L);
        PluginOwner secondOwner = new PluginOwner("film-stage", 2L);
        PluginGenerationGate firstGate = new PluginGenerationGate(firstOwner);
        PluginGenerationGate secondGate = new PluginGenerationGate(secondOwner);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();

        firstGate.activate();

        BBSFilmCollaborationSubscription first = BBSFilmCollaborationRegistry.subscribe(
            firstOwner,
            firstGate.fence(),
            listener(firstCalls)
        );
        BBSFilmCollaborationSubscription second = BBSFilmCollaborationRegistry.subscribe(
            secondOwner,
            secondGate.fence(),
            listener(secondCalls)
        );

        check(first.registration().accepted(), "active generation registration was rejected");
        check(second.registration().accepted(), "staged replacement registration was rejected");

        BBSFilmCollaborationRegistry.publishLocalMutations(null);
        check(firstCalls.get() == 1 && secondCalls.get() == 0,
            "staged generation displaced the active incumbent");

        secondGate.activate();
        BBSFilmCollaborationRegistry.publishLocalMutations(null);
        check(firstCalls.get() == 1 && secondCalls.get() == 1,
            "newest active generation was not the unique callback route");

        first.close();
        second.close();
        closeGate(firstGate);
        closeGate(secondGate);
    }

    private static void staleCloseCannotRetireReplacementRoute()
    {
        PluginOwner firstOwner = new PluginOwner("film-close", 1L);
        PluginOwner secondOwner = new PluginOwner("film-close", 2L);
        PluginGenerationGate firstGate = new PluginGenerationGate(firstOwner);
        PluginGenerationGate secondGate = new PluginGenerationGate(secondOwner);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();

        firstGate.activate();
        secondGate.activate();

        BBSFilmCollaborationSubscription first = BBSFilmCollaborationRegistry.subscribe(
            firstOwner,
            firstGate.fence(),
            listener(firstCalls)
        );
        BBSFilmCollaborationSubscription second = BBSFilmCollaborationRegistry.subscribe(
            secondOwner,
            secondGate.fence(),
            listener(secondCalls)
        );

        first.close();
        BBSFilmCollaborationRegistry.publishLocalMutations(null);

        check(firstCalls.get() == 0, "closed incumbent still received callbacks");
        check(secondCalls.get() == 1, "incumbent close removed the replacement route");
        check(second.active(), "replacement subscription was retired by stale close");

        second.close();
        closeGate(firstGate);
        closeGate(secondGate);
    }

    private static void rejectedDuplicateCannotRemoveAcceptedRoute()
    {
        PluginOwner owner = new PluginOwner("film-exact-close", 1L);
        PluginGenerationGate gate = new PluginGenerationGate(owner);
        AtomicInteger acceptedCalls = new AtomicInteger();
        AtomicInteger rejectedCalls = new AtomicInteger();
        BBSFilmCollaborationSubscription accepted = BBSFilmCollaborationRegistry.subscribe(
            owner,
            gate.fence(),
            listener(acceptedCalls)
        );
        BBSFilmCollaborationSubscription rejected = BBSFilmCollaborationRegistry.subscribe(
            owner,
            gate.fence(),
            listener(rejectedCalls)
        );

        check(accepted.registration().accepted(), "first exact-owner registration failed");
        check(rejected.registration().status() == BBSRegistrationStatus.DUPLICATE,
            "duplicate exact-owner registration was accepted");

        gate.activate();
        rejected.close();
        BBSFilmCollaborationRegistry.publishLocalMutations(null);

        check(acceptedCalls.get() == 1,
            "closing a rejected duplicate removed the accepted subscription");
        check(rejectedCalls.get() == 0, "rejected duplicate received a callback");

        accepted.close();
        closeGate(gate);
    }

    private static BBSFilmCollaborationListener listener(AtomicInteger calls)
    {
        return new BBSFilmCollaborationListener()
        {
            @Override
            public void onLocalMutations(mchorse.bbs_mod.api.client.film.BBSFilmMutationBatch batch)
            {
                calls.incrementAndGet();
            }
        };
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void closeGate(PluginGenerationGate gate)
    {
        gate.beginDrain();
        gate.close();
    }
}
