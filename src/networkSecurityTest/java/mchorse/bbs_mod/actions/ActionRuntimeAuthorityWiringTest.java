package mchorse.bbs_mod.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level wiring regressions for runtime authority and teardown order. */
public final class ActionRuntimeAuthorityWiringTest
{
    private static final Path ACTION_MANAGER = Path.of("src/main/java/mchorse/bbs_mod/actions/ActionManager.java");
    private static final Path ACTION_PLAYER = Path.of("src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java");
    private static final Path BBS_MOD = Path.of("src/main/java/mchorse/bbs_mod/BBSMod.java");
    private static final Path AUTHORITY_POLICY = Path.of("src/main/java/mchorse/bbs_mod/actions/FilmActionAuthorityPolicy.java");
    private static final Path BBS_CLIENT = Path.of("src/client/java/mchorse/bbs_mod/BBSModClient.java");
    private static final Path CLIENT_NETWORK = Path.of("src/client/java/mchorse/bbs_mod/network/ClientNetwork.java");
    private static final Path CLIENT_RECORDER = Path.of("src/client/java/mchorse/bbs_mod/film/Recorder.java");
    private static final Path FILMS = Path.of("src/client/java/mchorse/bbs_mod/film/Films.java");
    private static final Path FILM_PANEL = Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");
    private static final Path FIRST_PERSON_LEASE = Path.of("src/main/java/mchorse/bbs_mod/actions/FirstPersonStateLeaseRegistry.java");
    private static final Path PLAYER_TYPE = Path.of("src/main/java/mchorse/bbs_mod/actions/PlayerType.java");
    private static final Path SERVER_NETWORK = Path.of("src/main/java/mchorse/bbs_mod/network/ServerNetwork.java");

    private ActionRuntimeAuthorityWiringTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ActionRuntimeAuthorityWiringTest passed");
    }

    public static void runAll()
    {
        try
        {
            Path root = findProjectRoot();
            String manager = read(root, ACTION_MANAGER);
            String player = read(root, ACTION_PLAYER);
            String bbsMod = read(root, BBS_MOD);
            String policy = read(root, AUTHORITY_POLICY);
            String bbsClient = read(root, BBS_CLIENT);
            String clientNetwork = read(root, CLIENT_NETWORK);
            String clientRecorder = read(root, CLIENT_RECORDER);
            String films = read(root, FILMS);
            String filmPanel = read(root, FILM_PANEL);
            String lease = read(root, FIRST_PERSON_LEASE);
            String playerType = read(root, PLAYER_TYPE);
            String network = read(root, SERVER_NETWORK);

            preflightPrecedesSerializationAndDelivery(network);
            exactStopKeepsRuntimeUntilTeardown(manager, network);
            recordingRequiresExplicitExactLocalStop(manager, player, network, clientNetwork, clientRecorder, films, filmPanel);
            legacyRecordingDescriptorsRemainCallable(manager, network, filmPanel);
            actionManagerIsolatesAddonLinkageErrors(manager);
            revokedPermissionPreservesOwnedCleanup(network);
            sharedFormConstructionRunsOnClientThread(clientNetwork);
            worldBoundClientHandlersUseExactLevel(clientNetwork);
            serverPlayerFormFactoryRunsAfterGates(network);
            runtimePresenceIsIdentityBound(player);
            disconnectTearsDownOldFilmsLocally(bbsClient, films);
            dynamicFirstPersonStateUsesIdentityLease(manager, player, lease);
            clonedPlayersRestoreAndReleaseFirstPersonState(bbsMod, manager, player, lease, network);
            resetRetainsFailedRuntimeForRetry(manager);
            revocationUsesExpectedServerAndTypedStopRoute(manager, player, policy, playerType, network);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect action runtime authority wiring", e);
        }
    }

    private static void preflightPrecedesSerializationAndDelivery(String network)
    {
        String normal = section(
            network,
            "public static void sendPlayFilm(ServerPlayer player, ServerLevel world",
            "public static void sendPlayFilm(ServerPlayer player, String filmId"
        );
        String targeted = section(
            network,
            "public static void sendPlayFilm( ServerPlayer player, @Nullable ServerPlayer requester",
            "public static boolean stopFilmForPlayer"
        );

        assertOrdered(normal,
            "player == null || world == null",
            "player.serverLevel() != world",
            "world.getServer() != player.getServer()",
            "FilmManager films = BBSMod.getFilms()",
            "FilmActionAuthorityPolicy.requiresAdministrator(film)",
            "FilmPlaybackPolicy.isPlaybackAllowed",
            "BaseType data = film.toData()",
            "actions.play(",
            "crusher.send(");
        assertOrdered(targeted,
            "FilmActionAuthorityPolicy.requiresAdministrator(film)",
            "FilmPlaybackPolicy.isPlaybackAllowed",
            "BaseType data = film.toData()",
            "actions.playAuthorized(",
            "crusher.send(");
        check(!targeted.contains("player.serverLevel() != world")
                && !targeted.contains("world.getServer() != player.getServer()"),
            "the normal broadcast-world identity contract leaked into targeted playback");
    }

    private static void exactStopKeepsRuntimeUntilTeardown(String manager, String network)
    {
        String stopForPlayer = section(network, "public static boolean stopFilmForPlayer", "private static boolean trySendStopFilm");
        String stopMatching = section(manager, "private int stopMatching", "/* Actions recording */");
        String recordingDelivery = section(
            network,
            "public static boolean finishRecordingTerminal",
            "public static boolean sendRecordedActionsForActiveRecording"
        );

        assertOrdered(stopForPlayer,
            "String canonicalId = canonicalFilmId",
            "ActionPlayer runtime = actions.getPlayer(canonicalId, player)",
            "actions.stopExact(runtime, \"network_stop\")",
            "mutationSessions.releaseFilm(canonicalId, owner, player)",
            "trySendStopFilm");
        assertOrdered(stopMatching,
            "List<ActionPlayer> matches = new ArrayList<>()",
            "matches.add(next)",
            "this.stopExact(next, \"manual\")");
        check(stopForPlayer.contains("boolean broadcastRuntime = ownsFilm && !matchingRecording"),
            "an explicit recording stop can still enter the generic c5 broadcast path");
        assertOrdered(stopForPlayer,
            "ActionRecorder recorder = actions.getRecorder(player)",
            "boolean recorderMatches",
            "finishRecordingTerminal(");
        assertOrdered(recordingDelivery,
            "actions.prepareRecordingTerminalExact(",
            "if (!recorder.isTerminalDelivered())",
            "sendRecordedActionsForActiveRecording(",
            "recorder.markTerminalDelivered()",
            "mutationSessions.releaseRecording(owner, player)",
            "mutationSessions.releaseFilm(canonicalId, owner, player)",
            "actions.commitRecordingTerminalExact(player, recorder)");
        check(!recordingDelivery.contains("new Clips("),
            "a missing exact recorder is converted into a synthetic successful terminal");
        check(!recordingDelivery.contains("trySendStopFilm") && !recordingDelivery.contains("sendStopFilm"),
            "an explicit recording stop notifies through generic c5 instead of exact c7");

        String tick = section(manager, "public void tick()", "/* Actions playback */");
        assertOrdered(tick,
            "this.finishRecordingState(player)",
            "ServerNetwork.releaseFilmSession",
            "this.players.remove(index)");
    }

    private static void recordingRequiresExplicitExactLocalStop(
        String manager,
        String player,
        String network,
        String clientNetwork,
        String clientRecorder,
        String films,
        String filmPanel
    )
    {
        String playerTick = section(player, "public boolean tick()", "private void applyAction()");
        String recordingRequest = section(
            network,
            "private static void handleActionRecording",
            "private static void handleToggleFilm"
        );
        String activeRecordingDelivery = section(
            network,
            "public static boolean sendRecordedActionsForActiveRecording",
            "public static void sendHandshake"
        );
        String recordingTerminal = section(
            network,
            "public enum RecordingTerminal",
            "private static ServerPacketCrusher crusher"
        );
        String recordingStart = section(recordingRequest, "if (recording)", "} else {");
        String recordingStop = section(recordingRequest, "} else {", "});");
        String exactDuplicate = section(recordingStart, "if (exactDuplicate)", "if (activeSession != null");
        String inconsistentActive = section(recordingStart, "if (activeSession != null", "if (canonicalId == null");
        String startRejected = section(
            network,
            "private static void sendRecordingStartRejected",
            "public static boolean finishRecordingTerminal"
        );
        String release = section(network, "public static void releaseRecordingSession", "public static void sendMorphToTracked");
        String tick = section(manager, "public void tick()", "/* Actions playback */");
        String recordingAdmission = section(
            manager,
            "public boolean tryStartRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)",
            "public boolean hasRecording(ServerPlayer entity)"
        );
        String recordingState = section(
            manager,
            "private ActionRecorder getRecordingState(ActionPlayer player)",
            "private boolean finishRecordingState(ActionPlayer player)"
        );
        String finishRecording = section(
            manager,
            "private boolean finishRecordingState(ActionPlayer player)",
            "public void stop(String filmId)"
        );

        check(playerTick.contains("boolean finished = this.type != PlayerType.RECORDING && !this.syncing && this.tick >= this.duration"),
            "RECORDING once again finishes at the ordinary film duration");
        assertOrdered(recordingRequest,
            "tick = buf.readInt()",
            "NetworkMutationPolicy.isRecordingStartAllowed(",
            "mutationSessions.claimRecording(",
            "actions.tryStartRecording(film, player, tick, countdown, replayId)");
        assertOrdered(recordingAdmission,
            "recorder = new ActionRecorder(film, entity, 0, countdown)",
            "ActionPlayer play = this.play(",
            "this.recorders.put(entity, recorder)",
            "this.recordingPlayers.put(entity, play)");
        check(recordingRequest.indexOf("PermissionUtils.arePanelsAllowed")
                > recordingRequest.indexOf("recording = buf.readBoolean()"),
            "recording cleanup is rejected by panel permission before its exact tuple can be decoded");
        assertOrdered(recordingRequest,
            "recording = buf.readBoolean()",
            "server.execute(",
            "!isCurrentConnection(server, player)",
            "if (recording)",
            "!PermissionUtils.arePanelsAllowed(server, player)",
            "sendRecordingStartRejected(player, filmId, replayId, tick)");
        check(!recordingStop.contains("PermissionUtils.arePanelsAllowed"),
            "exact recording cleanup is blocked after panel permission is revoked");
        check(!recordingStop.contains("directActionGate")
                && !recordingStop.contains("NetworkDirectActionGate.Channel.RECORDING_START"),
            "s5 state=false cleanup is throttled by the recording-start budget");
        assertOrdered(recordingStop,
            "canonicalFilmId(BBSMod.getFilms(), filmId)",
            "mutationSessions.getRecording(owner, player)",
            "canonicalId.equals(session.filmId())",
            "replayId != session.replayId()",
            "tick != session.tick()",
            "BBSMod.getActions().getRecorder(player)",
            "finishRecordingTerminal(player, canonicalId, recorder, RecordingTerminal.LEGACY_MANUAL)");
        check(!recordingStop.contains("session.dimension()"),
            "s5 exact recording cleanup is blocked after its owner changes dimension");

        assertOrdered(recordingStart,
            "NetworkMutationSessions.RecordingSession activeSession",
            "ActionPlayer activeRuntime",
            "boolean exactDuplicate",
            "canonicalId.equals(activeSession.filmId())",
            "dimension.equals(activeSession.dimension())",
            "replayId == activeSession.replayId()",
            "tick == activeSession.tick()",
            "actions.hasRecording(player)",
            "activeRuntime.type == PlayerType.RECORDING",
            "activeRuntime.getServerPlayer() == player",
            "activeRuntime.getLevel() == player.serverLevel()",
            "canonicalId.equals(activeRuntime.film.getId())",
            "activeRuntime.exception == replayId",
            "if (exactDuplicate)");
        check(exactDuplicate.contains("return")
                && !exactDuplicate.contains("sendRecordingStartRejected")
                && !exactDuplicate.contains("actions.stop")
                && !exactDuplicate.contains("directActionGate"),
            "an exact duplicate recording start kills or throttles the active matching runtime");
        check(inconsistentActive.contains("sendRecordingStartRejected(player, filmId, replayId, tick)")
                && inconsistentActive.contains("return"),
            "a different or inconsistent active recording does not reject the new tuple with c7");
        assertOrdered(recordingStart,
            "if (canonicalId == null",
            "directActionGate.tryAcquire(",
            "NetworkDirectActionGate.Channel.RECORDING_START",
            "sendRecordingStartRejected(player, filmId, replayId, tick)",
            "mutationSessions.claimFilm(canonicalId, owner, player, dimension)",
            "FilmActionAuthorityPolicy.loadFilmForRequester",
            "NetworkMutationPolicy.isRecordingStartAllowed",
            "mutationSessions.claimRecording",
            "actions.tryStartRecording",
            "actions.getRecordingPlayer(player)",
            "recordingRuntime.tryResendActors()",
            "actions.stopExact(previousRuntime, \"record_start_replace\")");
        check(!recordingStart.contains("actions.stop(canonicalId, player)"),
            "recording replacement can tear down a newer same-id runtime");
        check(countOccurrences(recordingStart, "sendRecordingStartRejected(player, filmId, replayId, tick)") >= 11,
            "a reliable recording-start rejection can leave the client recorder half-live");
        assertOrdered(startRejected,
            "sendRecordedActions(",
            "player",
            "filmId",
            "replayId",
            "tick",
            "new Clips(\"...\", BBSMod.getFactoryActionClips())",
            "RecordingTerminal.START_REJECTED",
            "catch (RuntimeException | LinkageError e)");

        assertOrdered(recordingTerminal,
            "LEGACY_MANUAL(0)",
            "SERVER_FORCED(1)",
            "START_REJECTED(2)",
            "public static RecordingTerminal fromId(int id)");

        assertOrdered(recordingState,
            "this.recordingPlayers.get(owner) != player",
            "this.recorders.get(owner)",
            "recorder.getFilm() == player.film");
        assertOrdered(finishRecording,
            "this.getRecordingState(player)",
            "ServerNetwork.finishRecordingTerminal(",
            "player.getServerPlayer()",
            "player.film.getId()",
            "recorder",
            "ServerNetwork.RecordingTerminal.SERVER_FORCED");
        check(!finishRecording.contains("sendStopFilm"),
            "forced RECORDING teardown fell back to the generic c5 stop packet");
        check(!finishRecording.contains("player.exception")
                && !finishRecording.contains("recorder.getInitialTick()"),
            "forced RECORDING c7 metadata was reconstructed outside its exact recording session");
        check(startRejected.contains("new Clips(\"...\", BBSMod.getFactoryActionClips())")
                && startRejected.contains("RecordingTerminal.START_REJECTED")
                && !startRejected.contains("RecordingTerminal.SERVER_FORCED"),
            "s5 start rejection is not an explicit exact-tuple terminal");

        assertOrdered(activeRecordingDelivery,
            "canonicalFilmId(BBSMod.getFilms(), filmId)",
            "mutationSessions.getRecording(player.getUUID(), player)",
            "canonicalId == null",
            "session == null",
            "canonicalId.equals(session.filmId())",
            "sendRecordedActions(",
            "session.filmId()",
            "session.replayId()",
            "session.tick()",
            "clips");
        check(activeRecordingDelivery.contains("if (terminal != RecordingTerminal.LEGACY_MANUAL)")
                && activeRecordingDelivery.contains("packetByteBuf.writeByte(terminal.id())"),
            "legacy c7 no longer omits the optional marker or typed terminals no longer append it");

        check(release.contains("mutationSessions.releaseRecording(ownerId, owner)"),
            "recording terminal cleanup no longer releases the recording session");
        check(release.contains("mutationSessions.releaseFilm(filmId, ownerId, owner)"),
            "recording terminal cleanup no longer releases the matching film lease");
        assertOrdered(tick,
            "this.finishRecordingState(player)",
            "ServerNetwork.releaseFilmSession",
            "this.players.remove(index)");

        verifyExactLocalRecordingStop(clientNetwork, clientRecorder, films, filmPanel);
    }

    private static void legacyRecordingDescriptorsRemainCallable(String manager, String network, String filmPanel)
    {
        String legacyManager = section(
            manager,
            "public void startRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)",
            "public boolean tryStartRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)"
        );
        String transactionalManager = section(
            manager,
            "public boolean tryStartRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)",
            "public boolean hasRecording(ServerPlayer entity)"
        );
        String recordingRequest = section(
            network,
            "private static void handleActionRecording",
            "private static void handleToggleFilm"
        );
        String legacyPanel = section(
            filmPanel,
            "public void receiveActions(String filmId, int replayId, int tick, BaseType clips)",
            "ServerNetwork.RecordingTerminal recordingTerminal"
        );

        check(legacyManager.contains("this.tryStartRecording(film, entity, tick, countdown, replayId)"),
            "legacy ActionManager.startRecording descriptor no longer delegates to the transactional start");
        check(transactionalManager.contains("return false") && transactionalManager.contains("return true"),
            "transactional recording start no longer reports both rejection and success");
        check(recordingRequest.contains("actions.tryStartRecording(film, player, tick, countdown, replayId)")
                && !recordingRequest.contains("actions.startRecording(film, player, 0, countdown, replayId)"),
            "server recording admission no longer consumes the playback cursor and transactional result");
        assertOrdered(legacyPanel,
            "this.receiveActions(",
            "filmId",
            "replayId",
            "tick",
            "clips",
            "null",
            "false",
            "true",
            "ServerNetwork.RecordingTerminal.LEGACY_MANUAL");
        check(!legacyPanel.contains("stopRecording") && !legacyPanel.contains("applyRecordedKeyframes"),
            "legacy UIFilmPanel.receiveActions wrapper gained recorder or keyframe side effects");
    }

    private static void actionManagerIsolatesAddonLinkageErrors(String manager)
    {
        String tick = section(manager, "public void tick()", "/* Actions playback */");

        assertOrdered(tick,
            "shouldStop = player.tick()",
            "catch (RuntimeException | LinkageError e)",
            "player.requestForcedStop()",
            "shouldStop = true",
            "this.tryTeardown(player, \"natural\")");
        check(!manager.contains("catch (RuntimeException e)"),
            "an ActionManager runtime boundary still lets addon LinkageError escape");
    }

    private static void revokedPermissionPreservesOwnedCleanup(String network)
    {
        String toggle = section(
            network,
            "private static void handleToggleFilm",
            "private static void handleActionControl"
        );
        String toggleRuntime = section(toggle, "if (actionPlayer != null)", "} else {");
        String toggleStart = section(toggle, "} else {", "});");
        String control = section(
            network,
            "private static void handleActionControl",
            "private static void handleSyncData"
        );
        String stop = section(
            control,
            "if (state == ActionState.STOP)",
            "if (actionPlayer == null && state != ActionState.RESTART)"
        );
        String ownership = section(
            network,
            "private static boolean canMutateFilm",
            "private static String canonicalFilmId"
        );

        check(toggle.indexOf("PermissionUtils.arePanelsAllowed") > toggle.indexOf("} else {"),
            "s6 rejects an owned runtime stop before reaching its cleanup branch");
        assertOrdered(toggle,
            "server.execute(",
            "!isCurrentConnection(server, player)",
            "canonicalFilmId(BBSMod.getFilms(), filmId)",
            "mutationSessions.getRecording(player.getUUID(), player)",
            "BBSMod.getActions().getPlayer(canonicalId)",
            "if (actionPlayer != null)");
        assertOrdered(toggleRuntime,
            "actionPlayer.getServerPlayer() != player",
            "!mutationSessions.ownsFilm(canonicalId, player.getUUID(), player)",
            "stopFilmForPlayer(player, canonicalId)");
        check(!toggleRuntime.contains("canMutateFilm"),
            "s6 cleanup remains dimension-bound after an owned runtime changes level");
        check(!toggleRuntime.contains("directActionGate"),
            "s6 exact STOP is throttled by the FILM_START admission budget");
        check(!toggleRuntime.contains("PermissionUtils.arePanelsAllowed"),
            "s6 owned runtime cleanup still depends on current panel permission");
        assertOrdered(toggleStart,
            "!PermissionUtils.arePanelsAllowed(server, player)",
            "directActionGate.tryAcquire(",
            "NetworkDirectActionGate.Channel.FILM_START",
            "sendPlayFilm(player, player.serverLevel(), canonicalId, withCamera)");

        check(control.indexOf("PermissionUtils.arePanelsAllowed") > control.indexOf("ActionState state = states[stateId]"),
            "s7 rejects STOP before decoding the requested action state");
        assertOrdered(control,
            "ActionState state = states[stateId]",
            "state != ActionState.STOP && !PermissionUtils.arePanelsAllowed(server, player)",
            "server.execute(",
            "!isCurrentConnection(server, player)",
            "state != ActionState.STOP && !PermissionUtils.arePanelsAllowed(server, player)",
            "canonicalFilmId(films, filmId)",
            "mutationSessions.getRecording(player.getUUID(), player)",
            "actions.getPlayer(canonicalId)",
            "if (state == ActionState.STOP)",
            "stopFilmForPlayer(player, canonicalId)",
            "actionPlayer != null && !canMutateFilm(player, canonicalId, actionPlayer)");
        check(!stop.contains("PermissionUtils.arePanelsAllowed"),
            "s7 STOP cleanup still depends on current panel permission");
        assertOrdered(stop,
            "stopFilmForPlayer(player, canonicalId)",
            "return");

        assertOrdered(ownership,
            "mutationSessions.getRecording(player.getUUID(), player) == null",
            "actionPlayer.getServerPlayer() == player",
            "actionPlayer.getLevel() == player.serverLevel()",
            "mutationSessions.ownsFilm(filmId, player.getUUID(), player, dimensionId(player.serverLevel()))");
    }

    private static void verifyExactLocalRecordingStop(
        String clientNetwork,
        String clientRecorder,
        String films,
        String filmPanel
    )
    {
        String manualStop = section(
            films,
            "public Recorder stopRecording()",
            "public Recorder stopRecordingFromServer"
        );
        String startRecording = section(
            films,
            "public void startRecording(Film film",
            "public Recorder stopRecording()"
        );
        String serverStop = section(
            films,
            "public Recorder stopRecordingFromServer",
            "private Recorder stopRecording(Recorder recorder, boolean notifyServer)"
        );
        String localTeardown = section(
            films,
            "private Recorder stopRecording(Recorder recorder, boolean notifyServer, boolean restorePlayer)",
            "private PendingRecordingTerminal markPendingRecordingTerminal"
        );
        String manualTerminal = section(
            films,
            "public ManualRecordingTerminal consumeManualRecordingTerminal",
            "private Recorder stopRecording(Recorder recorder, boolean notifyServer)"
        );
        String markPendingTerminal = section(
            films,
            "private PendingRecordingTerminal markPendingRecordingTerminal",
            "private void removePendingRecordingTerminal"
        );
        String removePendingTerminal = section(
            films,
            "private void removePendingRecordingTerminal",
            "private static Throwable appendFailure"
        );
        String recorderConstructor = section(
            clientRecorder,
            "public Recorder(Film film",
            "ClientLevel getInitialLevel()"
        );
        String recorderLevel = section(
            clientRecorder,
            "public boolean isInCurrentLevel()",
            "public boolean hasNotStarted()"
        );
        String filmsUpdate = section(films, "public void update()", "public void updateEndWorld()");
        String recordedFrame = section(
            clientRecorder,
            "public boolean hasRecordedFrame()",
            "public void update()"
        );
        int recorderShutdownStart = clientRecorder.indexOf("void shutdown(boolean restorePlayer)");

        check(recorderShutdownStart >= 0, "Recorder local teardown is unavailable");

        String recorderShutdown = clientRecorder.substring(recorderShutdownStart);
        String lifecycleTeardown = section(
            films,
            "public Recorder stopRecordingForClientLifecycle(Recorder recorder)",
            "private Recorder stopRecording(Recorder recorder, boolean notifyServer, boolean restorePlayer)"
        );
        String genericStopPacket = section(
            clientNetwork,
            "private static void handleStopFilmPacket",
            "private static void handleHandshakePacket"
        );
        String recordedActionsPacket = section(
            clientNetwork,
            "private static void handleRecordedActionsPacket",
            "private static void handleFormTriggerPacket"
        );
        String manualTerminalGate = section(
            recordedActionsPacket,
            "films.consumeManualRecordingTerminal(filmId, replayId, tick)",
            "Recorder recorder = null"
        );
        String receiveActions = section(
            filmPanel,
            "public void receiveActions(",
            "public void applyRecordedKeyframes"
        );
        String panelOpen = section(filmPanel, "public void open()", "public void receiveActions(");
        String applyKeyframes = section(receiveActions, "if (applyKeyframes", "boolean mergeTerminalActions");
        String mergeActions = section(receiveActions, "if (mergeTerminalActions)", "if (changed)");

        check(manualStop.contains("return this.stopRecording(this.recorder, true)"),
            "manual recording stop no longer owns the server notification path");
        assertOrdered(startRecording,
            "this.recorder = new Recorder",
            "this.recorder.getRecordingFilmId()",
            "this.recorder.getRecordingReplayId()",
            "this.recorder.getRecordingTick()",
            "this.recorder.countdown",
            "true");
        assertOrdered(serverStop,
            "Recorder recorder = this.recorder",
            "recorder == null",
            "recorder.matchesRecording(filmId, replayId, tick)",
            "return this.stopRecording(recorder, false)");
        check(!serverStop.contains("sendActionRecording"),
            "server-driven recording stop directly loops a stop request back to s5");
        check(films.contains("return this.stopRecording(recorder, notifyServer, true)"),
            "ordinary recording teardown no longer preserves its notification flag");
        check(lifecycleTeardown.contains("return this.stopRecording(recorder, false, false)"),
            "disconnect recording teardown can notify the server or restore a disconnected player");

        assertOrdered(localTeardown,
            "this.recorder != recorder",
            "this.recorder = null",
            "if (notifyServer && ClientNetwork.isIsBBSModOnServer())",
            "this.markPendingRecordingTerminal(recorder)",
            "ClientNetwork.sendActionRecording",
            "recorder.getRecordingFilmId()",
            "recorder.getRecordingReplayId()",
            "recorder.getRecordingTick()",
            "this.removePendingRecordingTerminal(pending)",
            "recorder.shutdown(restorePlayer)");

        assertOrdered(manualTerminal,
            "new RecordingIdentity(filmId, replayId, tick)",
            "this.pendingRecordingTerminals.iterator()",
            "terminal.identity().equals(identity)",
            "iterator.remove()",
            "terminal.started()",
            "ManualRecordingTerminal.CANCELED_BEFORE_START",
            "terminal.level() != null && terminal.level() == Minecraft.getInstance().level",
            "ManualRecordingTerminal.STOPPED_AFTER_START",
            "ManualRecordingTerminal.STOPPED_AFTER_START_MERGE_BLOCKED");
        assertOrdered(markPendingTerminal,
            "new RecordingIdentity(",
            "recorder.getRecordingFilmId()",
            "recorder.getRecordingReplayId()",
            "recorder.getRecordingTick()",
            "recorder.hasRecordedFrame()",
            "recorder.getInitialLevel()",
            "this.pendingRecordingTerminals.addLast(pending)",
            "this.pendingRecordingTerminals.size() > MAX_PENDING_RECORDING_TERMINALS",
            "this.pendingRecordingTerminals.removeFirst()");
        check(!markPendingTerminal.contains("pendingRecordingTerminals.remove(identity)"),
            "same-tuple manual stops are de-duplicated instead of retained in FIFO order");
        assertOrdered(removePendingTerminal,
            "this.pendingRecordingTerminals.iterator()",
            "iterator.next() == pending",
            "iterator.remove()",
            "return");
        check(films.contains("private static final int MAX_PENDING_RECORDING_TERMINALS = 32"),
            "pending manual recording terminals are no longer bounded");
        check(films.contains("NONE, CANCELED_BEFORE_START, STOPPED_AFTER_START, STOPPED_AFTER_START_MERGE_BLOCKED"),
            "manual recording terminal outcomes can no longer distinguish cancel, completed stop, and no match");

        check(recorderConstructor.contains("this.initialLevel = Minecraft.getInstance().level")
                && recorderConstructor.contains("this.initialPlayer = Minecraft.getInstance().player"),
            "client recording no longer captures its start-level and LocalPlayer identities");
        assertOrdered(recorderConstructor,
            "this.recordingFilmId = film == null ? null : film.getId()",
            "this.recordingReplayId = replayId",
            "this.recordingTick = tick");
        check(clientRecorder.contains("public boolean matchesRecording(String filmId, int replayId, int tick)"),
            "client recorder no longer owns an immutable exact film/replay/tick identity");
        assertOrdered(recorderLevel,
            "Minecraft client = Minecraft.getInstance()",
            "this.initialLevel != null",
            "this.initialPlayer != null",
            "client.level == this.initialLevel",
            "client.player == this.initialPlayer");
        assertOrdered(filmsUpdate,
            "Recorder recorder = this.recorder",
            "recorder != null && !recorder.isInCurrentLevel()",
            "this.stopRecordingForClientLifecycle(recorder)",
            "return",
            "recorder.update()");
        check(recordedFrame.contains("return this.lastPosition != null"),
            "countdown completion is once again treated as an actually captured recording frame");
        assertOrdered(recorderShutdown,
            "restorePlayer && this.isInCurrentLevel() && pos != null",
            "PlayerUtils.teleport",
            "ClientNetwork.sendPlayerForm",
            "super.shutdown()");

        check(genericStopPacket.contains("Films.stopFilm(filmId)"),
            "generic c5 no longer stops the addressed playback film");
        check(genericStopPacket.contains(
                "executeIfCurrent(client, scope, false, () -> Films.stopFilm(filmId))"),
            "c5 is incorrectly dropped solely because its playback world already unloaded");
        check(!genericStopPacket.contains("stopRecording"),
            "generic c5 can stop an unrelated active recorder");
        assertOrdered(recordedActionsPacket,
            "ServerNetwork.RecordingTerminal terminal = ServerNetwork.RecordingTerminal.LEGACY_MANUAL",
            "filmId = packetByteBuf.readUtf()",
            "replayId = packetByteBuf.readInt()",
            "tick = packetByteBuf.readInt()",
            "packetByteBuf.readableBytes() != 1",
            "ServerNetwork.RecordingTerminal.fromId(packetByteBuf.readUnsignedByte())",
            "terminal == null",
            "Films films = BBSModClient.getFilms()",
            "films.consumeManualRecordingTerminal(filmId, replayId, tick)",
            "manualTerminal == Films.ManualRecordingTerminal.CANCELED_BEFORE_START",
            "Recorder recorder = null",
            "manualTerminal == Films.ManualRecordingTerminal.STOPPED_AFTER_START",
            "manualTerminal == Films.ManualRecordingTerminal.NONE",
            "Recorder candidate = films.getRecorder()",
            "films.stopRecordingFromServer(filmId, replayId, tick)",
            "recorder != null && recorder.isInCurrentLevel()",
            "boolean hasRecordedActions = !data.asList().isEmpty()",
            "boolean startRejected = recordingTerminal == ServerNetwork.RecordingTerminal.START_REJECTED",
            "boolean applyKeyframes = mergeAllowed",
            "!startRejected",
            "recorder.hasRecordedFrame()",
            "boolean mergeActions = mergeAllowed",
            "!startRejected",
            "hasRecordedActions",
            "recordingTerminal != ServerNetwork.RecordingTerminal.SERVER_FORCED",
            "UIDashboard dashboard = BBSModClient.getDashboardIfCreated()",
            "dashboard == null ? null",
            "if (panel != null)",
            "panel.receiveActions(",
            "recordingTerminal");
        check(manualTerminalGate.contains("ManualRecordingTerminal.CANCELED_BEFORE_START")
                && manualTerminalGate.contains("return"),
            "a canceled manual terminal can let an old c7 stop a newly started recorder with the same tuple");
        check(recordedActionsPacket.contains("recordingTerminal != ServerNetwork.RecordingTerminal.SERVER_FORCED")
                && recordedActionsPacket.contains("boolean hasRecordedActions = !data.asList().isEmpty()"),
            "empty c7 cannot distinguish a completed manual stop from a forced countdown terminal");
        check(recordedActionsPacket.contains(
                "boolean startRejected = recordingTerminal == ServerNetwork.RecordingTerminal.START_REJECTED")
                && countOccurrences(recordedActionsPacket, "&& !startRejected") >= 2,
            "s5 start rejection can still apply delayed local keyframes, inventory, or actions");
        check(recordedActionsPacket.contains(
                "manualTerminal == Films.ManualRecordingTerminal.STOPPED_AFTER_START")
                && recordedActionsPacket.contains(
                    "recordingTerminal != ServerNetwork.RecordingTerminal.SERVER_FORCED")
                && recordedActionsPacket.contains("&& !startRejected"),
            "legacy/manual empty c7 no longer preserves explicit recording-range replacement");
        check(!recordedActionsPacket.contains(
                "manualTerminal == Films.ManualRecordingTerminal.STOPPED_AFTER_START_MERGE_BLOCKED ||"),
            "a cross-level manual terminal can still merge server clips");
        check(recordedActionsPacket.contains("candidate != null && films.getRecorder() != candidate ? candidate : null"),
            "c7 loses an exact recorder that detached before local teardown threw");
        assertOrdered(receiveActions,
            "Film film = this.data",
            "film.getId().equals(filmId)",
            "boolean changed = false",
            "recorder.matchesRecording(filmId, replayId, tick)",
            "applyKeyframes && exactRecorder && recorder.hasRecordedFrame()",
            "this.applyRecordedKeyframes(recorder, film)",
            "changed = true",
            "if (mergeTerminalActions)",
            "replay.actions.copyOver(newClips, tick)",
            "if (changed)",
            "this.save()");
        assertOrdered(applyKeyframes,
            "this.applyRecordedKeyframes(recorder, film)",
            "changed = true");
        assertOrdered(mergeActions,
            "BaseValue.edit(",
            "replay.actions.copyOver(newClips, tick)",
            "changed = true");
        check(receiveActions.contains("boolean mergeTerminalActions = mergeActions"),
            "UI c7 handling does not independently fence forced empty terminals");
        check(receiveActions.contains("recordingTerminal != ServerNetwork.RecordingTerminal.START_REJECTED")
                && receiveActions.contains("recordingTerminal == ServerNetwork.RecordingTerminal.LEGACY_MANUAL")
                && receiveActions.contains("clips.asList().isEmpty()"),
            "non-manual empty c7 can still erase existing replay actions");
        check(!receiveActions.contains("stopRecording"),
            "UI availability once again owns c7 recorder teardown");
        check(!receiveActions.contains("sendActionRecording"),
            "c7 recording completion loops a client stop request back to s5");
        assertOrdered(panelOpen,
            "Recorder recorder = BBSModClient.getFilms().stopRecording()",
            "Film film = this.data",
            "Objects.equals(film.getId(), recorder.getRecordingFilmId())",
            "CollectionUtils.inRange(film.replays.getList(), recorder.getRecordingReplayId())",
            "!recorder.hasRecordedFrame()",
            "recorder.isInCurrentLevel()",
            "this.applyRecordedKeyframes(recorder, film)");
    }

    private static void disconnectTearsDownOldFilmsLocally(String bbsClient, String films)
    {
        String disconnect = section(
            bbsClient,
            "public static void onClientDisconnect()",
            "public static void onClientTickPre()"
        );
        String recordingLifecycle = section(
            bbsClient,
            "private static void stopFilmRecordingForLifecycle",
            "private static void saveFilmPanelForLifecycle"
        );
        String keyRecording = section(
            bbsClient,
            "private static void keyRecordReplay()",
            "private static void keyOpenReplays()"
        );
        int resetStart = films.indexOf("public void reset()");

        check(resetStart >= 0, "Films.reset is unavailable to the disconnect lifecycle");

        String reset = films.substring(resetStart);

        assertOrdered(disconnect,
            "films.reset()",
            "films = new Films()");
        check(reset.contains("this.stopRecordingForClientLifecycle(recorder)"),
            "Films.reset drops the active recorder without local shutdown");
        check(reset.contains("controller.shutdown()"),
            "Films.reset drops active controllers without local shutdown");
        check(reset.contains("this.pendingRecordingTerminals.clear()"),
            "disconnect retains stale manual recording terminal tuples");
        check(!reset.contains("ClientNetwork.sendActionRecording"),
            "disconnect teardown sends an s5 recording stop after the connection is gone");
        assertOrdered(recordingLifecycle,
            "owner.stopRecordingForClientLifecycle(recorder)",
            "owner.getRecorder() == recorder",
            "Objects.equals(film.getId(), recorder.getRecordingFilmId())",
            "CollectionUtils.inRange(film.replays.getList(), recorder.getRecordingReplayId())",
            "recorder.hasRecordedFrame()",
            "recorder.isInCurrentLevel()",
            "filmPanel.applyRecordedKeyframes(recorder, film)");
        assertOrdered(keyRecording,
            "BBSModClient.getFilms().stopRecording()",
            "Film film = panel.getData()",
            "Objects.equals(film.getId(), recorder.getRecordingFilmId())",
            "CollectionUtils.inRange(film.replays.getList(), recorder.getRecordingReplayId())",
            "!recorder.hasRecordedFrame()",
            "recorder.isInCurrentLevel()",
            "panel.applyRecordedKeyframes(recorder, film)");
    }

    private static void runtimePresenceIsIdentityBound(String player)
    {
        String apply = section(player, "public void apply(LivingEntity actor", "private boolean applySafely");
        String tick = section(player, "public boolean tick()", "private void applyAction()");
        String applyAction = section(player, "private void applyAction()", "public void syncData");
        String sync = section(player, "public void syncData", "public void goTo(int tick)");
        String seek = section(player, "public void seekTo(int tick)", "public void stop()");
        String runtimeAuthority = section(
            player,
            "private boolean hasRuntimeAuthority(boolean authorityRequired)",
            "private boolean hasCurrentTarget()"
        );
        String currentTarget = section(
            player,
            "private boolean hasCurrentTarget()",
            "private void clearBreakProgress(Replay replay)"
        );

        check(runtimeAuthority.contains(
                "return this.hasCurrentTarget() && FilmActionAuthorityPolicy.hasRuntimeAuthority("),
            "runtime authority can bypass current target presence for a visual or non-admin film");
        assertOrdered(currentTarget,
            "this.serverPlayer == null",
            "this.level == null",
            "this.serverPlayer.serverLevel() != this.level",
            "MinecraftServer server = this.level.getServer()",
            "this.serverPlayer.getServer() == server",
            "server.getPlayerList().getPlayer(this.serverPlayer.getUUID()) == this.serverPlayer");
        check(!runtimeAuthority.contains("PlayerType") && !currentTarget.contains("PlayerType"),
            "TARGETED_COMMAND or RECORDING can bypass exact runtime target presence");

        check(apply.contains("if (!this.hasRuntimeAuthority())"),
            "replay application no longer revalidates exact target presence");
        check(tick.contains("if (!this.hasRuntimeAuthority())"),
            "runtime tick no longer revalidates exact target presence");
        check(sync.contains("this.stopping || !this.hasRuntimeAuthority()"),
            "runtime sync no longer revalidates exact target presence");
        check(seek.contains("this.stopping || !this.hasRuntimeAuthority()"),
            "runtime seek no longer revalidates exact target presence");
        check(applyAction.indexOf("this.hasRuntimeAuthority(authorityRequired)")
                != applyAction.lastIndexOf("this.hasRuntimeAuthority(authorityRequired)"),
            "effectful action playback no longer revalidates presence throughout the clip loop");
    }

    private static void sharedFormConstructionRunsOnClientThread(String clientNetwork)
    {
        String shareForm = section(
            clientNetwork,
            "private static void handleShareFormPacket",
            "private static void handleEntityFormPacket"
        );

        assertOrdered(shareForm,
            "NetworkDataDecoder.decode(bytes)",
            "decoded instanceof MapType",
            "executeIfCurrent(client, scope, true",
            "try",
            "FormUtils.fromData(decoded)",
            "if (form == null)",
            "BBSModClient.getDashboard()",
            "getRecentForms()",
            "addForm(form)",
            "notifyInfo(",
            "catch (RuntimeException | LinkageError exception)");
    }

    private static void serverPlayerFormFactoryRunsAfterGates(String network)
    {
        String playerForm = section(
            network,
            "private static void handlePlayerFormPacket(MinecraftServer server",
            "private static void handleManagerDataPacket"
        );
        String rawDecode = section(playerForm, "crusher.receive(", "server.execute(");

        assertOrdered(rawDecode,
            "consumeCompletedPayload(",
            "packetByteBuf.isReadable()",
            "NetworkDataDecoder.decode(bytes)",
            "decoded instanceof MapType data");
        check(!rawDecode.contains("BBSMod.getForms().fromData")
                && !rawDecode.contains("Morph.getMorph"),
            "s3 constructs or applies a custom Form on the network callback thread");
        assertOrdered(playerForm,
            "server.execute(",
            "!isCurrentConnection(server, player)",
            "!PermissionUtils.arePanelsAllowed(server, player)",
            "try",
            "BBSMod.getForms().fromData(data)",
            "FormUtils.copy(form)",
            "Morph.getMorph(player).setForm(copy)",
            "sendMorphToTracked(player, form)",
            "catch (RuntimeException | LinkageError e)");
    }

    private static void worldBoundClientHandlersUseExactLevel(String clientNetwork)
    {
        String c1 = section(clientNetwork, "private static void handleClientModelBlockPacket", "private static void handlePlayerFormPacket");
        String c2 = section(clientNetwork, "private static void handlePlayerFormPacket", "private static void handlePlayFilmPacket");
        String c3 = section(clientNetwork, "private static void handlePlayFilmPacket", "private static void handleManagerDataPacket");
        String c5 = section(clientNetwork, "private static void handleStopFilmPacket", "private static void handleHandshakePacket");
        String c8 = section(clientNetwork, "private static void handleFormTriggerPacket", "private static void handleCheatsPermissionPacket");
        String c9 = section(clientNetwork, "private static void handleCheatsPermissionPacket", "private static void handleShareFormPacket");
        String c10 = section(clientNetwork, "private static void handleShareFormPacket", "private static void handleEntityFormPacket");
        String c11 = section(clientNetwork, "private static void handleEntityFormPacket", "private static void handleActorsPacket");
        String c12 = section(clientNetwork, "private static void handleActorsPacket", "private static void handleGunPropertiesPacket");
        String c13 = section(clientNetwork, "private static void handleGunPropertiesPacket", "private static void handlePauseFilmPacket");
        String c14 = section(clientNetwork, "private static void handlePauseFilmPacket", "private static void handleSelectedSlotPacket");
        String c15 = section(clientNetwork, "private static void handleSelectedSlotPacket", "private static void handleAnimationStateModelBlockPacket");
        String c16 = section(clientNetwork, "private static void handleAnimationStateModelBlockPacket", "private static void handleRefreshModelBlocksPacket");
        String c17 = section(clientNetwork, "private static void handleRefreshModelBlocksPacket", "private static void handleRequestFilmResync");
        String c19 = section(clientNetwork, "private static void handleRequestFilmResync", "/* API */");

        assertExactLevelGate(c1, "level.getBlockEntity(pos)");
        assertExactLevelGate(c2, "FormUtils.fromData(decoded)");
        assertExactLevelGate(c3, "Film film = new Film()");
        assertExactLevelGate(c8, "level.getEntity(id)");
        assertExactLevelGate(c11, "FormUtils.fromData(decoded)");
        assertExactLevelGate(c12, "BBSModClient.getDashboard()");
        assertExactLevelGate(c13, "level.getEntity(entityId)");
        assertExactLevelGate(c14, "Films.togglePauseFilm(filmId)");
        assertExactLevelGate(c15, "client.player.getInventory().selected = slot");
        assertExactLevelGate(c16, "level.getBlockEntity(pos)");
        assertExactLevelGate(c17, "for (ModelBlockEntity mb : BBSRendering.capturedModelBlocks)");
        assertExactLevelGate(c19, "BBSModClient.getDashboard()");
        assertOrdered(c8,
            "buf.readUtf(NetworkDirectActionGate.MAX_ANIMATION_TRIGGER_LENGTH)",
            "buf.isReadable()",
            "!NetworkDirectActionGate.isAnimationTriggerAllowed(triggerId)",
            "type < ServerNetwork.STATE_TRIGGER_MORPH",
            "type > ServerNetwork.STATE_TRIGGER_OFF_HAND_ITEM",
            "executeIfCurrent(client, scope, true");

        String c2Raw = section(c2, "crusher.receive(", "executeIfCurrent(");
        String c3Raw = section(c3, "crusher.receive(", "executeIfCurrent(");
        String c11Raw = section(c11, "crusher.receive(", "executeIfCurrent(");
        String c13Raw = section(c13, "int entityId = buf.readInt()", "executeIfCurrent(");

        check(!c2Raw.contains("FormUtils.fromData"),
            "c2 constructs an addon/custom player Form before the exact-level client gate");
        check(!c3Raw.contains("new Film()") && !c3Raw.contains("film.fromData"),
            "c3 constructs a Film before the exact-level client gate");
        check(!c11Raw.contains("FormUtils.fromData"),
            "c11 constructs an addon/custom entity Form before the exact-level client gate");
        check(!c13Raw.contains("new GunProperties") && !c13Raw.contains("fromNetwork"),
            "c13 constructs or parses typed GunProperties before the exact-level client gate");
        assertOrdered(c2,
            "if (client.level != level)",
            "try",
            "FormUtils.fromData(decoded)",
            "level.getEntity(id)",
            "morph.setForm(form)",
            "catch (RuntimeException | LinkageError exception)");
        assertOrdered(c3,
            "if (client.level != level)",
            "try",
            "Film film = new Film()",
            "film.fromData(decoded)",
            "Films.playFilm(film, withCamera)",
            "catch (RuntimeException | LinkageError exception)");
        assertOrdered(c11,
            "if (client.level != level)",
            "try",
            "FormUtils.fromData(decoded)",
            "level.getEntity(entityId)",
            "provider.setForm(form)",
            "catch (RuntimeException | LinkageError exception)");
        assertOrdered(c13,
            "if (client.level != level)",
            "FriendlyByteBuf propertiesBuffer = NetworkCompat.createBuffer()",
            "try",
            "GunProperties properties = new GunProperties()",
            "properties.fromNetwork(propertiesBuffer)",
            "level.getEntity(entityId)",
            "projectile.setProperties(properties)",
            "catch (RuntimeException | LinkageError exception)",
            "finally",
            "propertiesBuffer.release()");

        check(!c5.contains("ClientLevel level = client.level")
                && !c9.contains("ClientLevel level = client.level")
                && !c10.contains("ClientLevel level = client.level"),
            "connection/UI-scoped c5, c9, or c10 incorrectly inherited a world-identity gate");
        check(c5.contains("executeIfCurrent(client, scope, false")
                && c9.contains("executeIfCurrent(client, scope, true")
                && c10.contains("executeIfCurrent(client, scope, true"),
            "c5 is not the requireLevel=false exception among stop/permission/share handlers");
    }

    private static void assertExactLevelGate(String handler, String firstMutation)
    {
        assertOrdered(handler,
            "ClientPayloadScope scope",
            "ClientLevel level = client.level",
            "executeIfCurrent(client, scope, true",
            "if (client.level != level)",
            firstMutation);
    }

    private static void dynamicFirstPersonStateUsesIdentityLease(String manager, String player, String lease)
    {
        check(lease.contains("new IdentityHashMap<>()"),
            "first-person state ownership stopped using player-connection identity");
        check(lease.contains("owner != null && owner != this"),
            "a second first-person runtime can replace an existing lease owner");
        check(lease.contains("owners.get(this.target) == this"),
            "first-person lease release no longer verifies exact ownership");

        String playAuthorized = section(manager, "public ActionPlayer playAuthorized(", "private boolean tryTeardown");

        assertOrdered(playAuthorized,
            "initialFirstPersonState && !firstPersonLease.acquire()",
            "new ActionPlayer(",
            "player.initializeFirstPersonState()",
            "this.players.add(player)");
        assertOrdered(playAuthorized,
            "if (!this.tryTeardown(player, \"construct\"))",
            "this.players.add(player)",
            "return null");
        check(playAuthorized.contains("firstPersonLease.release()"),
            "constructor failure no longer releases its provisional first-person lease");

        String applyState = section(player, "private void applyFirstPersonState", "public void updateReplayEntities");

        assertOrdered(applyState,
            "!this.firstPersonLease.acquire()",
            "this.cachedInventory.clear()",
            "this.cachedSelectedSlot = this.serverPlayer.getInventory().selected",
            "this.cachedFoodData = new CompoundTag()",
            "this.serverPlayer.getFoodData().addAdditionalSaveData(this.cachedFoodData)",
            "this.cacheTotalExperience = this.serverPlayer.totalExperience",
            "this.firstPersonStateApplied = true",
            "FirstPersonInventoryProjection.apply(this.serverPlayer, this.film.inventory.getStacks())");
        check(player.contains("this.canApplyFirstPersonState() && this.firstPersonLease.isHeld()"),
            "a first-person replay can bind the real player without owning its lease");
        String constructor = section(player, "ActionPlayer(", "static boolean hasRequiredDeliveryTarget");

        assertOrdered(constructor,
            "FilmPlaybackPolicy.findEnabledFirstPersonReplay(film) != null",
            "initialFirstPersonState && !this.firstPersonLease.isHeld()",
            "First-person playback must be created through ActionManager",
            "this.tryUpdateReplayEntities()");

        String sync = section(player, "public void syncData", "public void goTo");
        String refreshState = section(player, "private void refreshFirstPersonState", "public void updateReplayEntities");

        check(sync.contains("baseValue.getParent() == this.film.replays"),
            "a full Replay replacement no longer refreshes the exact film actor map");
        check(sync.contains("baseValue.getId().equals(\"form\") && baseValue.getParent() instanceof Replay"),
            "a Replay form update no longer refreshes the existing actor projection");
        assertOrdered(sync,
            "baseValue.fromData(data)",
            "this.applyFirstPersonState(nextFirstPerson)",
            "FilmPlaybackPolicy.affectsFirstPersonDisplay(",
            "this.refreshFirstPersonState(nextFirstPerson)",
            "this.tryUpdateReplayEntities()",
            "baseValue.fromData(previous)");
        assertOrdered(sync,
            "baseValue.fromData(previous)",
            "this.refreshFirstPersonState(FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film))",
            "this.requestFullResync()");
        assertOrdered(refreshState,
            "!this.firstPersonStateApplied",
            "!this.firstPersonLease.isHeld()",
            "FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film) != fpReplay",
            "FirstPersonInventoryProjection.apply(this.serverPlayer, this.film.inventory.getStacks())",
            "ServerNetwork.sendMorphToTracked(this.serverPlayer, fpReplay.form.get())",
            "applyFilmPlayerSettingsTo(");
        check(!refreshState.contains("cachedInventory")
                && !refreshState.contains("cachedForm")
                && !refreshState.contains("cachedFoodData")
                && !refreshState.contains("cacheTotalExperience")
                && !refreshState.contains("firstPersonLease.acquire()")
                && !refreshState.contains("firstPersonLease.release()"),
            "first-person display refresh overwrites the original cache or changes lease ownership");
        check(sync.contains("stateWasApplied && firstPersonRefreshAttempted && mutationRolledBack"),
            "a failed first-person refresh can reapply display state before the film mutation rolls back");

        String restore = section(player, "private void restoreFirstPersonState", "private void discardCurrentActors");
        String selectedSlotRestore = section(
            restore,
            "ServerNetwork.sendSelectedSlot(this.serverPlayer, this.cachedSelectedSlot)",
            "float health = this.cacheHp"
        );
        String foodRestore = section(
            restore,
            "this.serverPlayer.getFoodData().readAdditionalSaveData(this.cachedFoodData.copy())",
            "this.serverPlayer.totalExperience = this.cacheTotalExperience"
        );
        String experienceRestore = section(
            restore,
            "this.serverPlayer.totalExperience = this.cacheTotalExperience",
            "if (this.restoreServerForm)"
        );
        String stop = section(player, "public void stop()", "private void requestFullResync");

        assertOrdered(restore,
            "if (!this.firstPersonLease.isHeld())",
            "ServerNetwork.sendSelectedSlot(this.serverPlayer, this.cachedSelectedSlot)",
            "this.serverPlayer.getFoodData().readAdditionalSaveData(this.cachedFoodData.copy())",
            "this.serverPlayer.totalExperience = this.cacheTotalExperience",
            "this.serverPlayer.experienceProgress = this.cacheXpProgress",
            "this.serverPlayer.setExperienceLevels(this.cacheXpLevel)",
            "ActionTeardown.throwIfFailed(failure)",
            "this.firstPersonStateApplied = false",
            "this.cachedInventory.clear()",
            "this.cachedFoodData = null",
            "this.firstPersonLease.release()");
        check(selectedSlotRestore.contains("failure = ActionTeardown.append(failure, e)"),
            "selected hotbar restoration failure no longer keeps teardown retryable");
        check(foodRestore.contains("failure = ActionTeardown.append(failure, e)"),
            "complete food-state restoration failure no longer keeps teardown retryable");
        check(experienceRestore.contains("failure = ActionTeardown.append(failure, e)"),
            "total-experience restoration failure no longer keeps teardown retryable");
        check(stop.contains("this::restoreFirstPersonState"),
            "normal ActionPlayer stop no longer restores first-person player state");
    }

    private static void clonedPlayersRestoreAndReleaseFirstPersonState(
        String bbsMod,
        String manager,
        String player,
        String lease,
        String network
    )
    {
        check(bbsMod.contains("NeoForge.EVENT_BUS.addListener(this::onPlayerClone)")
                && bbsMod.contains("NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn)")
                && bbsMod.contains("NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension)"),
            "common NeoForge player lifecycle listeners are incomplete");

        String cloneHook = section(bbsMod, "private void onPlayerClone", "private void onPlayerRespawn");
        String respawnHook = section(bbsMod, "private void onPlayerRespawn", "private void onPlayerChangedDimension");
        String dimensionHook = section(bbsMod, "private void onPlayerChangedDimension", "private void onStartTracking");

        assertOrdered(cloneHook,
            "event.getOriginal() instanceof ServerPlayer original",
            "event.getEntity() instanceof ServerPlayer replacement",
            "actions.handlePlayerClone(original, replacement)",
            "ServerNetwork.retirePlayerIdentity(original, replacement)");
        check(respawnHook.contains("actions.handlePlayerRespawn(player)"),
            "post-install PlayerRespawnEvent does not retry clone restoration");
        assertOrdered(dimensionHook,
            "actions.handlePlayerChangedDimension(player)",
            "ServerNetwork.retirePlayerIdentity(player, player)");
        check(!cloneHook.contains("net.minecraft.client")
                && !respawnHook.contains("net.minecraft.client")
                && !dimensionHook.contains("net.minecraft.client"),
            "common player lifecycle wiring introduced a physical-client dependency");

        String cloneManager = section(manager, "public void handlePlayerClone", "public void handlePlayerRespawn");
        String respawnManager = section(manager, "public void handlePlayerRespawn", "public void handlePlayerChangedDimension");
        String tickManager = section(manager, "public void tick()", "/* Actions playback */");
        String exactStop = section(
            manager,
            "public boolean stopExact(ActionPlayer candidate, String phase)",
            "public boolean stopExact(ActionPlayer candidate)"
        );
        String disconnectStop = section(manager, "public int stopAll(ServerPlayer owner)", "public void handlePlayerClone");

        assertOrdered(cloneManager,
            "player.requestForcedStop()",
            "player.transferFirstPersonStateToClone(original, replacement)",
            "this.cloneRestoreRetries.put(replacement, player)",
            "continue;",
            "this.stopExact(player, \"clone\")");
        check(cloneManager.contains("player.abandonFirstPersonState()"),
            "a conflicting clone lease does not fail closed");
        assertOrdered(respawnManager,
            "this.cloneRestoreRetries.get(replacement)",
            "pending.requestForcedStop()",
            "this.stopExact(pending, \"respawn\")",
            "phase=respawn_teardown result=retry");
        check(!respawnManager.contains("cloneRestoreRetries.remove(replacement)")
                && !respawnManager.contains("pending.abandonFirstPersonState()")
                && !respawnManager.contains("respawn_fail_closed"),
            "a transient respawn restore failure discards its retry, snapshot, or lease");
        assertOrdered(exactStop,
            "!this.tryTeardown(candidate, phase)",
            "this.clearCloneRestoreRetry(candidate)",
            "this.players.remove(index)");
        assertOrdered(tickManager,
            "!this.tryTeardown(player, \"natural\")",
            "this.clearCloneRestoreRetry(player)",
            "this.notifyStop(player)");
        assertOrdered(disconnectStop,
            "this.stopExact(player, \"disconnect\")",
            "continue;",
            "this.clearCloneRestoreRetry(player)",
            "player.isFirstPersonStateApplied()",
            "player.abandonFirstPersonState()",
            "this.stopExact(player, \"disconnect_terminal\")");

        String transfer = section(player, "boolean transferFirstPersonStateToClone", "void abandonFirstPersonState");
        String abandon = section(player, "void abandonFirstPersonState", "public ServerPlayer getRequester()");

        assertOrdered(transfer,
            "this.serverPlayer != original",
            "replacement.getUUID().equals(original.getUUID())",
            "replacement.connection != original.connection",
            "this.firstPersonLease.transfer(original, replacement)",
            "this.serverPlayer = replacement");
        assertOrdered(abandon,
            "this.cachedInventory.clear()",
            "this.cachedForm = null",
            "this.cachedFoodData = null",
            "this.firstPersonLease.release()");

        String restore = section(player, "private void restoreFirstPersonState", "private void discardCurrentActors");

        assertOrdered(restore,
            "this.serverPlayer.getInventory().setItem",
            "ServerNetwork.sendSelectedSlot(this.serverPlayer, this.cachedSelectedSlot)",
            "this.serverPlayer.setHealth(health)",
            "this.serverPlayer.getFoodData().readAdditionalSaveData(this.cachedFoodData.copy())",
            "this.serverPlayer.totalExperience = this.cacheTotalExperience",
            "this.serverPlayer.setExperienceLevels(this.cacheXpLevel)",
            "morph.setForm(FormUtils.copy(this.cachedForm))",
            "ServerNetwork.sendMorphToTracked(this.serverPlayer, this.cachedForm)",
            "this.firstPersonLease.release()");

        check(lease.contains("boolean transfer(T expectedTarget, T replacementTarget)"),
            "the first-person lease cannot move across a ServerPlayer clone");
        String leaseTransfer = section(lease, "Lease<T> replacementOwner", "public void release()");
        check(leaseTransfer.contains("replacementOwner != null && replacementOwner != this")
                && leaseTransfer.contains("owners.remove(this.target)")
                && leaseTransfer.contains("owners.put(this.target, this)"),
            "clone lease transfer is not atomic or can steal a replacement owner");

        String retire = section(network, "public static void retirePlayerIdentity", "/* Handlers */");

        assertOrdered(retire,
            "mutationSessions.clearOwner(playerId, retired)",
            "trySendStopFilm(observer, filmId)");
        check(retire.contains("replacement.connection != retired.connection")
                && retire.contains("trySendStopFilm(replacement, filmId)"),
            "retired identity cleanup is not fenced to the exact clone connection or misses the replacement client");
    }

    private static void resetRetainsFailedRuntimeForRetry(String manager)
    {
        String reset = section(manager, "public void reset()", "public void tick()");

        assertOrdered(reset,
            "boolean playerTeardownFailed = false",
            "player.stop()",
            "this.players.remove(index)",
            "playerTeardownFailed = true",
            "if (this.indexOfPlayerIdentity(player) < 0)",
            "this.players.add(player)",
            "if (!playerTeardownFailed && this.players.isEmpty())",
            "this.firstPersonLeases.clear()");
        check(!reset.contains("this.players.clear()"),
            "reset discarded failed ActionPlayers instead of retaining them for retry");
    }

    private static void revocationUsesExpectedServerAndTypedStopRoute(
        String manager,
        String player,
        String policy,
        String playerType,
        String network
    )
    {
        check(policy.contains("isRequesterAuthorized(@Nullable ServerPlayer requester, @Nullable MinecraftServer expectedServer)"),
            "runtime requester validation has no expected-server boundary");
        check(manager.contains("FilmActionAuthorityPolicy.isRequesterAuthorized(requester, level.getServer())"),
            "ActionManager preflight accepts an administrator from another server");
        check(player.contains("FilmActionAuthorityPolicy.isRequesterAuthorized(this.requester, this.level.getServer())"),
            "ActionPlayer dynamic revalidation accepts an administrator from another server");
        check(network.contains("AuthorizedCommandExecutor.isAuthorized(requester, player.getServer())"),
            "targeted network entry no longer binds issuer authority to the target server");

        check(playerType.contains("this == NORMAL || this == TARGETED_COMMAND || (this == FILM_EDITOR && forced)"),
            "the c5 completion/revocation type matrix changed");
        check(PlayerType.NORMAL.shouldSendStopNotification(false)
                && PlayerType.NORMAL.shouldSendStopNotification(true),
            "NORMAL no longer sends c5 for natural and forced completion");
        check(PlayerType.TARGETED_COMMAND.shouldSendStopNotification(false)
                && PlayerType.TARGETED_COMMAND.shouldSendStopNotification(true),
            "TARGETED_COMMAND no longer sends owner-local c5 for natural and forced completion");
        check(!PlayerType.FILM_EDITOR.shouldSendStopNotification(false)
                && PlayerType.FILM_EDITOR.shouldSendStopNotification(true),
            "FILM_EDITOR no longer limits c5 to forced teardown");
        check(!PlayerType.RECORDING.shouldSendStopNotification(false)
                && !PlayerType.RECORDING.shouldSendStopNotification(true),
            "RECORDING entered the generic c5 notification matrix");
        check(manager.contains("player.type.shouldSendStopNotification(player.isForcedStop())"),
            "ActionManager no longer applies the typed c5 stop matrix");
        check(manager.contains("player.type == PlayerType.TARGETED_COMMAND")
                && manager.contains("player.type == PlayerType.NORMAL")
                && manager.contains("player.type == PlayerType.FILM_EDITOR"),
            "ActionManager lost a targeted, broadcast, or forced-editor c5 route");
    }

    private static String read(Path root, Path file) throws IOException
    {
        return compact(Files.readString(root.resolve(file)));
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
            if (Files.isRegularFile(current.resolve(ACTION_MANAGER)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(ACTION_MANAGER)))
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
