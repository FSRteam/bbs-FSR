package mchorse.bbs_mod.utils.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hardened ZIP entry extraction for the download kernel: entries are
 * normalized and resolved against the staging directory so an archive entry
 * cannot escape it (zip-slip), and exactly one copy of the wanted file must
 * exist for the extraction to succeed.
 */
public final class ArchiveExtractor
{
    private ArchiveExtractor()
    {}

    /**
     * Extracts a single entry (matched case-insensitively by file name) from
     * the archive at {@code archive} into {@code staging}/{@code entryName}.
     */
    public static void extractEntry(Path archive, Path staging, String entryName) throws IOException
    {
        Path normalizedStaging = staging.toAbsolutePath().normalize();
        boolean found = false;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive)))
        {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null)
            {
                Path relative;

                try
                {
                    relative = Path.of(entry.getName().replace('\\', '/')).normalize();
                }
                catch (InvalidPathException e)
                {
                    throw new IOException("Invalid ZIP entry: " + entry.getName(), e);
                }

                Path resolved = normalizedStaging.resolve(relative).normalize();

                if (relative.isAbsolute() || !resolved.startsWith(normalizedStaging))
                {
                    throw new IOException("ZIP entry escapes the installation directory");
                }

                if (!entry.isDirectory() && relative.getFileName() != null
                    && relative.getFileName().toString().equalsIgnoreCase(entryName))
                {
                    if (found)
                    {
                        throw new IOException("ZIP contains multiple copies of " + entryName);
                    }

                    Files.copy(zip, staging.resolve(entryName), StandardCopyOption.REPLACE_EXISTING);
                    found = true;
                }

                zip.closeEntry();
            }
        }

        if (!found)
        {
            throw new IOException("ZIP does not contain " + entryName);
        }
    }
}
