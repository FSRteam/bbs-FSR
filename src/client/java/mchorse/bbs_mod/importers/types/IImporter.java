package mchorse.bbs_mod.importers.types;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.l10n.keys.IKey;

import java.io.File;

public interface IImporter
{
    public IKey getName();

    public default File getDefaultFolder()
    {
        return BBSMod.getAssetsFolder();
    }

    public boolean canImport(ImporterContext context);

    public void importFiles(ImporterContext context);

    /**
     * Whether the importer needs the configured FFmpeg executable before it
     * can run.  This keeps source-preserving importers usable on machines
     * where FFmpeg is not installed while retaining the old preflight for
     * legacy importers.
     */
    public default boolean requiresFFmpeg()
    {
        return true;
    }

    /**
     * Additive result API for importers that can validate publication. Legacy
     * importers retain their void method, but an exception is still surfaced
     * as a failed outcome for callers such as the drop-to-import UI.
     */
    public default ImportOutcome importFilesOutcome(ImporterContext context)
    {
        if (context == null || context.files == null || context.files.isEmpty())
        {
            return ImportOutcome.failure(0, "No files were selected");
        }

        try
        {
            this.importFiles(context);
        }
        catch (Exception e)
        {
            String message = e.getMessage();

            return ImportOutcome.failure(0,
                message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
        }

        return ImportOutcome.success(context.files.size());
    }
}
