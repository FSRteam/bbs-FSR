package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BBSAddonExecutionBoundary
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-addon-api2");

    private BBSAddonExecutionBoundary() {}

    static boolean run(BBSAddonRecord record, BBSAddonPhase phase, ThrowingRunnable action)
    {
        try
        {
            action.run();
            return true;
        }
        catch (Exception | LinkageError e)
        {
            record.diagnostics.fail(phase, e);
            LOGGER.error("[bbs-addon-api2] addon '{}' failed during {}", record.descriptor.addonId(), phase, e);

            return false;
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
