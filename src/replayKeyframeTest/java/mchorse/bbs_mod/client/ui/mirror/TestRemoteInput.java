package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;

import java.lang.reflect.Field;

/** Test-only access to the mailbox lease; production callers must use the mailbox. */
public final class TestRemoteInput
{
    public static Runnable install(long session, BBSUiRemoteInputState state)
    {
        try
        {
            Field snapshot = BBSUiRemoteHeldState.class.getDeclaredField("snapshot");
            snapshot.setAccessible(true);
            Object previous = snapshot.get(null);
            long lease = BBSUiRemoteHeldState.install(session, state);

            return () ->
            {
                if (BBSUiRemoteHeldState.isLeaseCurrent(lease))
                {
                    try
                    {
                        snapshot.set(null, previous);
                    }
                    catch (IllegalAccessException exception)
                    {
                        throw new AssertionError("Could not restore the enclosing test input lease", exception);
                    }
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the scoped test input lease", exception);
        }
    }
}
