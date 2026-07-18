package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable regression for revocation inside one replay action loop. */
public final class ReplayActionAuthorityTest
{
    private ReplayActionAuthorityTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ReplayActionAuthorityTest: OK");
    }

    public static void runAll()
    {
        ValueInt previousDuration = BBSSettings.duration;

        try
        {
            if (previousDuration == null)
            {
                BBSSettings.duration = new ValueInt("duration", 30, 1, 1000);
            }

            stopsBeforeTheSecondClipAfterRevocation();
            supplierFailureFailsClosed();
        }
        finally
        {
            BBSSettings.duration = previousDuration;
        }
    }

    private static void stopsBeforeTheSecondClipAfterRevocation()
    {
        CountingActionClip first = new CountingActionClip();
        CountingActionClip second = new CountingActionClip();
        AtomicInteger checks = new AtomicInteger();
        boolean continued = Replay.applyAuthorizedActions(
            List.of(first, second),
            () -> checks.incrementAndGet() == 1,
            (action) -> ((CountingActionClip) action).applications += 1
        );

        check(!continued, "revoked replay action loop reported successful completion");
        check(checks.get() == 2, "replay did not revalidate before every clip");
        check(first.applications == 1 && second.applications == 0,
            "a clip executed after the replay requester was revoked");
    }

    private static void supplierFailureFailsClosed()
    {
        CountingActionClip first = new CountingActionClip();
        CountingActionClip second = new CountingActionClip();
        boolean continued = Replay.applyAuthorizedActions(
            List.of(first, second),
            () -> { throw new IllegalStateException("revocation check failed"); },
            (action) -> ((CountingActionClip) action).applications += 1
        );

        check(!continued, "throwing authority supplier reported successful completion");
        check(first.applications == 0 && second.applications == 0,
            "throwing authority supplier allowed a replay clip to execute");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class CountingActionClip extends ActionClip
    {
        private int applications;

        @Override
        protected Clip create()
        {
            return new CountingActionClip();
        }
    }
}
