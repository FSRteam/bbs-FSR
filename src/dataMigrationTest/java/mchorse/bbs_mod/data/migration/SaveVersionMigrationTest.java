package mchorse.bbs_mod.data.migration;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.test.ExpectedErrorLogCapture;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.manager.BaseManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The save-format version mechanism, exercised through the real {@link BaseManager} on a real
 * temporary folder: nothing here stubs the boundary the feature lives on.
 *
 * <p>Two halves. The value half is the pure {@link SaveVersion} contract — a versionless document
 * reads as {@link SaveVersion#LEGACY}, a stamped one reads back as {@link SaveVersion#CURRENT},
 * and stamping is idempotent. The behaviour half is the pair that makes the version mean
 * something: a document written by this build still opens, and a document from a <em>newer</em>
 * build is refused instead of being read and then stripped on the next save.</p>
 *
 * <p>The refusal is a moving boundary, and the test is written against
 * {@link SaveVersion#CURRENT} rather than against the literal {@code 1} so that bumping the
 * version for a later migration step keeps this test meaningful instead of rewriting it.</p>
 */
public final class SaveVersionMigrationTest
{
    private SaveVersionMigrationTest()
    {}

    public static void main(String[] args) throws Exception
    {
        theVersionKeyIsTheDocumentBoundaryOne();
        stampingAndReadingRoundTrip();
        aMissingKeyReadsAsLegacy();
        theDefaultLadderIsEmpty();
        aLegacyDocumentStillOpens();
        aDocumentOfThisFormatStillOpens();
        aNewerFormatIsRefusedAndLeftUntouched();
        loadingNeverStampsTheDocument();
        savingStampsTheDocumentOnDisk();
        theUnknownKeyframeFactorySpeaksUpOnBothEnds();
        FilmStableIdsMigrationTest.runAll();

        System.out.println("SaveVersionMigrationTest: all tests passed");
    }

    /** The key is the file's contract with every other build, so it is pinned by name. */
    private static void theVersionKeyIsTheDocumentBoundaryOne()
    {
        require("bbs_version".equals(SaveVersion.KEY),
            "the version key was renamed: every file ever written by this build carries \"" + SaveVersion.KEY + "\"");
        require(SaveVersion.CURRENT >= 1,
            "the current format version went below 1, which would make versioning itself unrepresentable");
        require(SaveVersion.LEGACY == 0,
            "the legacy version is no longer 0: a document without the key reads as " + SaveVersion.LEGACY);
    }

    private static void stampingAndReadingRoundTrip()
    {
        MapType data = new MapType();

        require(SaveVersion.read(data) == SaveVersion.LEGACY,
            "a map stamped under a different key is not read as legacy");

        SaveVersion.stamp(data);

        require(SaveVersion.read(data) == SaveVersion.CURRENT,
            "a stamped document does not read back as the version this build writes: got "
                + SaveVersion.read(data) + ", want " + SaveVersion.CURRENT);
        require(data.getInt(SaveVersion.KEY) == SaveVersion.CURRENT,
            "stamping did not put the version under \"" + SaveVersion.KEY + "\"");

        SaveVersion.stamp(data);

        require(data.getInt(SaveVersion.KEY) == SaveVersion.CURRENT,
            "stamping twice changed the version");

        /* The null guards are part of the contract: a missing file hands a null map to the ladder. */
        SaveVersion.stamp(null);
        require(SaveVersion.read(null) == SaveVersion.LEGACY,
            "reading a null document is not legacy");
    }

    private static void aMissingKeyReadsAsLegacy()
    {
        MapType data = new MapType();

        data.putString("model", "some/model.json");

        require(SaveVersion.read(data) == SaveVersion.LEGACY,
            "a document without \"" + SaveVersion.KEY + "\" is not legacy");
    }

    /**
     * S1 ships versioning and no migration yet, so the ladder the base class hands out has to be
     * empty: a document then walks past nothing and is read exactly as before. A ladder that
     * returned a step here would rewrite documents that nothing has said need rewriting.
     */
    private static void theDefaultLadderIsEmpty()
    {
        require(new TestManager(temporaryFolder()).ladder().isEmpty(),
            "the base manager hands out a non-empty migration ladder in a build that ships no migration");
    }

    private static void aLegacyDocumentStillOpens() throws IOException
    {
        TestManager manager = new TestManager(temporaryFolder());
        MapType data = document();

        manager.writeRaw("legacy", data);

        require(manager.load("legacy") != null,
            "a versionless document written before versioning existed no longer opens");
    }

    private static void aDocumentOfThisFormatStillOpens() throws IOException
    {
        TestManager manager = new TestManager(temporaryFolder());
        MapType data = document();

        data.putInt(SaveVersion.KEY, SaveVersion.CURRENT);

        manager.writeRaw("current", data);

        require(manager.load("current") != null,
            "a document written by this build's own format version does not open");
    }

    /**
     * The refusal. Handing the document to {@code fromData} would drop the keys this build does
     * not know, and the next save would write that truncated shape over the user's file — so the
     * load refuses, and the file on disk keeps its newer content.
     */
    private static void aNewerFormatIsRefusedAndLeftUntouched() throws IOException
    {
        TestManager manager = new TestManager(temporaryFolder());
        MapType data = document();

        data.putInt(SaveVersion.KEY, SaveVersion.CURRENT + 1);
        data.putString("a_key_from_the_future", "keep me");

        manager.writeRaw("future", data);

        MapType before = manager.readRaw("future");

        try (ExpectedErrorLogCapture capture = ExpectedErrorLogCapture.install("newer format refusal", 1,
            (event) ->
            {
                String message = event.getMessage().getFormattedMessage();

                return message.startsWith("Refusing to load \"future\"")
                    && message.contains("format " + (SaveVersion.CURRENT + 1));
            },
            "", "mchorse.bbs_mod.utils.manager.BaseManager"))
        {
            require(manager.load("future") == null,
                "a document from a newer build was opened: this build would drop keys it does not know "
                    + "and write the truncated document back over the file");

            capture.assertExpectedErrors();
        }

        MapType after = manager.readRaw("future");

        require(SaveVersion.read(after) == SaveVersion.CURRENT + 1,
            "the refused document was rewritten with this build's version");
        require("keep me".equals(after.getString("a_key_from_the_future")),
            "the refused document lost a key this build does not know: opening a newer file must leave it alone");
        require(before.getString("a_key_from_the_future").equals(after.getString("a_key_from_the_future")),
            "the refused document changed on disk");
    }

    /** Versioning is written on the way out only; a read must not rewrite the document it read. */
    private static void loadingNeverStampsTheDocument() throws IOException
    {
        TestManager manager = new TestManager(temporaryFolder());

        manager.writeRaw("untouched", document());
        manager.load("untouched");

        require(SaveVersion.read(manager.readRaw("untouched")) == SaveVersion.LEGACY,
            "loading a legacy document stamped it with the current version: reads must not migrate the file");
    }

    /** The observable half of the whole feature: what is saved carries the version. */
    private static void savingStampsTheDocumentOnDisk() throws IOException
    {
        TestManager manager = new TestManager(temporaryFolder());

        require(manager.save("saved", document()),
            "a plain save into a temporary folder failed");

        require(SaveVersion.read(manager.readRaw("saved")) == SaveVersion.CURRENT,
            "the saved file does not carry the format version this build writes");
    }

    /**
     * The loud half of the same upstream change: a keyframe channel whose value type is not in the
     * registry must say so — it used to be assigned as-is, leaving the channel on a null factory
     * whose first keyframe read threw into whichever caller swallowed it.
     */
    private static void theUnknownKeyframeFactorySpeaksUpOnBothEnds()
    {
        String read = captureErr(() ->
        {
            KeyframeChannel<String> channel = new KeyframeChannel<>("unknown_channel", KeyframeFactories.STRING);
            MapType data = new MapType();

            data.put("keyframes", new ListType());
            data.putString("type", "myaddon:custom_factory");

            channel.fromData(data);

            require(channel.getFactory() == KeyframeFactories.STRING,
                "an unknown value type replaced the channel's constructed factory");
            require(channel.getKeyframes().isEmpty(),
                "keyframes of an unknown value type were read by a channel that cannot interpret them");
        });

        require(read.contains("unknown_channel") && read.contains("myaddon:custom_factory"),
            "an unknown keyframe value type is no longer reported at all; stderr said: " + read.trim());

        String write = captureErr(() ->
        {
            KeyframeChannel<String> channel = new KeyframeChannel<>("unregistered_channel", null);
            MapType data = (MapType) channel.toData();
            BaseType type = data.get("type");

            /* The Data API writes a null string as an empty one, so "no value type" lands on disk
             * as an empty type — which is exactly the shape the lookup on load cannot resolve. */
            require(type instanceof StringType && ((StringType) type).value.isEmpty(),
                "a channel without a registered factory still wrote a value type: " + type);
        });

        require(write.contains("unregistered_channel"),
            "a keyframe channel saved without a value type is not reported; stderr said: " + write.trim());
    }

    private static MapType document()
    {
        MapType data = new MapType();

        data.putString("model", "some/model.json");

        return data;
    }

    private static File temporaryFolder()
    {
        try
        {
            return Files.createTempDirectory("bbs-save-version").toFile();
        }
        catch (IOException e)
        {
            throw new AssertionError("could not create a temporary folder for the manager", e);
        }
    }

    /** Runs the action with stderr redirected, so the diagnostic can be asserted instead of read. */
    private static String captureErr(Runnable action)
    {
        PrintStream previous = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try
        {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        }
        finally
        {
            System.setErr(previous);
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** The smallest document a manager can own: a group whose only content is the raw map. */
    private static final class TestGroup extends ValueGroup
    {
        private TestGroup(String id)
        {
            super(id);
        }
    }

    /**
     * A real manager over a temporary folder. It deliberately inherits the base {@code load},
     * {@code save} and ladder — the point of the test is that boundary, not a reimplementation.
     */
    private static final class TestManager extends BaseManager<TestGroup>
    {
        private TestManager(File folder)
        {
            super(() -> folder);
        }

        @Override
        protected TestGroup createData(String id, MapType mapType)
        {
            TestGroup group = new TestGroup(id);

            if (mapType != null)
            {
                group.fromData(mapType);
            }

            return group;
        }

        private List<IDataMigration> ladder()
        {
            return this.getMigrations();
        }

        /** Writes a document straight to disk, so the test controls the version it carries. */
        private void writeRaw(String id, MapType data) throws IOException
        {
            this.storage.save(this.getFile(id), data);
        }

        private MapType readRaw(String id) throws IOException
        {
            return this.storage.load(this.getFile(id));
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
