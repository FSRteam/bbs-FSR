package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for the shared-form target trust boundary. */
final class ServerSharedFormAuthoritySourceTest
{
    private static final Path SOURCE = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ServerSharedFormAuthoritySourceTest passed");
    }

    static void runAll()
    {
        try
        {
            String source = Files.readString(findProjectRoot().resolve(SOURCE));
            int start = source.indexOf("private static void handleSharedFormPacket");
            int end = source.indexOf("private static void handleZoomPacket", start);

            check(start >= 0 && end > start, "could not locate shared-form handler");

            String handler = source.substring(start, end);

            check(handler.contains("isCurrentConnection(server, player)"),
                "queued shared-form delivery does not revalidate the sender connection");
            check(handler.contains("otherPlayer.serverLevel() == player.serverLevel()"),
                "shared-form delivery crosses dimensions");
            check(handler.contains("player.distanceToSqr(otherPlayer) <= MAX_SHARED_FORM_DISTANCE_SQR"),
                "shared-form delivery has no bounded distance check");
            check(handler.contains("PermissionUtils.arePanelsAllowed(server, otherPlayer)"),
                "shared-form delivery does not revalidate the target permission");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect shared-form authority wiring", e);
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

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
