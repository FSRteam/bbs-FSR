package mchorse.bbs_mod.api.client;

import mchorse.bbs_mod.client.compat.ClientApiCompat;
import mchorse.bbs_mod.client.compat.BBSWorldRenderContext;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.film.BBSFilmApplyResult;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationListener;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationSubscription;
import mchorse.bbs_mod.api.client.film.BBSFilmMutationBatch;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceClearRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceResult;
import mchorse.bbs_mod.api.client.film.BBSFilmRemotePresence;
import mchorse.bbs_mod.api.client.film.BBSFilmServerSequenceObserveRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotApplyRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotResult;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceListener;
import mchorse.bbs_mod.api.client.ui.BBSUiInputBatch;
import mchorse.bbs_mod.api.client.ui.BBSUiInputResult;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorListener;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorSubscription;
import mchorse.bbs_mod.api.client.ui.BBSUiOpenResult;
import mchorse.bbs_mod.api.network.BBSAddonClientNetworkReceiver;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.client.ui.mirror.BBSUiInputDispatcher;
import mchorse.bbs_mod.client.ui.mirror.BBSUiMirrorRegistry;
import mchorse.bbs_mod.client.ui.mirror.BBSUiOpenDispatcher;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRegistry;
import mchorse.bbs_mod.client.film.collaboration.BBSFilmCollaborationRegistry;
import mchorse.bbs_mod.network.compat.AddonPayloadBroker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Client-only Addon/API 2.0 facade.
 */
public final class BBSClientApi
{
    private BBSClientApi() {}

    public static KeyMapping registerKeyBinding(KeyMapping keyBinding)
    {
        return ClientApiCompat.registerKeyBinding(keyBinding);
    }

    /**
     * Descriptor-aware structural registration with Addon/API diagnostics.
     * Call during the addon client bootstrap before NeoForge closes the key
     * mapping registration event.
     */
    public static KeyMapping registerKeyBinding(BBSAddonDescriptor descriptor, KeyMapping keyBinding)
    {
        return ClientApiCompat.registerKeyBinding(descriptor, keyBinding);
    }

    public static void onAfterEntities(WorldRenderHandler handler)
    {
        ClientApiCompat.onAfterEntities(handler::render);
    }

    public static void onAfterLevel(WorldRenderHandler handler)
    {
        ClientApiCompat.onLast(handler::render);
    }

    public static void onHudRender(HudRenderHandler handler)
    {
        ClientApiCompat.onHudRender(handler::render);
    }

    public static void onClientStarted(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onClientStarted(handler);
    }

    public static void onClientStopping(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onClientStopping(handler);
    }

    public static void onDisconnect(ClientApiCompat.DisconnectHandler handler)
    {
        ClientApiCompat.onDisconnect(handler);
    }

    public static void onStartClientTick(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onStartClientTick(handler);
    }

    public static void onEndWorldTick(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onEndWorldTick(handler);
    }

    public static void onEndClientTick(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onEndClientTick(handler);
    }

    /**
     * Register an observer for immutable draw-command snapshots of the active
     * BBS UIScreen. This compatibility facade remains permanently active;
     * addons with runtime viewers should use {@link #subscribeUiMirror}.
     * Callbacks are isolated on a bounded serial worker per listener.
     */
    public static BBSRegistrationResult registerUiMirror(BBSAddonDescriptor descriptor, BBSUiMirrorListener listener)
    {
        return BBSUiMirrorRegistry.register(descriptor, listener);
    }

    /**
     * Register an initially inactive UI mirror listener with explicit runtime
     * viewer demand. Session lifecycle is tracked while inactive, but frames
     * and assets are captured only after the subscription is activated.
     */
    public static BBSUiMirrorSubscription subscribeUiMirror(
        BBSAddonDescriptor descriptor,
        BBSUiMirrorListener listener
    )
    {
        return BBSUiMirrorRegistry.subscribe(descriptor, listener);
    }

    /**
     * Submit browser input from any thread. The batch is applied on the
     * Minecraft client thread and the returned future completes there.
     */
    public static CompletableFuture<BBSUiInputResult> submitUiInput(BBSAddonDescriptor descriptor, BBSUiInputBatch batch)
    {
        return BBSUiInputDispatcher.submit(descriptor, batch);
    }

    /**
     * Request the fixed native BBS Dashboard from any thread. An accepted
     * mailbox request is evaluated and completed on the Minecraft client
     * thread; admission rejection may return an already-completed future.
     */
    public static CompletableFuture<BBSUiOpenResult> requestDashboardOpen(BBSAddonDescriptor descriptor)
    {
        return BBSUiOpenDispatcher.requestDashboardOpen(descriptor);
    }

    /**
     * Release held browser input owned by this addon.
     */
    public static void clearUiInput(BBSAddonDescriptor descriptor)
    {
        BBSUiInputDispatcher.clear(descriptor);
    }

