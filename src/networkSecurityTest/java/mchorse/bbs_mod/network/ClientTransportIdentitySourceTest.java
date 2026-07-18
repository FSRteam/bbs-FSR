package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regressions for exact client transport/player payload scope. */
final class ClientTransportIdentitySourceTest
{
    private static final Path NETWORK_COMPAT = Path.of("src/main/java/mchorse/bbs_mod/network/compat/NetworkCompat.java");
    private static final Path BBS_CLIENT = Path.of("src/client/java/mchorse/bbs_mod/BBSModClient.java");
    private static final Path BBS_CLIENT_EVENTS = Path.of("src/client/java/mchorse/bbs_mod/client/BBSClientNeoEvents.java");
    private static final Path CAMERA_CONTROLLER = Path.of("src/client/java/mchorse/bbs_mod/camera/controller/CameraController.java");
    private static final Path CLIENT_COMPAT = Path.of("src/client/java/mchorse/bbs_mod/network/compat/NetworkCompatClient.java");
    private static final Path CLIENT_NETWORK = Path.of("src/client/java/mchorse/bbs_mod/network/ClientNetwork.java");
    private static final Path CONNECTION_GATE = Path.of("src/main/java/mchorse/bbs_mod/network/NetworkConnectionGate.java");
    private static final Path PACKET_CRUSHER = Path.of("src/main/java/mchorse/bbs_mod/network/PacketCrusher.java");

