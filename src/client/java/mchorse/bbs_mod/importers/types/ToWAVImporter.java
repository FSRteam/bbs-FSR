package mchorse.bbs_mod.importers.types;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.audio.AudioImportPolicy;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.ImporterUtils;
import mchorse.bbs_mod.l10n.keys.IKey;

import java.io.File;

public class ToWAVImporter implements IImporter
{
    private final IKey name;
    private final String[] extensions;
    private final AudioImportPolicy policy;

    public ToWAVImporter(IKey name, String... extensions)
    {
        this(name, AudioImportPolicy.SOURCE, extensions);
    }

    public ToWAVImporter(IKey name, AudioImportPolicy policy, String... extensions)
    {
        this.name = name;
        this.extensions = extensions;
        this.policy = policy == null ? AudioImportPolicy.SOURCE : policy;
    }

    public AudioImportPolicy policy()
    {
        return this.policy;
    }

    @Override
    public IKey getName()
    {
        return this.name;
    }

    @Override
    public File getDefaultFolder()
    {
        return BBSMod.getAudioFolder();
    }

    @Override
    public boolean canImport(ImporterContext context)
    {
        return ImporterUtils.checkFileExtension(context.files, this.extensions);
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
        return AudioImporterSupport.importFiles(context, this.policy, false, true,
            context.getDestination(this));
    }
}
