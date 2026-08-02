package mchorse.bbs_mod.ui.film;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for Replay first-person edits reaching the live server runtime. */
public final class FilmReplayFirstPersonSyncSourceTest
{
    private static final Path FILM_UNDO_HANDLER = Path.of("src/client/java/mchorse/bbs_mod/ui/film/utils/UIFilmUndoHandler.java");
    private static final Path REPLAY = Path.of("src/main/java/mchorse/bbs_mod/film/replays/Replay.java");
    private static final Path CLIENT_NETWORK = Path.of("src/client/java/mchorse/bbs_mod/network/ClientNetwork.java");
    private static final Path SERVER_NETWORK = Path.of("src/main/java/mchorse/bbs_mod/network/ServerNetwork.java");
    private static final Path ACTION_PLAYER = Path.of("src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java");
    private static final Path BASE_FILM_CONTROLLER = Path.of("src/client/java/mchorse/bbs_mod/film/BaseFilmController.java");
    private static final Path REPLAY_KEYFRAMES = Path.of("src/main/java/mchorse/bbs_mod/film/replays/ReplayKeyframes.java");

    private FilmReplayFirstPersonSyncSourceTest()
    {}

    public static void main(String[] args) throws IOException
    {
        runAll();

        System.out.println("FilmReplayFirstPersonSyncSourceTest passed");
    }

    public static void runAll() throws IOException
    {
        Path root = findProjectRoot();
        String undo = compact(Files.readString(root.resolve(FILM_UNDO_HANDLER)));
        String replay = compact(Files.readString(root.resolve(REPLAY)));
        String clientNetwork = compact(Files.readString(root.resolve(CLIENT_NETWORK)));
        String serverNetwork = compact(Files.readString(root.resolve(SERVER_NETWORK)));
        String actionPlayer = compact(Files.readString(root.resolve(ACTION_PLAYER)));
        String baseFilmController = compact(Files.readString(root.resolve(BASE_FILM_CONTROLLER)));
        String replayKeyframes = compact(Files.readString(root.resolve(REPLAY_KEYFRAMES)));

        check(replay.contains("new ValueBoolean(\"fp\", false)"),
            "Replay no longer exposes the first-person value used by the editor");
        check(undo.contains("path.endsWith(\"/fp\")"),
            "first-person Replay edits no longer enter the live action sync path");
        assertOrdered(undo,
            "if (this.isReplayActions(value))",
            "this.syncData.add(value)",
            "this.actionsTimer.mark()",
            "ClientNetwork.sendSyncData");
        assertOrdered(clientNetwork,
            "DataPath path = data.getPath()",
            "packetByteBuf.writeInt(path.strings.size())",
            "for (String string : path.strings)");
        assertOrdered(serverNetwork,
            "BaseType data = NetworkDataDecoder.decode(bytes)",
            "actionPlayer.syncData(new DataPath(path), data)");
        check(replayKeyframes.contains("public static final double GRAVITY_PROBE = 0.0784D"),
            "replay playback lost the vanilla floor probe constant");
        assertOrdered(actionPlayer,
            "boolean grounded = replay.keyframes.grounded.interpolate(tick) > 0",
            "grounded ? ReplayKeyframes.GRAVITY_PROBE : 0D",
            "actor.move(MoverType.SELF",
            "actor.setOnGround(grounded)",
            "actor.setSprinting(replay.keyframes.sprinting.interpolate(tick) > 0)");
        assertOrdered(baseFilmController,
            "grounded ? ReplayKeyframes.GRAVITY_PROBE : 0D",
            "player.move(MoverType.SELF",
            "player.setOnGround(grounded)",
            "player.setSprinting(replay.keyframes.sprinting.interpolate(replayTick) > 0)");
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(FILM_UNDO_HANDLER)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(FILM_UNDO_HANDLER)))
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

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
