package mchorse.bbs_mod.utils.net;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * File publication primitives for the download kernel: guarded recursive
 * deletion, atomic-enough directory moves and POSIX executability, all with
 * retry-on-Windows-file-lock behavior so transient readers (antivirus, file
 * explorers) cannot derail an install.
 */
public final class AtomicFiles
{
    private static final int FILE_OPERATION_ATTEMPTS = 5;
    private static final long FILE_OPERATION_RETRY_MS = 200L;

    private AtomicFiles()
    {}

    /** Deletes {@code target}, refusing anything outside {@code managedRoot}. */
    public static void deleteTree(Path managedRoot, Path target) throws IOException
    {
        retryAccessDenied(() -> deleteTreeOnce(managedRoot, target));
    }

    private static void deleteTreeOnce(Path managedRoot, Path target) throws IOException
    {
        Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();

        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot))
        {
            throw new IOException("Refusing to delete outside the managed directory");
        }

        if (!Files.exists(normalizedTarget))
        {
            return;
        }

        Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException
            {
                if (error != null)
                {
                    throw error;
                }

                Files.delete(directory);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void moveDirectory(Path source, Path target) throws IOException
    {
        try
        {
            moveWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            moveWithRetry(source, target);
        }
    }

    public static void move(Path source, Path target, StandardCopyOption... options) throws IOException
    {
        moveWithRetry(source, target, options);
    }

    private static void moveWithRetry(Path source, Path target, StandardCopyOption... options) throws IOException
    {
        retryAccessDenied(() -> Files.move(source, target, options));
    }

    public static void makeExecutable(Path executable) throws IOException
    {
        try
        {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(executable));

            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        }
        catch (UnsupportedOperationException e)
        {
            executable.toFile().setExecutable(true, false);
        }

        if (!Files.isExecutable(executable))
        {
            throw new IOException("Executable permission could not be set");
        }
    }

    private static void retryAccessDenied(FileOperation operation) throws IOException
    {
        AccessDeniedException denied = null;

        for (int attempt = 0; attempt < FILE_OPERATION_ATTEMPTS; attempt += 1)
        {
            try
            {
                operation.run();

                return;
            }
            catch (AccessDeniedException e)
            {
                denied = e;

                if (attempt + 1 < FILE_OPERATION_ATTEMPTS)
                {
                    pauseBeforeRetry(attempt, e);
                }
            }
        }

        throw denied;
    }

    private static void pauseBeforeRetry(int attempt, AccessDeniedException failure) throws IOException
    {
        try
        {
            Thread.sleep(FILE_OPERATION_RETRY_MS * (attempt + 1L));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            failure.addSuppressed(e);
            throw failure;
        }
    }

    @FunctionalInterface
    private interface FileOperation
    {
        void run() throws IOException;
    }
}
