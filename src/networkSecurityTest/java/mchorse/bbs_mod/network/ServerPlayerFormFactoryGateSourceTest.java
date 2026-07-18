package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for the s3 player-form factory boundary. */
final class ServerPlayerFormFactoryGateSourceTest
{
    private static final Path SOURCE = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );

    private ServerPlayerFormFactoryGateSourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ServerPlayerFormFactoryGateSourceTest passed");
    }

    static void runAll()
    {
        try
        {
            String source = Files.readString(findProjectRoot().resolve(SOURCE));
            int start = source.indexOf("private static void handlePlayerFormPacket");
            int end = source.indexOf("private static void handleManagerDataPacket", start);

            check(start >= 0 && end > start, "could not locate the s3 player-form handler");

            String handler = source.substring(start, end).replaceAll("\\s+", " ");

            assertOrdered(handler,
                "consumeCompletedPayload(player.getUUID(), player, SERVER_PLAYER_FORM_PACKET, bytes, false)",
                "packetByteBuf.isReadable()",
                "NetworkDataDecoder.decode(bytes)",
                "decoded instanceof MapType data",
                "server.execute(",
                "isCurrentConnection(server, player)",
                "PermissionUtils.arePanelsAllowed(server, player)",
                "BBSMod.getForms().fromData(data)",
                "FormUtils.copy(form)",
                "Morph.getMorph(player).setForm(copy)",
                "sendMorphToTracked(player, form)",
                "catch (RuntimeException | LinkageError e)");
            check(handler.indexOf("BBSMod.getForms().fromData(data)") > handler.indexOf("server.execute("),
                "s3 invoked a typed Form factory before the main-thread authority gate");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect s3 player-form wiring", e);
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        for (int i = 0; i < 8 && current != null; i += 1)
        {
            if (Files.isRegularFile(current.resolve(SOURCE)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate project root for " + SOURCE);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int index = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, index + 1);

            check(next >= 0, "missing s3 marker: " + marker);
            check(next > index, "s3 marker is out of order: " + marker);
            index = next;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
