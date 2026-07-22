package mchorse.bbs_mod.importers.types;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.audio.AudioImportPolicy;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.ImporterUtils;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;

import java.io.File;

public class WAVImporter implements IImporter
{
    private final AudioImportPolicy policy;

    public WAVImporter()
    {
        this(AudioImportPolicy.SOURCE);
    }

    public WAVImporter(AudioImportPolicy policy)
    {
        this.policy = policy == null ? AudioImportPolicy.SOURCE : policy;
    }

    public AudioImportPolicy policy()
    {
        return this.policy;
    }

    @Override
    public boolean requiresFFmpeg()
    {
        return this.policy != AudioImportPolicy.SOURCE;
    }

    @Override
    public IKey getName()
    {
        return UIKeys.IMPORTER_WAV;
    }

    @Override
    public File getDefaultFolder()
    {
        return BBSMod.getAudioFolder();
    }

    @Override
    public boolean canImport(ImporterContext context)
    {
        return ImporterUtils.checkFileExtension(context.files, ".wav");
    }

    @Override
    public void importFiles(ImporterContext context)
    {
        ImportOutcome outcome = this.importFilesOutcome(context);
        if (!outcome.success())
        {
            throw new IllegalStateException(outcome.message());
        }
    }

    @Override
    public ImportOutcome importFilesOutcome(ImporterContext context)
    {
        return AudioImporterSupport.importFiles(context, this.policy, true, false,
            context.getDestination(this));
    }
}
