package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source-level regression for the s4 FILM_META metadata pipeline: the operation
 * must stay last in the wire enum, the server branch must read through the raw
 * (factory-free) boundary, and the description must be truncated to keep the
 * batched payload bounded.
 */
final class ServerFilmMetaSourceTest
{
    private static final Path SERVER_NETWORK = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );
    private static final Path REPOSITORY_OPERATION = Path.of(
        "src/main/java/mchorse/bbs_mod/utils/repos/RepositoryOperation.java"
    );
    private static final Path FILM = Path.of(
        "src/main/java/mchorse/bbs_mod/film/Film.java"
    );
    private static final Path CLIENT_NETWORK = Path.of(
        "src/client/java/mchorse/bbs_mod/network/ClientNetwork.java"
    );

    private ServerFilmMetaSourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ServerFilmMetaSourceTest passed");
    }

    static void runAll()
    {
        try
        {
            Path root = findProjectRoot();

            checkEnumAppendix(Files.readString(root.resolve(REPOSITORY_OPERATION)));
            checkFilmTimestampContract(Files.readString(root.resolve(FILM)));

            String source = Files.readString(root.resolve(SERVER_NETWORK));

            checkHandlerBranch(source);
            checkMetadataUsesRawBoundary(source);
            checkDescriptionTruncation(source);
            checkClientEntry(Files.readString(root.resolve(CLIENT_NETWORK)));
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect the FILM_META pipeline", e);
        }
    }

    private static void checkEnumAppendix(String source)
    {
        String compact = compact(source);

        check(compact.contains("DELETE_FOLDER, FILM_META;"),
            "FILM_META must be appended after DELETE_FOLDER without reordering existing entries");
        check(!compact.contains("FILM_META, LOAD") && !compact.contains("FILM_META, SAVE"),
            "FILM_META must not precede existing RepositoryOperation entries (ordinals are wire ids)");
    }

    private static void checkFilmTimestampContract(String source)
    {
        check(source.contains("new ValueString(\"updated_at\", \"\")"),
            "Film lost its updated_at field");
        check(source.contains("public void stampUpdatedTimeNow()"),
            "Film lost its stampUpdatedTimeNow stamping helper");
        check(source.contains("public static Instant parseTimestamp(String iso)"),
            "Film lost its parseTimestamp helper");
    }

    private static void checkHandlerBranch(String source)
    {
        String manager = method(source,
            "private static void handleManagerDataPacket",
            "private static void handleActionRecording");

        check(manager.contains("op == RepositoryOperation.FILM_META"),
            "the s4 handler lost its FILM_META branch");
        check(manager.indexOf("op == RepositoryOperation.KEYS") < manager.indexOf("op == RepositoryOperation.FILM_META"),
            "the FILM_META branch must follow the existing KEYS branch");
    }

    private static void checkMetadataUsesRawBoundary(String source)
    {
        String metadata = method(source,
            "private static void sendFilmMetaData",
            "private static MapType filmMetaData");

        check(metadata.contains("films.loadRaw(id)"),
            "FILM_META must read films through loadRaw, not create");
        check(!metadata.contains("films.create(") && !metadata.contains("films.load("),
            "FILM_META must not invoke typed Film construction or addon clip factories");

        String helper = method(source,
            "private static MapType filmMetaData",
            "private static int rawCameraDuration");

        check(helper.contains("meta.putString(\"id\", id)")
                && helper.contains("meta.putString(\"created_at\"")
                && helper.contains("meta.putString(\"updated_at\"")
                && helper.contains("meta.putString(\"description\"")
                && helper.contains("meta.putInt(\"duration\""),
            "the FILM_META entry lost one of its payload fields");

        String duration = method(source,
            "private static int rawCameraDuration",
            "private static void sendRecordingStartRejected");

        check(duration.contains("raw.get(\"camera\")") && duration.contains("clip.getInt(\"tick\")") && duration.contains("clip.getInt(\"duration\")"),
            "the raw camera duration must read tick/duration leaves from the camera list");

        /* Per-film failures must stay bounded: exactly one aggregate warn. */
        check(metadata.contains("skipped += 1") && metadata.contains("result=partial"),
            "per-film loadRaw failures must be skipped and counted, not logged per film");
    }

    private static void checkDescriptionTruncation(String source)
    {
        String helper = method(source,
            "private static MapType filmMetaData",
            "private static int rawCameraDuration");

        check(helper.contains("FILM_META_MAX_DESCRIPTION_CHARS")
                && helper.contains("description.substring(0, FILM_META_MAX_DESCRIPTION_CHARS)"),
            "the FILM_META description must be truncated for payload bounding");
    }

    private static void checkClientEntry(String source)
    {
        String compact = compact(source);

        check(compact.contains("public static void requestFilmMeta(Consumer<List<MapType>> consumer)"),
            "ClientNetwork lost its requestFilmMeta entry point");
        check(compact.contains("ClientNetwork.sendManagerData(RepositoryOperation.FILM_META,"),
            "requestFilmMeta must reuse the callback registry send path");
    }

    private static String method(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start,
            "could not locate ServerNetwork method boundaries: " + startMarker);

        return source.substring(start, end);
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ");
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(SERVER_NETWORK))
                && Files.isRegularFile(current.resolve(CLIENT_NETWORK)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the project root");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
