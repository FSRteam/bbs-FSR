package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Binary descriptor regressions for the transport-scoped client bridge. */
final class NetworkCompatClientDescriptorTest
{
    private NetworkCompatClientDescriptorTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("NetworkCompatClientDescriptorTest passed");
    }

    static void runAll()
    {
        try
        {
            Class<?> legacyReceiver = NetworkCompatClient.ClientReceiver.class;
            Method legacyReceive = legacyReceiver.getMethod("receive", FriendlyByteBuf.class);

            check(legacyReceive.getReturnType() == void.class,
                "legacy ClientReceiver SAM return type changed");
            NetworkCompatClient.class.getMethod(
                "registerClientReceiver",
                ResourceLocation.class,
                legacyReceiver
            );
            NetworkCompatClient.class.getMethod(
                "dispatchClientPayload",
                ResourceLocation.class,
                FriendlyByteBuf.class
            );

            Class<?> scopedReceiver = NetworkCompatClient.ScopedClientReceiver.class;
            Method scopedReceive = scopedReceiver.getMethod(
                "receive",
                FriendlyByteBuf.class,
                Connection.class,
                LocalPlayer.class
            );

            check(scopedReceive.getReturnType() == void.class,
                "scoped client receiver return type changed");
            NetworkCompatClient.class.getMethod(
                "registerCoreClientReceiver",
                ResourceLocation.class,
                scopedReceiver
            );
            NetworkCompatClient.class.getMethod(
                "dispatchClientPayload",
                ResourceLocation.class,
                FriendlyByteBuf.class,
                Connection.class,
                Player.class
            );
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("client network compatibility descriptor changed", e);
        }

        legacyUnscopedEntryFailsClosed();
    }

    @SuppressWarnings("deprecation")
    private static void legacyUnscopedEntryFailsClosed()
    {
        ResourceLocation channel = ResourceLocation.fromNamespaceAndPath("bbs", "c1");
        AtomicBoolean invoked = new AtomicBoolean();
        boolean rejected = false;

        try
        {
            NetworkCompatClient.registerClientReceiver(channel, (buf) -> invoked.set(true));
        }
        catch (IllegalStateException expected)
        {
            rejected = true;
        }

        check(rejected, "legacy unscoped receiver registration accepted a frozen core channel");

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        try
        {
            NetworkCompatClient.dispatchClientPayload(channel, buf);
        }
        finally
        {
            buf.release();
        }

        check(!invoked.get(), "legacy two-argument dispatch invoked an unscoped receiver");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
