package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;

/** Identity-held damage snapshots release only the playback that acquired them. */
public final class DamageControlOwnershipTest
{
    private DamageControlOwnershipTest()
    {}

    public static void runAll()
    {
        ValueBoolean original = BBSSettings.damageControl;
        DamageControl control;

        try
        {
            BBSSettings.damageControl = new ValueBoolean("damage_control", true);
            control = new DamageControl(null);
        }
        finally
        {
            BBSSettings.damageControl = original;
        }

        control.enable = true;
        Object first = new Object();
        Object second = new Object();

        control.acquire(first);
        control.acquire(second);
        check(control.nested == 1, "Two playback owners did not retain a nested damage hold");
        check(!control.release(first) && control.nested == 0, "Releasing one owner restored a shared snapshot");
        check(!control.release(first), "A repeated release consumed another owner's hold");
        check(control.release(second), "The final playback owner did not release the snapshot");

        control.acquire(null);
        control.acquire(null);
        check(!control.release(null) && control.nested == 0, "Manual damage holds did not nest");
        check(control.release(null), "The final manual damage hold did not release");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
