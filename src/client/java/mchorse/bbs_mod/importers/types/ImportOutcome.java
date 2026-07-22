package mchorse.bbs_mod.importers.types;

/** Outcome reported by importers that can validate and publish their output. */
public record ImportOutcome(boolean success, int imported, String message)
{
    public static ImportOutcome success(int imported)
    {
        if (imported <= 0)
        {
            return failure(0, "No files were imported");
        }

        return new ImportOutcome(true, imported, null);
    }

    public static ImportOutcome failure(int imported, String message)
    {
        return new ImportOutcome(false, imported,
            message == null || message.isBlank() ? "Importer failed" : message);
    }
}
