package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for ordering and ownership at film runtime entry points. */
final class ServerFilmRuntimeAuthoritySourceTest
{
    private static final Path SOURCE = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );
    private static final Path ACTION_MANAGER = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/ActionManager.java"
    );

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ServerFilmRuntimeAuthoritySourceTest passed");
    }

    static void runAll()
    {
        try
        {
            String source = Files.readString(findProjectRoot().resolve(SOURCE))
                .replace("\r\n", "\n");
            String manager = Files.readString(findProjectRoot().resolve(ACTION_MANAGER))
                .replace("\r\n", "\n");

            clientReachableLoadsUseRawPreflight(source);
            playbackRejectsBeforeSerialization(source);
            failedFirstPersonStartRetainsClaimForRecovery(source, manager);
            stopRequiresExactRuntimeOrSession(source);
            recordingTerminalReleasesBothLeases(source);
            pauseAdmissionPrecedesCanonicalization(source);
            restartUsesExactRuntimeTransactions(source, manager);
            forcedRecordingTerminalIsOptional(source, manager);
            zoomGunFactoryFailureFailsClosed(source);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect ServerNetwork authority wiring", e);
        }
    }

    private static void clientReachableLoadsUseRawPreflight(String source)
    {
        String manager = method(source,
            "private static void handleManagerDataPacket",
            "private static void handleActionRecording");
        String load = manager.substring(
            manager.indexOf("if (op == RepositoryOperation.LOAD)"),
            manager.indexOf("else if (op == RepositoryOperation.SAVE)")
        );
        String recording = method(source,
            "private static void handleActionRecording",
            "private static void handleToggleFilm");
        String control = method(source,
            "private static void handleActionControl",
            "private static void handleSyncData");
        String normal = method(source,
            "public static void sendPlayFilm(ServerPlayer player, ServerLevel world",
            "public static void sendPlayFilm(ServerPlayer player, String filmId");

        assertOrdered(load,
            "FilmActionAuthorityPolicy.isRequesterAuthorized(player, server)",
            "FilmActionAuthorityPolicy.loadFilmForRequester(films, id, requesterAuthorized)",
            "sendManagerData");
        assertOrdered(recording,
            "FilmActionAuthorityPolicy.isRequesterAuthorized(player, server)",
            "FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)",
            "NetworkMutationPolicy.isRecordingStartAllowed");
        assertOrdered(control,
            "FilmActionAuthorityPolicy.isRequesterAuthorized(player, server)",
            "FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)",
            "NetworkMutationPolicy.isFilmTickAllowed");
        assertOrdered(normal,
            "AuthorizedCommandExecutor.isAuthorized(player, world.getServer())",
            "FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)",
            "FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized");

        check(!load.contains("films.load("), "s4 LOAD bypassed the raw Film preflight");
        check(!recording.contains("films.load("), "s5 recording start bypassed the raw Film preflight");
        check(!control.contains("films.load("), "s7 RESTART bypassed the raw Film preflight");
        check(!normal.contains("films.load("), "s6 NORMAL playback bypassed the raw Film preflight");
    }

    private static void playbackRejectsBeforeSerialization(String source)
    {
        String normal = method(source,
            "public static void sendPlayFilm(ServerPlayer player, ServerLevel world",
            "public static void sendPlayFilm(ServerPlayer player, String filmId");
        String targeted = method(source,
            "public static void sendPlayFilm(\n        ServerPlayer player,\n        @Nullable ServerPlayer requester",
            "public static boolean stopFilmForPlayer");

        assertOrdered(normal,
            "actions.getPlayer(canonicalId)",
            "mutationSessions.claimFilm(canonicalId, owner, player, dimension)",
            "AuthorizedCommandExecutor.isAuthorized(player, world.getServer())",
            "FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)",
            "FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized",
            "FilmPlaybackPolicy.isPlaybackAllowed(film, player.getMaxHealth(), appliesFirstPersonState)",
            "BaseType data = film.toData()",
            "startedRuntime = actions.play",
            "if (startedRuntime == null)",
            "crusher.send(",
            "actions.stopExact(startedRuntime, \"film_play_rollback\")");
        check(normal.indexOf("actions.getPlayer(canonicalId)") < normal.indexOf("BaseType data = film.toData()"),
            "normal playback serialized before duplicate-runtime rejection");
        check(normal.indexOf("mutationSessions.claimFilm") < normal.indexOf("FilmActionAuthorityPolicy.loadFilmForRequester"),
            "normal playback loaded typed film data before reserving its bounded session");

        assertOrdered(targeted,
            "actions.getPlayer(canonicalId, player)",
            "AuthorizedCommandExecutor.isAuthorized(requester, player.getServer())",
            "FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)",
            "FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized",
            "FilmPlaybackPolicy.isPlaybackAllowed(film, player.getMaxHealth(), allowFirstPersonState)",
            "BaseType data = film.toData()",
            "ActionPlayer actionPlayer = actions.playAuthorized(",
            "if (actionPlayer == null)",
            "crusher.send(",
            "actions.stopExact(actionPlayer, \"targeted_play_rollback\")");
        check(targeted.indexOf("actions.getPlayer(canonicalId, player)") < targeted.indexOf("BaseType data = film.toData()"),
            "targeted playback serialized before exact-target runtime rejection");
        check(!targeted.contains("films.load("),
            "targeted playback bypassed raw action preflight");
    }

    private static void failedFirstPersonStartRetainsClaimForRecovery(String source, String manager)
    {
        String start = method(manager,
            "public ActionPlayer playAuthorized(",
            "public ActionPlayer replaceFilmEditorExact");
        String normal = method(source,
            "public static void sendPlayFilm(ServerPlayer player, ServerLevel world",
            "public static void sendPlayFilm(ServerPlayer player, String filmId");
        String tick = method(manager,
            "public void tick()",
            "/* Actions playback */");

        assertOrdered(start,
            "player.initializeFirstPersonState()",
            "if (!this.tryTeardown(player, \"construct\"))",
            "this.players.add(player)",
            "return null");
        assertOrdered(normal,
            "boolean recoveryPending = false",
            "startedRuntime = actions.play",
            "if (startedRuntime == null)",
            "crusher.send(",
            "if (!committed)",
            "if (startedRuntime == null)",
            "startedRuntime = actions.getPlayer(canonicalId, player)",
            "recoveryPending = startedRuntime != null",
            "boolean canRelease = !recoveryPending",
            "actions.stopExact(startedRuntime, \"film_play_rollback\")",
            "if (canRelease && !hadFilmClaim)",
            "mutationSessions.releaseFilm(canonicalId, owner, player)");
        check(normal.contains("&& (startedRuntime == null || actions.stopExact"),
            "a retained recovery runtime can still release its film claim before exact teardown");
        check(normal.indexOf("if (startedRuntime == null)") < normal.indexOf("crusher.send("),
            "a recovery-only runtime can still be announced as normal playback");
        assertOrdered(tick,
            "this.tryTeardown(player, \"natural\")",
            "ServerNetwork.releaseFilmSession(player.film.getId(), player.getServerPlayer())",
            "this.players.remove(index)");
    }

    private static void stopRequiresExactRuntimeOrSession(String source)
    {
        String stop = method(source,
            "public static boolean stopFilmForPlayer",
            "public static void sendStopFilm");
        int missingOwnershipGuard = stop.indexOf("if (runtime == null");
        int firstDelivery = stop.indexOf("trySendStopFilm");

        check(missingOwnershipGuard >= 0 && firstDelivery > missingOwnershipGuard,
            "a missing exact runtime/session can still send a target-local stop");
        check(stop.contains("!targetedRuntime && !ownsFilm"),
            "a non-targeted runtime can be stopped without its mutation lease");
        check(stop.contains("recordingRuntime == null")
                && stop.contains("runtime == null")
                && stop.contains("anyRuntime == null")
                && stop.contains("finishRecordingTerminal("),
            "the recorder-only terminal is not pinned to exact recorder/runtime absence");
        check(stop.contains("targetedRuntime && (ownsFilm || sessionMatches)"),
            "ambiguous targeted and shared ownership does not fail closed");
        int exactStop = stop.indexOf("actions.stopExact(runtime, \"network_stop\")");
        int finalRelease = stop.lastIndexOf("mutationSessions.releaseFilm(canonicalId, owner, player)");
        int finalDelivery = stop.lastIndexOf("trySendStopFilm");

        check(exactStop >= 0 && finalRelease > exactStop && finalDelivery > finalRelease,
            "shared runtime release/delivery no longer follows exact identity teardown");
        check(!stop.contains("actions.stop(canonicalId, player)"),
            "film-id teardown can remove a newer same-id runtime");
    }

    private static void recordingTerminalReleasesBothLeases(String source)
    {
        String release = method(source,
            "public static boolean finishRecordingTerminal",
            "public static boolean sendRecordedActionsForActiveRecording");

        assertOrdered(release,
            "actions.prepareRecordingTerminalExact(",
            "sendRecordedActionsForActiveRecording(",
            "recorder.markTerminalDelivered()",
            "mutationSessions.releaseRecording(owner, player)",
            "mutationSessions.releaseFilm(canonicalId, owner, player)",
            "actions.commitRecordingTerminalExact(player, recorder)");
        check(release.contains("mutationSessions.releaseRecording(owner, player)"),
            "recording terminal does not release its recording lease");
        check(release.contains("mutationSessions.releaseFilm(canonicalId, owner, player)"),
            "recording terminal does not release its film lease");
    }

    private static void pauseAdmissionPrecedesCanonicalization(String source)
    {
        String pause = method(source,
            "private static void handlePauseFilmPacket",
            "private static void handleApplyFilmPlayerSettings");

        assertOrdered(pause,
            "buf.readUtf(256)",
            "buf.isReadable()",
            "server.execute(",
            "|| !PermissionUtils.arePanelsAllowed(server, player)",
            "directActionGate.tryAcquire(",
            "NetworkDirectActionGate.Channel.PAUSE_FILM",
            "canonicalFilmId(BBSMod.getFilms(), filmId)",
            "canMutateFilm(player, canonicalId, actionPlayer)",
            "logFilmMutationRejected(player, canonicalId, \"pause\")");
        check(pause.indexOf("directActionGate.tryAcquire(")
                < pause.indexOf("canonicalFilmId(BBSMod.getFilms(), filmId)"),
            "invalid pause ids bypass the exact-connection admission budget");
    }

    private static void restartUsesExactRuntimeTransactions(String source, String manager)
    {
        String control = method(source,
            "private static void handleActionControl",
            "private static void handleSyncData");
        String replacement = method(manager,
            "public ActionPlayer replaceFilmEditorExact",
            "public boolean stopExact(ActionPlayer candidate, String phase)");
        String exactStop = method(manager,
            "public boolean stopExact(ActionPlayer candidate, String phase)",
            "public boolean stopExact(ActionPlayer candidate)");

        assertOrdered(control,
            "NetworkDirectActionGate.Channel.FILM_START",
            "mutationSessions.claimFilm(",
            "FilmActionAuthorityPolicy.loadFilmForRequester",
            "actions.replaceFilmEditorExact(",
            "restartRuntime = actions.play",
            "actionPlayer.tryResendActors()");
        int newRuntime = control.indexOf("restartRuntime = actions.play");
        int newRuntimeStopPacket = control.lastIndexOf("sendStopFilm(player, canonicalId)");
        int rollback = control.indexOf("actions.stopExact(restartRuntime, \"restart_rollback\")");
        int release = control.lastIndexOf("mutationSessions.releaseFilm(canonicalId, owner, player)");

        check(newRuntime >= 0
                && newRuntimeStopPacket > newRuntime
                && rollback > newRuntimeStopPacket
                && release > rollback,
            "inactive restart no longer rolls back its exact runtime before releasing the claim");
        assertOrdered(replacement,
            "replacement = this.play",
            "beforeCommit.accept(replacement)",
            "replacement.tryResendActors()",
            "this.stopExact(expected, \"replace\")",
            "expectedStopped = true",
            "replacement.resendActors()",
            "committed = true",
            "this.stopExact(replacement, \"replace_rollback\")",
            "if (!expectedStopped)",
            "expected.resendActors()");
        assertOrdered(exactStop,
            "this.indexOfPlayerIdentity(candidate) < 0",
            "candidate.type == PlayerType.RECORDING",
            "ServerNetwork.finishRecordingTerminal(",
            "this.tryTeardown(candidate, phase)",
            "int index = this.indexOfPlayerIdentity(candidate)",
            "this.players.remove(index)");
        check(!control.contains("actions.stop(canonicalId, player)"),
            "restart rollback can remove a newer same-id runtime");
    }

    private static void forcedRecordingTerminalIsOptional(String source, String manager)
    {
        check(source.contains("LEGACY_MANUAL(0)") && source.contains("SERVER_FORCED(1)"),
            "c7 recording terminal ids drifted");
        check(source.contains("if (terminal != RecordingTerminal.LEGACY_MANUAL)")
                && source.contains("packetByteBuf.writeByte(terminal.id())"),
            "legacy c7 footer is no longer preserved while forced terminals are marked");
        check(manager.contains("ServerNetwork.RecordingTerminal.SERVER_FORCED"),
            "forced recording teardown no longer emits its explicit c7 terminal marker");
    }

    private static void zoomGunFactoryFailureFailsClosed(String source)
    {
        String zoom = method(source,
            "private static void handleZoomPacket",
            "private static void handlePauseFilmPacket");

        assertOrdered(zoom,
            "isCurrentConnection(server, player)",
            "ItemStack main = player.getMainHandItem()",
            "try",
            "properties = GunProperties.get(main)",
            "catch (RuntimeException | LinkageError e)",
            "return",
            "GunPropertiesPolicy.isAllowed(properties)",
            "zoomSessions.turnOn");
    }

    private static String method(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start,
            "could not locate ServerNetwork method boundaries: " + startMarker);

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, previous + 1);

            check(next > previous, "authority/playback ordering drifted at: " + marker);
            previous = next;
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

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
