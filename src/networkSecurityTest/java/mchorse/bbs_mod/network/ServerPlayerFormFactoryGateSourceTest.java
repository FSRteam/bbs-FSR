package mchorse.bbs_mod.network;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.resources.Link;

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
        testFormArchitectIdentityContract();

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
                "!data.isEmpty() && !BBSMod.getForms().has(data)",
                "reason=unknown_form",
                "data.isEmpty() ? null : BBSMod.getForms().fromData(data)",
                "FormUtils.copy(form)",
                "Morph.getMorph(player).setForm(copy)",
                "sendMorphToTracked(player, form)",
                "catch (RuntimeException | LinkageError e)");
            check(handler.indexOf("BBSMod.getForms().fromData(data)") > handler.indexOf("server.execute("),
                "s3 invoked a typed Form factory before the main-thread authority gate");
            check(!handler.contains("data.has(\"type\")"),
                "s3 hard-coded the generic factory key instead of using FormArchitect's id contract");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect s3 player-form wiring", e);
        }
    }

    private static void testFormArchitectIdentityContract()
    {
        FormArchitect forms = new FormArchitect();
        MapType valid = new MapType();
        MapType genericFactoryKey = new MapType();

        forms.register(Link.bbs("label"), LabelForm.class, null);
        valid.putString(forms.getTypeKey(), "bbs:label");
        genericFactoryKey.putString("type", "bbs:label");

        check("id".equals(forms.getTypeKey()), "FormArchitect no longer serializes form identity under id");
        check(forms.has(valid), "FormArchitect rejected a registered form id");
        check(!forms.has(new MapType()), "an empty demorph map was accepted as a concrete form");
        check(!forms.has(genericFactoryKey), "the generic type key was accepted as a form id");
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
