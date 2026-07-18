package mchorse.bbs_mod.client.film.collaboration;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.util.Objects;

/** Applies validated data and restores one complete value tree on failure. */
final class BBSFilmAtomicApply
{
    private BBSFilmAtomicApply()
    {}

    static Result run(BaseValue root, BaseType backup, Runnable apply)
    {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(apply, "apply");

        try
        {
            apply.run();
            return new Result(true, false, null, null);
        }
        catch (Exception | LinkageError applyFailure)
        {
            try
            {
                root.fromData(backup);
                return new Result(false, true, applyFailure, null);
            }
            catch (Exception | LinkageError restoreFailure)
            {
                return new Result(false, false, applyFailure, restoreFailure);
            }
        }
    }

    record Result(boolean applied, boolean restored, Throwable applyFailure, Throwable restoreFailure)
    {}
}