    private ClientTransportIdentitySourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ClientTransportIdentitySourceTest passed");
    }

    static void runAll()
    {
        try
        {
            Path root = findProjectRoot();
            String common = compact(Files.readString(root.resolve(NETWORK_COMPAT)));
            String bbsClient = compact(Files.readString(root.resolve(BBS_CLIENT)));
            String events = compact(Files.readString(root.resolve(BBS_CLIENT_EVENTS)));
            String cameraController = compact(Files.readString(root.resolve(CAMERA_CONTROLLER)));
            String bridge = compact(Files.readString(root.resolve(CLIENT_COMPAT)));
            String client = compact(Files.readString(root.resolve(CLIENT_NETWORK)));
            String gate = compact(Files.readString(root.resolve(CONNECTION_GATE)));
            String crusher = compact(Files.readString(root.resolve(PACKET_CRUSHER)));

            commonBridgeCarriesExactPayloadContext(common);
            legacyClientDescriptorsRemainFailClosed(bridge);
            clientHandlersCaptureAndRevalidateExactScope(client);
            allCoreClientPayloadsUseScopedRegistration(client);
            chunkedHandlersSplitTransportAndPlayerScopes(client);
            typedClientFactoriesStayBehindScopeGates(client);
            retiredTransferTokensCannotReenter(crusher, gate);
            cloneLifecycleRetiresScopeAndPlayback(client, events, bbsClient, cameraController);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect client transport identity wiring", e);
        }
    }

    private static void commonBridgeCarriesExactPayloadContext(String source)
    {
        String handler = section(source, "private static void handleClientPayload", "private static final class PayloadBinding");

        assertOrdered(handler,
            "connection = context.connection()",
            "player = context.player()",
            "connection == null || player == null",
            "Connection.class",
            "Player.class",
            "method.invoke(null, payload.binding().id, buf, connection, player)");
        check(!handler.contains("getMethod(\"dispatchClientPayload\", ResourceLocation.class, FriendlyByteBuf.class)"),
            "common client payload bridge still invokes the unscoped two-argument entry");
    }

    private static void legacyClientDescriptorsRemainFailClosed(String source)
    {
        String legacyReceiver = section(source, "public interface ClientReceiver", "public interface ScopedClientReceiver");
        String legacyRegistration = section(source, "public static synchronized void registerClientReceiver", "public static synchronized void registerCoreClientReceiver");
        String legacyDispatch = section(source,
            "public static void dispatchClientPayload(ResourceLocation id, FriendlyByteBuf buf)",
            "} }");

        check(legacyReceiver.contains("void receive(FriendlyByteBuf buf)"),
            "legacy ClientReceiver single-argument SAM descriptor changed");
        check(legacyRegistration.contains("reason=unscoped_core_channel")
                && legacyRegistration.contains("throw new IllegalStateException"),
            "legacy unscoped receiver registration no longer rejects frozen core channels");
        check(source.contains("void receive(FriendlyByteBuf buf, Connection connection, LocalPlayer player)"),
            "transport-scoped client receiver descriptor is missing");
        check(source.contains("registerCoreClientReceiver(ResourceLocation id, ScopedClientReceiver receiver)"),
            "core transport-scoped client receiver registration is missing");
        check(legacyDispatch.contains("reason=legacy_scope_missing") && !legacyDispatch.contains("receiver.receive"),
            "legacy two-argument client dispatch does not fail closed");
    }

    private static void clientHandlersCaptureAndRevalidateExactScope(String source)
    {
        String registration = section(source, "private static void registerClientReceiver", "private static ClientPayloadScope captureScope");
        String capture = section(source, "private static ClientPayloadScope captureScope", "private static Connection getCurrentTransport");
        String execution = section(source, "private static void executeIfCurrent", "private static boolean isScopeCurrent");
        String current = section(source, "private static boolean isScopeCurrent", "private static void handleAddonBrokerPacket");
        String scope = section(source, "private record ClientPayloadScope", "/* Handlers */");
        String retiredCleanup = section(source, "private static void clearRetiredTransfer", "private static Connection getCurrentTransport");

        assertOrdered(registration,
            "NetworkCompatClient.registerCoreClientReceiver",
            "ClientPayloadScope scope = captureScope(client, connection, player)",
            "if (scope != null)",
            "handler.handle(client, scope, buf)");
        assertOrdered(capture,
            "ClientLevel currentLevel = client.level",
            "connection == null || player == null || !connection.isConnected()",
            "connectionGate.capture(",
            "currentConnection",
            "currentPlayer",
            "gate == null",
            "clearRetiredTransfer(gate)",
            "new ClientPayloadScope(connection, player, currentLevel, gate)");
        assertOrdered(retiredCleanup,
            "scope.retiredTransferIdentity()",
            "retiredTransferIdentity != null",
            "crusher.clearConnection(scope.generation(), retiredTransferIdentity)");
        check(execution.contains("isScopeCurrent(client, scope)"),
            "queued client work no longer revalidates the exact transport scope");
        check(execution.contains("requireLevel && (scope.level() == null || client.level != scope.level())"),
            "queued world-bound client work can cross a ClientLevel replacement");
        assertOrdered(current,
            "scope.connection().isConnected()",
            "connectionGate.isCurrent(scope.gate(), getCurrentTransport(client), client.player)");
        check(scope.contains("ClientLevel level"),
            "the exact client payload scope no longer carries its receive-time ClientLevel identity");
        check(source.contains("connectionGate.rotate(getCurrentTransport(client), client.player)"),
            "logout rotation no longer retires the exact current transport/player identities");
        check(!source.contains("connectionGate.snapshot()"),
            "a client payload handler still snapshots generation after transport dispatch");
    }

    private static void chunkedHandlersSplitTransportAndPlayerScopes(String source)
    {
        assertChunkedScope(source, "handlePlayerFormPacket", "handlePlayFilmPacket", "CLIENT_PLAYER_FORM_PACKET");
        assertChunkedScope(source, "handlePlayFilmPacket", "handleManagerDataPacket", "CLIENT_PLAY_FILM_PACKET");
        assertChunkedScope(source, "handleManagerDataPacket", "handleStopFilmPacket", "CLIENT_MANAGER_DATA_PACKET");
        assertChunkedScope(source, "handleRecordedActionsPacket", "handleFormTriggerPacket", "CLIENT_RECORDED_ACTIONS");
        assertChunkedScope(source, "handleShareFormPacket", "handleEntityFormPacket", "CLIENT_SHARED_FORM");
        assertChunkedScope(source, "handleEntityFormPacket", "handleActorsPacket", "CLIENT_ENTITY_FORM");
    }

    private static void allCoreClientPayloadsUseScopedRegistration(String source)
    {
        String setup = section(source, "public static void setup()", "private static void handleClientTick");

        check(countOccurrences(setup, "registerClientReceiver(ServerNetwork.CLIENT_") == 19,
            "not every frozen c1..c19 payload is registered through the exact-scope helper");
        check(!source.contains("NetworkCompatClient.registerClientReceiver("),
            "ClientNetwork still registers an unscoped legacy client receiver");

        String[] handlers = {
            "handleClientModelBlockPacket",
            "handlePlayerFormPacket",
            "handlePlayFilmPacket",
            "handleManagerDataPacket",
            "handleStopFilmPacket",
            "handleHandshakePacket",
            "handleRecordedActionsPacket",
            "handleFormTriggerPacket",
            "handleCheatsPermissionPacket",
            "handleShareFormPacket",
            "handleEntityFormPacket",
            "handleActorsPacket",
            "handleGunPropertiesPacket",
            "handlePauseFilmPacket",
            "handleSelectedSlotPacket",
            "handleAnimationStateModelBlockPacket",
            "handleRefreshModelBlocksPacket",
            "handleAddonBrokerPacket",
            "handleRequestFilmResync"
        };

        for (String handler : handlers)
        {
            check(source.contains("private static void " + handler + "(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)"),
                handler + " bypasses the exact client transport/player scope");
        }
    }

    private static void typedClientFactoriesStayBehindScopeGates(String source)
    {
        String c2 = section(source, "private static void handlePlayerFormPacket", "private static void handlePlayFilmPacket");
        String c3 = section(source, "private static void handlePlayFilmPacket", "private static void handleManagerDataPacket");
        String c4 = section(source, "private static void handleManagerDataPacket", "private static void handleStopFilmPacket");
        String c7 = section(source, "private static void handleRecordedActionsPacket", "private static void handleFormTriggerPacket");
        String c10 = section(source, "private static void handleShareFormPacket", "private static void handleEntityFormPacket");
        String c11 = section(source, "private static void handleEntityFormPacket", "private static void handleActorsPacket");
        String c13 = section(source, "private static void handleGunPropertiesPacket", "private static void handlePauseFilmPacket");
        String c17 = section(source, "private static void handleRefreshModelBlocksPacket", "private static void handleRequestFilmResync");
        String c18 = section(source, "private static void handleAddonBrokerPacket", "@FunctionalInterface");

        assertOrdered(c2,
            "executeIfCurrent(client, scope, true",
            "if (client.level != level)",
            "try",
            "Form form = FormUtils.fromData(decoded)");
        assertOrdered(c3,
            "executeIfCurrent(client, scope, true",
            "if (client.level != level)",
            "try",
            "Film film = new Film()",
            "film.fromData(decoded)");
        assertOrdered(c4,
            "executeIfCurrent(client, scope, false",
            "try",
            "callback.accept(data)",
            "catch (RuntimeException | LinkageError exception)");
        assertOrdered(c7,
            "executeIfCurrent(client, scope, true",
            "Films films = BBSModClient.getFilms()",
            "films.consumeManualRecordingTerminal",
            "panel.receiveActions(");
        assertOrdered(c10,
            "executeIfCurrent(client, scope, true",
            "try",
            "Form form = FormUtils.fromData(decoded)");
        assertOrdered(c11,
            "executeIfCurrent(client, scope, true",
            "if (client.level != level)",
            "try",
            "Form form = FormUtils.fromData(decoded)");
        assertOrdered(c13,
            "executeIfCurrent(client, scope, true",
            "if (client.level != level)",
            "GunProperties properties = new GunProperties()",
            "properties.fromNetwork(propertiesBuffer)");
        assertOrdered(c17,
            "executeIfCurrent(client, scope, true",
            "if (client.level != level)",
            "try",
            "FormUtils.copy(properties.getForm())",
            "catch (RuntimeException | LinkageError exception)");
        assertOrdered(c18,
            "scope.generation()",
            "executeIfCurrent(client, scope, true, task)");

        check(c2.contains("catch (RuntimeException | LinkageError exception)"),
            "c2 typed Form construction is not fail-closed");
        check(c3.contains("catch (RuntimeException | LinkageError exception)"),
            "c3 typed Film construction is not fail-closed");
        check(c10.contains("catch (RuntimeException | LinkageError exception)"),
            "c10 typed shared-Form construction is not fail-closed");
        check(c11.contains("catch (RuntimeException | LinkageError exception)"),
            "c11 typed entity-Form construction is not fail-closed");
        check(c17.contains("catch (RuntimeException | LinkageError exception)"),
            "c17 model-block Form refresh is not fail-closed");
    }

    private static void retiredTransferTokensCannotReenter(String crusher, String gate)
    {
        String capture = section(gate, "public synchronized Scope capture", "public synchronized boolean isCurrent");
        String replacement = section(gate, "public synchronized Scope replacePlayer", "public synchronized UUID snapshot");
        String rotate = section(gate, "public synchronized UUID rotate(Object", "static final class Scope");
        String receive = section(crusher,
            "public void receive( UUID owner, Object connectionIdentity",
            "private boolean validateFrameHeader");

        assertOrdered(capture,
            "retiredTransferIdentity = this.transferIdentity",
            "this.transferIdentity.retire()",
            "this.transferIdentity = new TransferIdentity()");
        assertOrdered(replacement,
            "Object retiredTransferIdentity = playerReplaced ? this.transferIdentity : null",
            "this.transferIdentity.retire()",
            "this.transferIdentity = new TransferIdentity()");
        assertOrdered(rotate,
            "this.transferIdentity.retire()",
            "this.transferIdentity = null");
        assertOrdered(receive,
            "synchronized (this.transferLock)",
            "connectionIdentity instanceof NetworkConnectionGate.RetirementAwareConnectionIdentity identity",
            "identity.isRetired()",
            "dropTransfer(key, \"retired_connection\"");
    }

    private static void cloneLifecycleRetiresScopeAndPlayback(
        String client,
        String events,
        String bbsClient,
        String cameraController
    )
    {
        String cloneHandler = section(events,
            "private static void onPlayerClone",
            "private static void onGameShuttingDown");
        String networkClone = section(client,
            "public static void onClientPlayerClone",
            "public static boolean isBBSModOnServer");
        String lifecycle = section(bbsClient,
            "public static void onClientPlayerClone",
            "private static void resetCameraControllersForLifecycle");
        String cameraLifecycle = section(bbsClient,
            "private static void resetCameraControllersForLifecycle",
            "public static void onClientTickPre");
        String cameraRemoval = section(cameraController,
            "public List<ICameraController> removeAll",
            "public ICameraController remove(ICameraController controller)");

        check(events.contains("NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onPlayerClone)"),
            "NeoForge ClientPlayerNetworkEvent.Clone listener is not registered");
        assertOrdered(cloneHandler,
            "BBSModClient.onClientPlayerClone(",
            "event.getConnection()",
            "event.getOldPlayer()",
            "event.getNewPlayer()");
        assertOrdered(networkClone,
            "connectionGate.replacePlayer(",
            "getCurrentTransport(client)",
            "client.player",
            "replacement == null",
            "clearRetiredTransfer(replacement)",
            "callbacks.reset()");
        assertOrdered(lifecycle,
            "ClientNetwork.onClientPlayerClone(connection, oldPlayer, newPlayer)",
            "films.reset()",
            "films = new Films()",
            "resetCameraControllersForLifecycle(\"client-player clone\")");
        assertOrdered(cameraRemoval,
            "Iterator<ICameraController> it = this.controllers.iterator()",
            "it.remove()",
            "removed.add(controller)",
            "this.updateCurrent()",
            "return removed");
        check(bbsClient.contains("cameraController.removeAll(PlayCameraController.class)"),
            "Clone lifecycle does not remove every queued Film playback camera");
        assertOrdered(cameraLifecycle,
            "cameraController.removeAll(PlayCameraController.class)",
            "play.getContext().shutdown()",
            "cameraController.reset()");
        check(bbsClient.contains("resetCameraControllersForLifecycle(\"disconnect\")"),
            "disconnect does not share the lifecycle-owned full camera cleanup");
        check(cameraController.contains("public void reset() { this.controllers.clear(); this.current = null; }"),
            "camera reset can resurrect a stale controller after the next add/updateCurrent call");

        String c5 = section(client,
            "private static void handleStopFilmPacket",
            "private static void handleHandshakePacket");

        check(c5.contains("executeIfCurrent(client, scope, false")
                && !c5.contains("ClientLevel level = client.level"),
            "c5 lost its cross-level cleanup exception or exact player scope fence");
    }

    private static void assertChunkedScope(String source, String start, String end, String channel)
    {
        String handler = section(source, "private static void " + start, "private static void " + end);

        check(handler.contains("crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork." + channel),
            start + " does not isolate chunk transfer state by exact transport/player scope");
    }

    private static String section(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0, "missing production marker: " + startMarker);
        check(end > start, "missing production end marker: " + endMarker);

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int index = source.indexOf(marker, previous + 1);

            check(index > previous, "missing or out-of-order production marker: " + marker);
            previous = index;
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(NETWORK_COMPAT)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(NETWORK_COMPAT)))
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

    private static int countOccurrences(String source, String marker)
    {
        int count = 0;
        int index = 0;

        while ((index = source.indexOf(marker, index)) >= 0)
        {
            count += 1;
            index += marker.length();
        }

        return count;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
