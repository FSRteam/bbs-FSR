package mchorse.bbs_mod.utils.manager;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.manager.storage.CompressedDataStorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Executable regression checks for repository path containment. */
public final class FolderManagerContainmentTest
{
    private FolderManagerContainmentTest()
    {}

    public static void main(String[] args) throws Exception
    {
        Path sandbox = Files.createTempDirectory("bbs-folder-manager-");

        try
        {
            Path root = sandbox.resolve("films");
            TestManager manager = new TestManager(root.toFile());

            assertLegitimateNestedPaths(manager, root);
            assertFileOperationsStayContained(manager, root, sandbox);
            assertFolderOperationsStayContained(manager, root, sandbox);
            assertAbsoluteTraversalAndRootEdgesRejected(manager, root, sandbox);
            assertSiblingPrefixEscapeRejected(manager, root, sandbox);
            assertExternalSymbolicLinkRejected(manager, root, sandbox);
            assertNestedBaseManagerSaveCreatesParents(sandbox);
        }
        finally
        {
            deleteRecursively(sandbox);
        }

        System.out.println("FolderManagerContainmentTest: all tests passed");
    }

    private static void assertNestedBaseManagerSaveCreatesParents(Path sandbox) throws Exception
    {
        Path root = sandbox.resolve("persistent-films");
        PersistentManager manager = new PersistentManager(root.toFile());
        MapType data = new MapType();
        data.putString("marker", "saved");

        check(manager.save("sequences/shot", data), "nested Film save was rejected");
        check(Files.isRegularFile(root.resolve("sequences/shot.dat")),
            "nested Film save did not create the parent directory");
        check(manager.load("sequences/shot") != null, "nested Film save could not be loaded");
    }

    private static void assertLegitimateNestedPaths(TestManager manager, Path root) throws Exception
    {
        assertEquals(
            root.resolve("sequences/intro.dat").toFile().getCanonicalFile(),
            manager.getFile("sequences/intro")
        );
        assertEquals(
            root.resolve("sequences/scenes").toFile().getCanonicalFile(),
            manager.getFolder("sequences/scenes/")
        );
        assertEquals(
            root.resolve("intro.dat.dat").toFile().getCanonicalFile(),
            manager.getFile("intro.dat")
        );
    }

    private static void assertFileOperationsStayContained(TestManager manager, Path root, Path sandbox) throws Exception
    {
        File source = manager.getFile("sequences/shot");

        check(source != null, "legitimate nested file was rejected");
        Files.createDirectories(source.toPath().getParent());
        Files.writeString(source.toPath(), "film");

        check(manager.exists("sequences/shot"), "nested file was not found");
        check(!manager.rename("sequences/shot", "../outside"), "rename escaped the repository");
        check(source.isFile(), "failed escaped rename changed the source file");
        check(manager.rename("sequences/shot", "sequences/renamed"), "legitimate nested rename failed");
        check(manager.delete("sequences/renamed"), "legitimate nested delete failed");

        Path outside = sandbox.resolve("outside.dat");

        Files.writeString(outside, "keep");
        check(!manager.exists("../outside"), "escaped file was reported as a repository entry");
        check(!manager.delete("../outside"), "delete escaped the repository");
        check(Files.readString(outside).equals("keep"), "escaped operation changed an outside file");

        File listed = manager.getFile("catalog/example");

        check(listed != null, "contained listing fixture was rejected");
        Files.createDirectories(listed.toPath().getParent());
        Files.writeString(listed.toPath(), "film");
        check(manager.getKeys().contains("catalog/example"), "contained nested film was not listed");
        check(listed.toPath().startsWith(root.toFile().getCanonicalFile().toPath()), "test fixture left repository root");
    }

    private static void assertFolderOperationsStayContained(TestManager manager, Path root, Path sandbox) throws Exception
    {
        check(manager.addFolder("groups/source/"), "legitimate nested folder creation failed");
        check(manager.addFolder("groups/target"), "legitimate rename parent creation failed");
        check(manager.renameFolder("groups/source", "groups/target/source/"), "legitimate nested folder rename failed");
        check(manager.deleteFolder("groups/target/source"), "legitimate empty folder delete failed");

        Path outsideFolder = sandbox.resolve("outside-folder");

        Files.createDirectories(outsideFolder);
        check(!manager.addFolder("../new-outside-folder"), "folder creation escaped the repository");
        check(!manager.renameFolder("groups/target", "../renamed-outside-folder"), "folder rename escaped the repository");
        check(!manager.deleteFolder("../outside-folder"), "folder delete escaped the repository");
        check(Files.isDirectory(outsideFolder), "escaped operation changed an outside folder");
        check(Files.isDirectory(root), "root-edge operation removed the repository");
    }

