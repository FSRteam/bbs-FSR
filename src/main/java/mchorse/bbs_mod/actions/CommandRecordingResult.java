package mchorse.bbs_mod.actions;

/** Records one successful result for a top-level player command. */
public final class CommandRecordingResult
{
    private boolean recorded;

    /**
     * A forked command can report several leaf results, but the film stores the
     * original top-level command only once. Recording faults must not turn an
     * otherwise successful Minecraft command into a failed command.
     */
    public boolean tryRecord(boolean successful, Runnable recorder)
    {
        if (!successful || this.recorded || recorder == null)
        {
            return false;
        }

        this.recorded = true;

        try
        {
            recorder.run();
        }
        catch (RuntimeException | LinkageError ignored)
        {}

        return true;
    }
}