    /**
     * Register a CLIENT_RENDER listener for asynchronously encoded Film/Replay
     * raster surfaces. Registration is inert while the listener reports no
     * viewer demand; callbacks run on the dedicated JPEG encoder thread.
     */
    public static BBSRegistrationResult registerRenderSurface(BBSAddonDescriptor descriptor, BBSRenderSurfaceListener listener)
    {
        return BBSRenderSurfaceRegistry.register(descriptor, listener);
    }

    /**
     * Subscribe to semantic changes committed by the active native Film editor.
     * The returned subscription owns registration lifetime and is safe to close
     * repeatedly. This additive API reuses the CLIENT_UI capability.
     */
    public static BBSFilmCollaborationSubscription registerFilmCollaboration(
        BBSAddonDescriptor descriptor,
        BBSFilmCollaborationListener listener
    )
    {
        return BBSFilmCollaborationRegistry.register(descriptor, listener);
    }

    /** Capture the current Film as bounded encoded BBS data on the client thread. */
    public static CompletableFuture<BBSFilmSnapshotResult> requestFilmSnapshot(BBSAddonDescriptor descriptor, long sessionId)
    {
        return BBSFilmCollaborationRegistry.requestSnapshot(descriptor, sessionId);
    }

    /** Apply one server-ordered semantic batch to the existing Film instance. */
    public static CompletableFuture<BBSFilmApplyResult> applyRemoteFilmMutations(
        BBSAddonDescriptor descriptor,
        BBSFilmMutationBatch batch
    )
    {
        return BBSFilmCollaborationRegistry.applyMutations(descriptor, batch);
    }

    /** Apply a server-approved recovery snapshot without replacing the Film object. */
    public static CompletableFuture<BBSFilmApplyResult> applyRemoteFilmSnapshot(
        BBSAddonDescriptor descriptor,
        BBSFilmSnapshotApplyRequest request
    )
    {
        return BBSFilmCollaborationRegistry.applySnapshot(descriptor, request);
    }

    /**
     * Advance this addon's continuous, server-ordered semantic watermark for
     * an event that intentionally requires no Film mutation, such as this
     * client's own broadcast.
     */
    public static CompletableFuture<BBSFilmApplyResult> observeFilmServerSequence(
        BBSAddonDescriptor descriptor,
        BBSFilmServerSequenceObserveRequest request
    )
    {
        return BBSFilmCollaborationRegistry.observeServerSequence(descriptor, request);
    }

    /** Inject one revision-scoped remote participant into the native/web-visible overlay. */
    public static CompletableFuture<BBSFilmPresenceResult> applyRemoteFilmPresence(
        BBSAddonDescriptor descriptor,
        BBSFilmRemotePresence presence
    )
    {
        return BBSFilmCollaborationRegistry.applyPresence(descriptor, presence);
    }

    /** Remove one remote participant using the same gap-checked server order. */
    public static CompletableFuture<BBSFilmPresenceResult> clearRemoteFilmPresence(
        BBSAddonDescriptor descriptor,
        BBSFilmPresenceClearRequest request
    )
    {
        return BBSFilmCollaborationRegistry.clearPresence(descriptor, request);
    }

    /** Clear every remote presence and tombstone owned by this addon for one local Film session. */
    public static CompletableFuture<BBSFilmPresenceResult> clearAllRemoteFilmPresence(
        BBSAddonDescriptor descriptor,
        long sessionId
    )
    {
        return BBSFilmCollaborationRegistry.clearAllPresence(descriptor, sessionId);
    }

    public static BBSRegistrationResult registerNetworkReceiver(
        BBSAddonDescriptor descriptor,
        ResourceLocation id,
        BBSAddonClientNetworkReceiver receiver
    )
    {
        return AddonPayloadBroker.registerClientReceiver(descriptor, id, receiver);
    }

    public static FriendlyByteBuf createNetworkBuffer()
    {
        return AddonPayloadBroker.createBuffer();
    }

    public static boolean sendNetworkToServer(BBSAddonDescriptor descriptor, ResourceLocation id, FriendlyByteBuf payload)
    {
        return AddonPayloadBroker.sendToServer(descriptor, id, payload);
    }

    public static <T extends Entity> void registerEntityRenderer(EntityType<T> type, EntityRendererProvider<T> factory)
    {
        ClientApiCompat.registerEntityRenderer(type, factory);
    }

    public static <T extends Entity> void registerEntityRenderer(
        BBSAddonDescriptor descriptor,
        EntityType<T> type,
        EntityRendererProvider<T> factory
    )
    {
        ClientApiCompat.registerEntityRenderer(descriptor, type, factory);
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory)
    {
        ClientApiCompat.registerBlockEntityRenderer(type, factory);
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(
        BBSAddonDescriptor descriptor,
        BlockEntityType<E> type,
        BlockEntityRendererProvider<? super E> factory
    )
    {
        ClientApiCompat.registerBlockEntityRenderer(descriptor, type, factory);
    }

    @FunctionalInterface
    public interface HudRenderHandler
    {
        void render(GuiGraphics graphics, float tickDelta);
    }

    @FunctionalInterface
    public interface WorldRenderHandler
    {
        void render(BBSWorldRenderContext context);
    }
}