    private static void assertAbsoluteTraversalAndRootEdgesRejected(TestManager manager, Path root, Path sandbox)
    {
        String absolute = sandbox.resolve("absolute").toAbsolutePath().toString();
        String[] rejected = {
            "",
            " ",
            ".",
            "..",
            "../escape",
            "nested/../escape",
            "nested/./escape",
            absolute,
            "/absolute/escape",
            "C:\\absolute\\escape",
            "C:drive-relative-escape",
            "\\\\server\\share\\escape"
        };

        for (String value : rejected)
        {
            check(manager.getFile(value) == null, "file path was accepted: " + value);
            check(manager.getFolder(value) == null, "folder path was accepted: " + value);
            check(!manager.exists(value), "invalid path was reported as existing: " + value);
            check(!manager.delete(value), "invalid file delete was accepted: " + value);
            check(!manager.addFolder(value), "invalid folder creation was accepted: " + value);
            check(!manager.deleteFolder(value), "invalid folder delete was accepted: " + value);
        }

        check(manager.getFile("nested/") == null, "folder-shaped id was accepted as a film file");
        check(manager.getFile(null) == null, "null file id was accepted");
        check(manager.getFolder(null) == null, "null folder path was accepted");
        check(!manager.rename(null, "safe"), "null rename source was accepted");
        check(!manager.rename("safe", null), "null rename destination was accepted");
        check(!manager.renameFolder(null, "safe"), "null folder rename source was accepted");
        check(!manager.renameFolder("safe", null), "null folder rename destination was accepted");
        check(Files.isDirectory(root), "root-edge rejection removed the repository root");
    }

    private static void assertSiblingPrefixEscapeRejected(TestManager manager, Path root, Path sandbox) throws Exception
    {
        Path sibling = sandbox.resolve(root.getFileName() + "-archive");
        Path siblingFilm = sibling.resolve("secret.dat");
        String escape = "../" + sibling.getFileName() + "/secret";

        Files.createDirectories(sibling);
        Files.writeString(siblingFilm, "keep");

        check(manager.getFile(escape) == null, "same-prefix sibling file passed containment");
        check(manager.getFolder("../" + sibling.getFileName()) == null, "same-prefix sibling folder passed containment");
        check(!manager.delete(escape), "same-prefix sibling file was deleted");
        check(Files.readString(siblingFilm).equals("keep"), "same-prefix sibling file was changed");
    }

    private static void assertExternalSymbolicLinkRejected(TestManager manager, Path root, Path sandbox) throws Exception
    {
        Path external = sandbox.resolve("external-library");
        Path secret = external.resolve("secret.dat");
        Path link = root.resolve("linked");

        Files.createDirectories(external);
        Files.writeString(secret, "keep");

        try
        {
            Files.createSymbolicLink(link, external.toAbsolutePath());
        }
        catch (IOException | UnsupportedOperationException | SecurityException e)
        {
            return;
        }

        check(manager.getFile("linked/secret") == null, "external symbolic link passed file containment");
        check(manager.getFolder("linked") == null, "external symbolic link passed folder containment");
        check(!manager.delete("linked/secret"), "delete followed an external symbolic link");
        check(!manager.getKeys().contains("linked/secret"), "listing followed an external symbolic link");
        check(Files.readString(secret).equals("keep"), "external symbolic-link target was changed");
    }

    private static void assertEquals(Object expected, Object actual)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path root) throws IOException
    {
        if (!Files.exists(root))
        {
            return;
        }

        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class TestManager extends FolderManager<ValueGroup>
    {
        private TestManager(File root)
        {
            super(() -> root);
        }

        @Override
        public ValueGroup create(String id, MapType data)
        {
            return null;
        }

        @Override
        public ValueGroup load(String id)
        {
            return null;
        }

        @Override
        public boolean save(String name, MapType mapType)
        {
            return false;
        }

        @Override
        protected String getExtension()
        {
            return ".dat";
        }
    }

    private static final class PersistentManager extends BaseManager<ValueGroup>
    {
        private PersistentManager(File root)
        {
            super(() -> root);
            this.backUps = false;
            this.storage = new CompressedDataStorage();
        }

        @Override
        protected ValueGroup createData(String id, MapType mapType)
        {
            return new ValueGroup(id);
        }

        @Override
        protected String getExtension()
        {
            return ".dat";
        }
    }
}
