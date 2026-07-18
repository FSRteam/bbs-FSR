package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for the s1 model-block transaction boundary. */
final class ServerModelBlockMutationSourceTest
{
    private static final Path SERVER_NETWORK = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );
    private static final Path MODEL_BLOCK_ENTITY = Path.of(
        "src/main/java/mchorse/bbs_mod/blocks/entities/ModelBlockEntity.java"
    );

    private ServerModelBlockMutationSourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ServerModelBlockMutationSourceTest passed");
    }

    static void runAll()
    {
        try
        {
            Path root = findProjectRoot();

            checkHandlerFailureBoundary(Files.readString(root.resolve(SERVER_NETWORK)));
            checkEntityTransaction(Files.readString(root.resolve(MODEL_BLOCK_ENTITY)));
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect the s1 model-block transaction", e);
        }
    }

    private static void checkHandlerFailureBoundary(String source)
    {
        int start = source.indexOf("private static void handleModelBlockFormPacket");
        int end = source.indexOf("private static void handleModelBlockTransformsPacket", start);

        check(start >= 0 && end > start, "could not locate the s1 handler");

        String handler = compact(source.substring(start, end));

        assertOrdered("s1 handler", handler,
            "server.execute(",
            "isCurrentConnection(server, player)",
            "canEditModelBlock(server, player, pos)",
            "try { modelBlock.updateForm(data, world)",
            "catch (RuntimeException | LinkageError e)",
            "reason=mutation_failed",
            "return;",
            "mutationSessions.refreshModelBlockSession(");
    }

    private static void checkEntityTransaction(String source)
    {
        int start = source.indexOf("public void updateForm(MapType data, Level world)");

        check(start >= 0, "could not locate ModelBlockEntity.updateForm");

        String mutation = compact(source.substring(start));

        check(!mutation.contains("this.properties.fromData(data)"),
            "s1 still decodes directly into the live model-block properties");
        assertOrdered("model-block transaction", mutation,
            "ModelProperties replacement = new ModelProperties()",
            "replacement.fromData(data)",
            "BlockState blockState = world.getBlockState(pos)",
            "ModelProperties previous = this.properties",
            "try { this.properties = replacement",
            "this.setChanged()",
            "world.sendBlockUpdated(",
            "catch (RuntimeException | LinkageError e)",
            "this.properties = previous",
            "throw e;");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ");
    }

    private static void assertOrdered(String scope, String source, String... markers)
    {
        int index = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, index + 1);

            check(next >= 0, "missing " + scope + " marker: " + marker);
            check(next > index, scope + " marker is out of order: " + marker);
            index = next;
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        for (int i = 0; i < 8 && current != null; i += 1)
        {
            if (Files.isRegularFile(current.resolve(SERVER_NETWORK))
                && Files.isRegularFile(current.resolve(MODEL_BLOCK_ENTITY)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the project root");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
