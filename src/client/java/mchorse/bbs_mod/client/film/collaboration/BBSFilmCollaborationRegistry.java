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
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BBSFilmCollaborationRegistry
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film-collaboration");
    private static final Map<String, SubscriptionImpl> SUBSCRIPTIONS = new ConcurrentHashMap<>();

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

    public static CompletableFuture<BBSFilmSnapshotResult> requestSnapshot(BBSAddonDescriptor descriptor, long sessionId)
    {
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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
        SubscriptionImpl owner = authorizedSubscription(descriptor);

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

    static void publishSessionOpened(BBSFilmSession session)
    {
        for (SubscriptionImpl subscription : SUBSCRIPTIONS.values())
        {
            if (subscription.active())
            {
                notifyOpened(subscription, session);
            }
        }
    }

    static boolean hasSubscriptions()
    {
        return !SUBSCRIPTIONS.isEmpty();
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
    }

    private static void completeSnapshot(CompletableFuture<BBSFilmSnapshotResult> future, SubscriptionImpl owner, long sessionId)
    {
        if (!isCurrent(owner))
        {
            future.complete(new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.CLOSED, null, "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.requestSnapshot(sessionId));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] snapshot request failed", e);
            future.complete(new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.INTERNAL_ERROR, null, "snapshot request failed"));
        }
    }

    private static void completeApply(CompletableFuture<BBSFilmApplyResult> future, SubscriptionImpl owner, BBSFilmMutationBatch batch)
    {
        if (!isCurrent(owner))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, batch == null ? 0 : batch.sessionId(), "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.applyRemote(owner.addonId, batch));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote mutation apply failed", e);
            future.complete(failedApply(BBSFilmCollaborationStatus.INTERNAL_ERROR, batch == null ? 0 : batch.sessionId(), "remote mutation apply failed"));
        }
    }

    private static void completeSnapshotApply(CompletableFuture<BBSFilmApplyResult> future, SubscriptionImpl owner, BBSFilmSnapshotApplyRequest request)
    {
        if (!isCurrent(owner))
        {
            future.complete(failedApply(BBSFilmCollaborationStatus.CLOSED, request == null ? 0 : request.sessionId(), "Film collaboration subscription closed before execution"));
            return;
        }

        try
        {
            future.complete(BBSFilmCollaborationBridge.applySnapshot(owner.addonId, request));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote snapshot apply failed", e);
            future.complete(failedApply(BBSFilmCollaborationStatus.INTERNAL_ERROR, request == null ? 0 : request.sessionId(), "remote snapshot apply failed"));
        }
    }

    private static void completeServerSequenceObserve(
        CompletableFuture<BBSFilmApplyResult> future,
        SubscriptionImpl owner,
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
            future.complete(BBSFilmCollaborationBridge.observeServerSequence(owner.addonId, request));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] server sequence observation failed", e);
            future.complete(failedApply(BBSFilmCollaborationStatus.INTERNAL_ERROR, request == null ? 0 : request.sessionId(), "server sequence observation failed"));
        }
    }

    private static void completePresenceApply(
        CompletableFuture<BBSFilmPresenceResult> future,
        SubscriptionImpl owner,
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
            future.complete(BBSFilmCollaborationBridge.applyRemotePresence(owner.addonId, remote));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote presence apply failed", e);
            future.complete(failedPresence(BBSFilmCollaborationStatus.INTERNAL_ERROR, remote, "remote presence apply failed"));
        }
    }

    private static void completePresenceClear(
        CompletableFuture<BBSFilmPresenceResult> future,
        SubscriptionImpl owner,
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
            future.complete(BBSFilmCollaborationBridge.clearRemotePresence(owner.addonId, request));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote presence clear failed", e);
            future.complete(failedPresence(BBSFilmCollaborationStatus.INTERNAL_ERROR, request, "remote presence clear failed"));
        }
    }

    private static void completePresenceClearAll(
        CompletableFuture<BBSFilmPresenceResult> future,
        SubscriptionImpl owner,
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
            future.complete(BBSFilmCollaborationBridge.clearAddonPresence(owner.addonId, sessionId));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] remote presence clear-all failed", e);
            future.complete(failedPresence(BBSFilmCollaborationStatus.INTERNAL_ERROR, sessionId, "remote presence clear-all failed"));
        }
    }

    private static void notifyOpened(SubscriptionImpl subscription, BBSFilmSession session)
    {
        try
        {
            subscription.listener.onSessionOpened(session);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] open listener failed for addon '{}'", subscription.addonId, e);
        }
    }

    private static void notifyClosed(SubscriptionImpl subscription, long sessionId)
    {
        try
        {
            subscription.listener.onSessionClosed(sessionId);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] close listener failed for addon '{}'", subscription.addonId, e);
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

    private static boolean isCurrent(SubscriptionImpl subscription)
    {
        return subscription.active() && SUBSCRIPTIONS.get(subscription.addonId) == subscription;
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

    private static final class SubscriptionImpl implements BBSFilmCollaborationSubscription
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
