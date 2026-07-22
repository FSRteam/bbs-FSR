package mchorse.bbs_mod.client.film.collaboration;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.film.BBSFilmApplyResult;
import mchorse.bbs_mod.api.client.film.BBSFilmCheckpointRequired;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationListener;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationStatus;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationSubscription;
import mchorse.bbs_mod.api.client.film.BBSFilmMutationBatch;
import mchorse.bbs_mod.api.client.film.BBSFilmPresence;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceClearRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceResult;
import mchorse.bbs_mod.api.client.film.BBSFilmRemotePresence;
import mchorse.bbs_mod.api.client.film.BBSFilmServerSequenceObserveRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSession;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotApplyRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotResult;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.plugin.runtime.PluginGenerationFence;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class BBSFilmCollaborationRegistry
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film-collaboration");
    private static final Map<String, SubscriptionImpl> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    /**
     * Stable host-owned routes for the additive hot-plugin path. A route can
     * hold more than one staged generation while a replacement is prepared;
     * dispatch selects the newest generation whose fence is still open.
     */
    private static final Map<String, HotRoute> HOT_ROUTES = new ConcurrentHashMap<>();

    private BBSFilmCollaborationRegistry()
    {}

    public static BBSFilmCollaborationSubscription register(BBSAddonDescriptor descriptor, BBSFilmCollaborationListener listener)
    {
        BBSRegistrationResult rejected = validateRegistration(descriptor, listener);

        if (rejected != null)
        {
            return new InactiveSubscription(rejected);
        }

        String addonId = descriptor.addonId();
        SubscriptionImpl subscription = new SubscriptionImpl(addonId, listener);
        SubscriptionImpl existing = SUBSCRIPTIONS.putIfAbsent(addonId, subscription);

        if (existing != null)
        {
            return new InactiveSubscription(BBSRegistrationResult.duplicate(addonId, existing.listener.getClass().getName()));
        }

        subscription.registration = BBSRegistrationResult.accepted(addonId);
        BBSFilmSession session = BBSFilmCollaborationBridge.currentSession();

        if (session != null)
        {
            executeOnClient(() ->
            {
                BBSFilmSession current = BBSFilmCollaborationBridge.currentSession();

                if (subscription.active() && current != null && current.sessionId() == session.sessionId())
                {
                    notifyOpened(subscription, current);
                }
            });
        }

        return subscription;
    }

    /**
     * Register one generation-owned Film collaboration route for a hot
     * plugin. Unlike the legacy Addon registration window, this path permits
     * a newer generation to stage beside its incumbent. The route only
     * becomes dispatchable once its generation fence is ACTIVE.
     */
    public static BBSFilmCollaborationSubscription subscribe(
        PluginOwner owner,
        PluginGenerationFence fence,
        BBSFilmCollaborationListener listener
    )
    {
        String pluginId = owner == null ? "<unknown>" : owner.pluginId();

        if (owner == null)
        {
            return new InactiveSubscription(BBSRegistrationResult.rejected(pluginId, "plugin owner is null"));
        }

        if (fence == null)
        {
            return new InactiveSubscription(BBSRegistrationResult.rejected(pluginId, "plugin generation fence is null"));
        }

        if (!owner.equals(fence.owner()))
        {
            return new InactiveSubscription(BBSRegistrationResult.rejected(pluginId, "plugin owner does not match generation fence"));
        }

        if (listener == null)
        {
            return new InactiveSubscription(BBSRegistrationResult.rejected(pluginId, "Film collaboration listener is null"));
        }

        HotRoute route;
        HotSubscription subscription;
        boolean accepted;

        synchronized (HOT_ROUTES)
        {
            route = HOT_ROUTES.computeIfAbsent(pluginId, HotRoute::new);
            subscription = new HotSubscription(route, owner, fence, listener);
            accepted = route.add(subscription);
        }

        if (!accepted)
        {
            return new InactiveSubscription(BBSRegistrationResult.duplicate(pluginId, owner.toString()));
        }

        subscription.registration = BBSRegistrationResult.accepted(pluginId);
        BBSFilmSession session = BBSFilmCollaborationBridge.currentSession();

        if (session != null)
        {
            executeOnClient(() -> route.invoke(subscription, (candidate) -> notifyOpened(candidate, session)));
        }

        return subscription;
    }

    public static CompletableFuture<BBSFilmSnapshotResult> requestSnapshot(BBSAddonDescriptor descriptor, long sessionId)
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.NOT_REGISTERED, null, "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmSnapshotResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completeSnapshot(future, owner, sessionId)))
        {
            future.complete(new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.CLOSED, null, "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmApplyResult> applyMutations(BBSAddonDescriptor descriptor, BBSFilmMutationBatch batch)
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(failedApply(BBSFilmCollaborationStatus.NOT_REGISTERED, batch == null ? 0 : batch.sessionId(), "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmApplyResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completeApply(future, owner, batch)))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, batch == null ? 0 : batch.sessionId(), "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmApplyResult> applySnapshot(BBSAddonDescriptor descriptor, BBSFilmSnapshotApplyRequest request)
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(failedApply(BBSFilmCollaborationStatus.NOT_REGISTERED, request == null ? 0 : request.sessionId(), "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmApplyResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completeSnapshotApply(future, owner, request)))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, request == null ? 0 : request.sessionId(), "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmApplyResult> observeServerSequence(
        BBSAddonDescriptor descriptor,
        BBSFilmServerSequenceObserveRequest request
    )
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(failedApply(BBSFilmCollaborationStatus.NOT_REGISTERED, request == null ? 0 : request.sessionId(), "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmApplyResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completeServerSequenceObserve(future, owner, request)))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, request == null ? 0 : request.sessionId(), "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmPresenceResult> applyPresence(BBSAddonDescriptor descriptor, BBSFilmRemotePresence remote)
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(failedPresence(BBSFilmCollaborationStatus.NOT_REGISTERED, remote, "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmPresenceResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completePresenceApply(future, owner, remote)))
        {
            future.complete(failedPresence(BBSFilmCollaborationStatus.CLOSED, remote, "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmPresenceResult> clearPresence(BBSAddonDescriptor descriptor, BBSFilmPresenceClearRequest request)
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(failedPresence(BBSFilmCollaborationStatus.NOT_REGISTERED, request, "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmPresenceResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completePresenceClear(future, owner, request)))
        {
            future.complete(failedPresence(BBSFilmCollaborationStatus.CLOSED, request, "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmPresenceResult> clearAllPresence(BBSAddonDescriptor descriptor, long sessionId)
    {
        RouteSubscription owner = authorizedSubscription(descriptor);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(failedPresence(BBSFilmCollaborationStatus.NOT_REGISTERED, sessionId, "addon is not registered for Film collaboration"));
        }

        CompletableFuture<BBSFilmPresenceResult> future = new CompletableFuture<>();

        if (!executeOnClient(() -> completePresenceClearAll(future, owner, sessionId)))
        {
            future.complete(failedPresence(BBSFilmCollaborationStatus.CLOSED, sessionId, "Minecraft client executor is unavailable"));
        }

        return future;
    }

    public static CompletableFuture<BBSFilmSnapshotResult> requestSnapshot(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        long sessionId
    )
    {
        return executeHot(
            pluginOwner,
            fence,
            new BBSFilmSnapshotResult(
                BBSFilmCollaborationStatus.NOT_REGISTERED,
                null,
                "plugin is not registered for Film collaboration"
            ),
            new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.CLOSED, null, "Minecraft client executor is unavailable"),
            (future, owner) -> completeSnapshot(future, owner, sessionId)
        );
    }

    public static CompletableFuture<BBSFilmApplyResult> applyMutations(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        BBSFilmMutationBatch batch
    )
    {
        long sessionId = batch == null ? 0 : batch.sessionId();

        return executeHot(
            pluginOwner,
            fence,
            failedApply(BBSFilmCollaborationStatus.NOT_REGISTERED, sessionId, "plugin is not registered for Film collaboration"),
            failedApply(BBSFilmCollaborationStatus.CLOSED, sessionId, "Minecraft client executor is unavailable"),
            (future, owner) -> completeApply(future, owner, batch)
        );
    }

    public static CompletableFuture<BBSFilmApplyResult> applySnapshot(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        BBSFilmSnapshotApplyRequest request
    )
    {
        long sessionId = request == null ? 0 : request.sessionId();

        return executeHot(
            pluginOwner,
            fence,
            failedApply(BBSFilmCollaborationStatus.NOT_REGISTERED, sessionId, "plugin is not registered for Film collaboration"),
            failedApply(BBSFilmCollaborationStatus.CLOSED, sessionId, "Minecraft client executor is unavailable"),
            (future, owner) -> completeSnapshotApply(future, owner, request)
        );
    }

    public static CompletableFuture<BBSFilmApplyResult> observeServerSequence(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        BBSFilmServerSequenceObserveRequest request
    )
    {
        long sessionId = request == null ? 0 : request.sessionId();

        return executeHot(
            pluginOwner,
            fence,
            failedApply(BBSFilmCollaborationStatus.NOT_REGISTERED, sessionId, "plugin is not registered for Film collaboration"),
            failedApply(BBSFilmCollaborationStatus.CLOSED, sessionId, "Minecraft client executor is unavailable"),
            (future, owner) -> completeServerSequenceObserve(future, owner, request)
        );
    }

    public static CompletableFuture<BBSFilmPresenceResult> applyPresence(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        BBSFilmRemotePresence remote
    )
    {
        return executeHot(
            pluginOwner,
            fence,
            failedPresence(BBSFilmCollaborationStatus.NOT_REGISTERED, remote, "plugin is not registered for Film collaboration"),
            failedPresence(BBSFilmCollaborationStatus.CLOSED, remote, "Minecraft client executor is unavailable"),
            (future, owner) -> completePresenceApply(future, owner, remote)
        );
    }

    public static CompletableFuture<BBSFilmPresenceResult> clearPresence(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        BBSFilmPresenceClearRequest request
    )
    {
        return executeHot(
            pluginOwner,
            fence,
            failedPresence(BBSFilmCollaborationStatus.NOT_REGISTERED, request, "plugin is not registered for Film collaboration"),
            failedPresence(BBSFilmCollaborationStatus.CLOSED, request, "Minecraft client executor is unavailable"),
            (future, owner) -> completePresenceClear(future, owner, request)
        );
    }

    public static CompletableFuture<BBSFilmPresenceResult> clearAllPresence(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        long sessionId
    )
    {
        return executeHot(
            pluginOwner,
            fence,
            failedPresence(BBSFilmCollaborationStatus.NOT_REGISTERED, sessionId, "plugin is not registered for Film collaboration"),
            failedPresence(BBSFilmCollaborationStatus.CLOSED, sessionId, "Minecraft client executor is unavailable"),
            (future, owner) -> completePresenceClearAll(future, owner, sessionId)
        );
    }

    static void publishSessionOpened(BBSFilmSession session)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (subscription.active())
            {
                notifyOpened(subscription, session);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            route.invokeCurrent((subscription) -> notifyOpened(subscription, session));
        }
    }

    static boolean hasSubscriptions()
    {
        if (!SUBSCRIPTIONS.isEmpty())
        {
            return true;
        }

        return HOT_ROUTES.values().stream().anyMatch(HotRoute::active);
    }

    static void publishLocalMutations(BBSFilmMutationBatch batch)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (!subscription.active())
            {
                continue;
            }

            try
            {
                subscription.listener.onLocalMutations(batch);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-film-collaboration] mutation listener failed for addon '{}'", subscription.addonId, e);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            route.invokeCurrent((subscription) -> subscription.listener.onLocalMutations(batch));
        }
    }

    static void publishCheckpointRequired(BBSFilmCheckpointRequired checkpoint)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (!subscription.active())
            {
                continue;
            }

            try
            {
                subscription.listener.onCheckpointRequired(checkpoint);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-film-collaboration] checkpoint listener failed for addon '{}'", subscription.addonId, e);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            route.invokeCurrent((subscription) -> subscription.listener.onCheckpointRequired(checkpoint));
        }
    }

    static void publishPresence(BBSFilmPresence presence)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (!subscription.active())
            {
                continue;
            }

            try
            {
                subscription.listener.onPresence(presence);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-film-collaboration] presence listener failed for addon '{}'", subscription.addonId, e);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            route.invokeCurrent((subscription) -> subscription.listener.onPresence(presence));
        }
    }

    static void publishSessionClosed(long sessionId)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (subscription.active())
            {
                notifyClosed(subscription, sessionId);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            route.invokeCurrent((subscription) -> notifyClosed(subscription, sessionId));
        }
    }

    static void publishSessionClosing(BBSFilmSession session)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (!subscription.active())
            {
                continue;
            }

            try
            {
                subscription.listener.onSessionClosing(session);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-film-collaboration] closing listener failed for addon '{}'", subscription.addonId, e);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            route.invokeCurrent((subscription) -> subscription.listener.onSessionClosing(session));
        }
    }

    private static void completeSnapshot(CompletableFuture<BBSFilmSnapshotResult> future, RouteSubscription owner, long sessionId)
    {
        if (!isCurrent(owner))
        {
            future.complete(new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.CLOSED, null, "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.requestSnapshot(owner.owner(), sessionId));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] snapshot request failed", e);
            future.complete(new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.INTERNAL_ERROR, null, "snapshot request failed"));
        }
    }

    private static void completeApply(CompletableFuture<BBSFilmApplyResult> future, RouteSubscription owner, BBSFilmMutationBatch batch)
    {
        if (!isCurrent(owner))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, batch == null ? 0 : batch.sessionId(), "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.applyRemote(owner.addonId(), owner.owner(), batch));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote mutation apply failed", e);
            future.complete(failedApply(BBSFilmCollaborationStatus.INTERNAL_ERROR, batch == null ? 0 : batch.sessionId(), "remote mutation apply failed"));
        }
    }

    private static void completeSnapshotApply(CompletableFuture<BBSFilmApplyResult> future, RouteSubscription owner, BBSFilmSnapshotApplyRequest request)
    {
        if (!isCurrent(owner))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, request == null ? 0 : request.sessionId(), "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.applySnapshot(owner.addonId(), owner.owner(), request));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote snapshot apply failed", e);
            future.complete(failedApply(BBSFilmCollaborationStatus.INTERNAL_ERROR, request == null ? 0 : request.sessionId(), "remote snapshot apply failed"));
        }
    }

    private static void completeServerSequenceObserve(
        CompletableFuture<BBSFilmApplyResult> future,
        RouteSubscription owner,
        BBSFilmServerSequenceObserveRequest request
    )
    {
        if (!isCurrent(owner))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, request == null ? 0 : request.sessionId(), "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.observeServerSequence(owner.addonId(), owner.owner(), request));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] server sequence observation failed", e);
            future.complete(failedApply(BBSFilmCollaborationStatus.INTERNAL_ERROR, request == null ? 0 : request.sessionId(), "server sequence observation failed"));
        }
    }

    private static void completePresenceApply(
        CompletableFuture<BBSFilmPresenceResult> future,
        RouteSubscription owner,
        BBSFilmRemotePresence remote
    )
    {
        if (!isCurrent(owner))
        {
            future.complete(failedPresence(BBSFilmCollaborationStatus.CLOSED, remote, "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.applyRemotePresence(owner.addonId(), owner.owner(), remote));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote presence apply failed", e);
            future.complete(failedPresence(BBSFilmCollaborationStatus.INTERNAL_ERROR, remote, "remote presence apply failed"));
        }
    }

    private static void completePresenceClear(
        CompletableFuture<BBSFilmPresenceResult> future,
        RouteSubscription owner,
        BBSFilmPresenceClearRequest request
    )
    {
        if (!isCurrent(owner))
        {
            future.complete(failedPresence(BBSFilmCollaborationStatus.CLOSED, request, "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.clearRemotePresence(owner.addonId(), owner.owner(), request));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote presence clear failed", e);
            future.complete(failedPresence(BBSFilmCollaborationStatus.INTERNAL_ERROR, request, "remote presence clear failed"));
        }
    }

    private static void completePresenceClearAll(
        CompletableFuture<BBSFilmPresenceResult> future,
        RouteSubscription owner,
        long sessionId
    )
    {
        if (!isCurrent(owner))
        {
            future.complete(failedPresence(BBSFilmCollaborationStatus.CLOSED, sessionId, "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.clearAddonPresence(owner.addonId(), owner.owner(), sessionId));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote presence clear-all failed", e);
            future.complete(failedPresence(BBSFilmCollaborationStatus.INTERNAL_ERROR, sessionId, "remote presence clear-all failed"));
        }
    }

    private static void notifyOpened(RouteSubscription subscription, BBSFilmSession session)
    {
        try
        {
            subscription.listener().onSessionOpened(session);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] open listener failed for owner '{}'", subscription.identity(), e);
        }
    }

    private static void notifyClosed(RouteSubscription subscription, long sessionId)
    {
        try
        {
            subscription.listener().onSessionClosed(sessionId);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] close listener failed for owner '{}'", subscription.identity(), e);
        }
    }

    private static BBSRegistrationResult validateRegistration(BBSAddonDescriptor descriptor, BBSFilmCollaborationListener listener)
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();

        if (descriptor == null)
        {
            return BBSRegistrationResult.rejected(addonId, "addon descriptor is null");
        }

        if (addonId == null || addonId.isBlank())
        {
            return BBSRegistrationResult.rejected("<blank>", "addon id is blank");
        }

        if (!descriptor.capabilities().contains(BBSAddonCapability.CLIENT_UI))
        {
            return BBSRegistrationResult.rejected(addonId, "addon did not declare CLIENT_UI capability");
        }

        if (listener == null)
        {
            return BBSRegistrationResult.rejected(addonId, "Film collaboration listener is null");
        }

        return null;
    }

    private static SubscriptionImpl authorizedSubscription(BBSAddonDescriptor descriptor)
    {
        if (descriptor == null || descriptor.addonId() == null || !descriptor.capabilities().contains(BBSAddonCapability.CLIENT_UI))
        {
            return null;
        }

        SubscriptionImpl subscription = SUBSCRIPTIONS.get(descriptor.addonId());

        return subscription != null && subscription.active() ? subscription : null;
    }

    private static RouteSubscription authorizedSubscription(PluginOwner owner, PluginGenerationFence fence)
    {
        if (owner == null || fence == null || !owner.equals(fence.owner()))
        {
            return null;
        }

        HotRoute route = HOT_ROUTES.get(owner.pluginId());
        HotSubscription subscription = route == null ? null : route.get(owner);

        return subscription != null && subscription.fence == fence && subscription.active() ? subscription : null;
    }

    private static <T> CompletableFuture<T> executeHot(
        PluginOwner pluginOwner,
        PluginGenerationFence fence,
        T notRegistered,
        T executorUnavailable,
        BiConsumer<CompletableFuture<T>, RouteSubscription> operation
    )
    {
        RouteSubscription owner = authorizedSubscription(pluginOwner, fence);

        if (owner == null)
        {
            return CompletableFuture.completedFuture(notRegistered);
        }

        CompletableFuture<T> future = new CompletableFuture<>();

        if (!executeOnClient(() -> operation.accept(future, owner)))
        {
            future.complete(executorUnavailable);
        }

        return future;
    }

    private static boolean isCurrent(RouteSubscription subscription)
    {
        return subscription != null && subscription.current();
    }

    private static BBSFilmApplyResult failedApply(BBSFilmCollaborationStatus status, long sessionId, String message)
    {
        return new BBSFilmApplyResult(status, sessionId, -1, 0, BBSFilmMutationBatch.NO_SERVER_SEQUENCE, message);
    }

    private static BBSFilmPresenceResult failedPresence(BBSFilmCollaborationStatus status, BBSFilmRemotePresence remote, String message)
    {
        BBSFilmPresence presence = remote == null ? null : remote.presence();

        return new BBSFilmPresenceResult(
            status,
            presence == null ? 0 : presence.sessionId(),
            -1,
            remote == null ? "" : remote.participantId(),
            remote == null ? BBSFilmMutationBatch.NO_SERVER_SEQUENCE : remote.serverSeq(),
            message
        );
    }

    private static BBSFilmPresenceResult failedPresence(BBSFilmCollaborationStatus status, BBSFilmPresenceClearRequest request, String message)
    {
        return new BBSFilmPresenceResult(
            status,
            request == null ? 0 : request.sessionId(),
            -1,
            request == null ? "" : request.participantId(),
            request == null ? BBSFilmMutationBatch.NO_SERVER_SEQUENCE : request.serverSeq(),
            message
        );
    }

    private static BBSFilmPresenceResult failedPresence(BBSFilmCollaborationStatus status, long sessionId, String message)
    {
        return new BBSFilmPresenceResult(
            status,
            sessionId,
            -1,
            "",
            BBSFilmMutationBatch.NO_SERVER_SEQUENCE,
            message
        );
    }

    private static boolean executeOnClient(Runnable runnable)
    {
        try
        {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft == null)
            {
                return false;
            }

            if (minecraft.isSameThread())
            {
                runnable.run();
            }
            else
            {
                minecraft.execute(runnable);
            }

            return true;
        }
        catch (Exception e)
        {
            LOGGER.warn("[bbs-film-collaboration] Minecraft client executor is unavailable", e);
            return false;
        }
    }

    private interface RouteSubscription
    {
        String addonId();

        PluginOwner owner();

        BBSFilmCollaborationListener listener();

        boolean current();

        default String identity()
        {
            PluginOwner owner = this.owner();

            return owner == null ? this.addonId() : owner.toString();
        }
    }

    private static final class SubscriptionImpl implements BBSFilmCollaborationSubscription, RouteSubscription
    {
        private final String addonId;
        private final BBSFilmCollaborationListener listener;
        private volatile BBSRegistrationResult registration;
        private volatile boolean active = true;

        private SubscriptionImpl(String addonId, BBSFilmCollaborationListener listener)
        {
            this.addonId = addonId;
            this.listener = listener;
        }

        @Override
        public BBSRegistrationResult registration()
        {
            return this.registration;
        }

        @Override
        public boolean active()
        {
            return this.active;
        }

        @Override
        public String addonId()
        {
            return this.addonId;
        }

        @Override
        public PluginOwner owner()
        {
            return null;
        }

        @Override
        public BBSFilmCollaborationListener listener()
        {
            return this.listener;
        }

        @Override
        public boolean current()
        {
            return this.active && SUBSCRIPTIONS.get(this.addonId) == this;
        }

        @Override
        public void close()
        {
            if (!this.active)
            {
                return;
            }

            this.active = false;
            SUBSCRIPTIONS.remove(this.addonId, this);
            BBSFilmSession session = BBSFilmCollaborationBridge.currentSession();
            executeOnClient(() ->
            {
                BBSFilmCollaborationBridge.clearAddonPresence(this.addonId);

                if (session != null)
                {
                    notifyClosed(this, session.sessionId());
                }
            });
        }
    }

    private static final class HotRoute
    {
        private final String pluginId;
        private final Map<Long, HotSubscription> generations = new ConcurrentHashMap<>();

        private HotRoute(String pluginId)
        {
            this.pluginId = pluginId;
        }

        private boolean add(HotSubscription subscription)
        {
            return this.generations.putIfAbsent(subscription.owner.generation(), subscription) == null;
        }

        private HotSubscription get(PluginOwner owner)
        {
            HotSubscription subscription = this.generations.get(owner.generation());

            return subscription != null && subscription.owner.equals(owner) ? subscription : null;
        }

        private boolean active()
        {
            return this.current() != null;
        }

        private boolean isCurrent(HotSubscription subscription)
        {
            return subscription != null && this.current() == subscription;
        }

        private HotSubscription current()
        {
            HotSubscription selected = null;

            for (HotSubscription candidate : this.generations.values())
            {
                if (!candidate.dispatchable())
                {
                    continue;
                }

                if (selected == null || candidate.owner.generation() > selected.owner.generation())
                {
                    selected = candidate;
                }
            }

            return selected;
        }

        private void invokeCurrent(Consumer<HotSubscription> callback)
        {
            this.invoke(this.current(), callback);
        }

        private void invoke(HotSubscription subscription, Consumer<HotSubscription> callback)
        {
            Objects.requireNonNull(callback, "callback");

            if (!this.isCurrent(subscription))
            {
                return;
            }

            var lease = subscription.fence.acquire();

            if (lease == null)
            {
                return;
            }

            try (lease)
            {
                if (!this.isCurrent(subscription))
                {
                    return;
                }

                callback.accept(subscription);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-film-collaboration] hot listener failed for owner '{}'", subscription.owner, e);
            }
        }

        private void remove(HotSubscription subscription)
        {
            synchronized (HOT_ROUTES)
            {
                this.generations.remove(subscription.owner.generation(), subscription);

                if (this.generations.isEmpty())
                {
                    HOT_ROUTES.remove(this.pluginId, this);
                }
            }
        }
    }

    private static final class HotSubscription implements BBSFilmCollaborationSubscription, RouteSubscription
    {
        private final HotRoute route;
        private final PluginOwner owner;
        private final PluginGenerationFence fence;
        private final BBSFilmCollaborationListener listener;
        private volatile BBSRegistrationResult registration;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HotSubscription(
            HotRoute route,
            PluginOwner owner,
            PluginGenerationFence fence,
            BBSFilmCollaborationListener listener
        )
        {
            this.route = route;
            this.owner = owner;
            this.fence = fence;
            this.listener = listener;
        }

        @Override
        public BBSRegistrationResult registration()
        {
            return this.registration;
        }

        @Override
        public boolean active()
        {
            return this.current();
        }

        @Override
        public String addonId()
        {
            return this.owner.pluginId();
        }

        @Override
        public PluginOwner owner()
        {
            return this.owner;
        }

        @Override
        public BBSFilmCollaborationListener listener()
        {
            return this.listener;
        }

        @Override
        public boolean current()
        {
            return this.dispatchable() && this.route.isCurrent(this);
        }

        private boolean dispatchable()
        {
            return !this.closed.get() && this.fence.isOpen();
        }

        @Override
        public void close()
        {
            if (!this.closed.compareAndSet(false, true))
            {
                return;
            }

            this.route.remove(this);
            executeOnClient(() -> BBSFilmCollaborationBridge.clearAddonPresence(this.addonId(), this.owner));
        }
    }

    private static final class InactiveSubscription implements BBSFilmCollaborationSubscription
    {
        private final BBSRegistrationResult registration;

        private InactiveSubscription(BBSRegistrationResult registration)
        {
            this.registration = registration;
        }

        @Override
        public BBSRegistrationResult registration()
        {
            return this.registration;
        }

        @Override
        public boolean active()
        {
            return false;
        }

        @Override
        public void close()
        {}
    }
}
