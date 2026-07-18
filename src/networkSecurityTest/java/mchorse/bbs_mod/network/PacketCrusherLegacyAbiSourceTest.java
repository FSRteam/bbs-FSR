package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for the fail-closed legacy PacketCrusher ABI. */
final class PacketCrusherLegacyAbiSourceTest
{
    private static final Path SOURCE = Path.of(
        "src/main/java/mchorse/bbs_mod/network/PacketCrusher.java"
    );

    private PacketCrusherLegacyAbiSourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("PacketCrusherLegacyAbiSourceTest passed");
    }

    static void runAll()
    {
        try
        {
            String source = compact(Files.readString(findProjectRoot().resolve(SOURCE)));
            String signature = "public void receive(FriendlyByteBuf buf, IBufferReceiver receiver)";
            int receiveStart = source.indexOf(signature);
            int receiveEnd = source.indexOf(
                "public void receive(UUID owner, ResourceLocation channel, FriendlyByteBuf buf, IBufferReceiver receiver)",
                receiveStart
            );

            check(source.contains("public PacketCrusher()"),
                "PacketCrusher public no-arg constructor descriptor was removed");
            check(receiveStart >= 0 && receiveEnd > receiveStart,
                "PacketCrusher legacy public receive descriptor was removed");

            String legacy = source.substring(receiveStart, receiveEnd);

            check(legacy.contains("reason=legacy_unscoped_receiver"),
                "legacy PacketCrusher receive no longer reports its fail-closed contract");
            check(!legacy.contains("receiver.receiveBuffer")
                    && !legacy.contains("buf.read")
                    && !legacy.contains("this.receive("),
                "legacy PacketCrusher receive can consume or reassemble unscoped data");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect PacketCrusher legacy ABI", e);
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(SOURCE)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(SOURCE)))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
