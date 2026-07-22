package mchorse.bbs_mod.network.compat;

import mchorse.bbs_mod.api.network.BBSAddonClientNetworkReceiver;
import mchorse.bbs_mod.plugin.runtime.ActivePluginGeneration;
import mchorse.bbs_mod.plugin.runtime.ActivePluginIndex;
import mchorse.bbs_mod.plugin.runtime.PluginLease;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable coverage for host-owned hot broker routes and generation fencing. */
public final class AddonPayloadBrokerHotRouteTest
{
    private AddonPayloadBrokerHotRouteTest()
    {}

    public static void main(String[] args) throws Exception
    {
        queuedPayloadCannotCrossGenerationSwap();
        invalidNamespaceIsRejectedBeforeAHostRouteIsCreated();

        System.out.println("AddonPayloadBrokerHotRouteTest: all tests passed");
    }

    private static void queuedPayloadCannotCrossGenerationSwap() throws Exception
    {
        String pluginId = "broker-route-test";
        ResourceLocation messageId = ResourceLocation.fromNamespaceAndPath(pluginId, "message");
        ActivePluginIndex<AddonPayloadBroker.HotReceiverSnapshot> index = new ActivePluginIndex<>();
        PluginOwner firstOwner = new PluginOwner(pluginId, 1L);
        PluginOwner secondOwner = new PluginOwner(pluginId, 2L);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();

        AddonPayloadBroker.HotReceiverSnapshot.Builder firstBuilder =
            AddonPayloadBroker.stageHotReceivers(firstOwner, index);
        PluginLease firstRegistration = firstBuilder.registerClientReceiver(
            messageId,
            receiver(firstCalls)
        );
        ActivePluginGeneration<AddonPayloadBroker.HotReceiverSnapshot> first =
            new ActivePluginGeneration<>(firstOwner, firstBuilder.build());
        index.replace(first);

        Object connection = new Object();
        List<Runnable> queued = new ArrayList<>();
        AddonPayloadBroker.handleClientPayload(
            frame(messageId, new byte[] {1}),
            connection,
            queued::add
        );
        check(queued.size() == 1, "active v1 route did not queue a client delivery");

        AddonPayloadBroker.HotReceiverSnapshot.Builder secondBuilder =
            AddonPayloadBroker.stageHotReceivers(secondOwner, index);
        PluginLease secondRegistration = secondBuilder.registerClientReceiver(
            messageId,
            receiver(secondCalls)
        );
        ActivePluginGeneration<AddonPayloadBroker.HotReceiverSnapshot> second =
            new ActivePluginGeneration<>(secondOwner, secondBuilder.build());
        check(index.replace(firstOwner, second) == first, "v2 did not atomically replace v1");

        /* The callback was queued while v1 was selected. It must not be
         * retargeted to v2, nor invoke a generation already draining. */
        queued.remove(0).run();
        check(firstCalls.get() == 0 && secondCalls.get() == 0,
            "queued v1 payload crossed the generation fence");

        AddonPayloadBroker.handleClientPayload(
            frame(messageId, new byte[] {2}),
            connection,
            queued::add
        );
        check(queued.size() == 1, "active v2 route did not queue a fresh delivery");
        queued.remove(0).run();
        check(secondCalls.get() == 1 && firstCalls.get() == 0,
            "fresh payload did not reach only the active v2 receiver");

        check(AddonPayloadBroker.clearHotOwner(secondOwner) == 1,
            "owner clear did not remove the v2 route claim");
        check(AddonPayloadBroker.clearHotOwner(secondOwner) == 0,
            "owner clear was not idempotent");

        queued.clear();
        AddonPayloadBroker.handleClientPayload(frame(messageId, new byte[] {3}), connection, queued::add);
        check(queued.isEmpty(), "cleared owner still exposed a hot broker route");

        secondRegistration.close();
        secondRegistration.close();
        firstRegistration.close();
        firstBuilder.close();
        secondBuilder.close();

        ActivePluginGeneration<AddonPayloadBroker.HotReceiverSnapshot> removed = index.remove(pluginId, secondOwner);
        check(removed == second, "owner clear unexpectedly changed the active index");
        check(removed.awaitDrained(Duration.ofSeconds(1L)), "v2 did not drain after route teardown");
        removed.retire();
        check(first.awaitDrained(Duration.ofSeconds(1L)), "v1 did not drain after replacement");
        first.retire();
        AddonPayloadBroker.clearClientConnection(connection);
        index.close();
    }

    private static void invalidNamespaceIsRejectedBeforeAHostRouteIsCreated()
    {
        PluginOwner owner = new PluginOwner("namespace-test", 1L);
        ActivePluginIndex<AddonPayloadBroker.HotReceiverSnapshot> index = new ActivePluginIndex<>();
        AddonPayloadBroker.HotReceiverSnapshot.Builder builder =
            AddonPayloadBroker.stageHotReceivers(owner, index);

        try
        {
            builder.registerClientReceiver(
                ResourceLocation.fromNamespaceAndPath("other", "message"),
                receiver(new AtomicInteger())
            );
            throw new AssertionError("hot broker accepted a receiver outside the plugin namespace");
        }
        catch (IllegalArgumentException expected)
        {}
        finally
        {
            builder.close();
            index.close();
        }
    }

    private static BBSAddonClientNetworkReceiver receiver(AtomicInteger calls)
    {
        return (id, payload) -> calls.incrementAndGet();
    }

    private static FriendlyByteBuf frame(ResourceLocation id, byte[] payload)
    {
        FriendlyByteBuf frame = NetworkCompat.createBuffer();
        frame.writeUtf(id.toString());
        frame.writeInt(payload.length);
        frame.writeBytes(payload);
        return frame;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
