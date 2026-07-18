package mchorse.bbs_mod.forms.categories;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class RecentFormCategoryTest
{
    public static void main(String[] args) throws Exception
    {
        runAll();

        System.out.println("RecentFormCategoryTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        Runnable restoreClientRuntime = HeadlessClientTestBootstrap.install();

        try
        {
            testCentralAddFormOverride();
            testOldestEntriesAreTrimmedAtCapacity();
            testDuplicateMovesToNewestPosition();
            testRecentOrderRemainsStable();
            testNullEntryIsIgnored();
            testDirectListUsesTheSamePolicy();
            testReloadClearsThePreviousQueue();
        }
        finally
        {
            restoreClientRuntime.run();
        }
    }

    private static void testCentralAddFormOverride() throws Exception
    {
        Method addForm = RecentFormCategory.class.getDeclaredMethod("addForm", Form.class);

        check(addForm.getDeclaringClass() == RecentFormCategory.class,
            "RecentFormCategory must own the addForm policy used by every caller");
    }

    private static void testOldestEntriesAreTrimmedAtCapacity()
    {
        List<Entry> entries = new ArrayList<>();

        for (int i = 0; i < 130; i += 1)
        {
            RecentFormCategory.addRecent(entries, new Entry(i, "entry-" + i));
        }

        check(entries.size() == 128, "recent forms exceeded the 128-entry limit");

        for (int i = 0; i < entries.size(); i += 1)
        {
            check(entries.get(i).key == i + 2,
                "oldest-entry trimming changed the relative recent order at index " + i);
        }
    }

    private static void testDuplicateMovesToNewestPosition()
    {
        List<Entry> entries = new ArrayList<>();
        Entry original = new Entry(1, "original");
        Entry legacyDuplicate = new Entry(1, "legacy-duplicate");
        Entry middle = new Entry(2, "middle");
        Entry tail = new Entry(3, "tail");
        Entry replacement = new Entry(1, "replacement");

        RecentFormCategory.addRecent(entries, original);
        RecentFormCategory.addRecent(entries, middle);
        RecentFormCategory.addRecent(entries, tail);
        entries.add(1, legacyDuplicate);
        RecentFormCategory.addRecent(entries, replacement);

        check(entries.size() == 3, "an equal recent form was retained twice");
        check(entries.get(0) == middle && entries.get(1) == tail,
            "moving a duplicate disturbed unrelated recent entries");
        check(entries.get(2) == replacement,
            "the incoming equal form was not retained at the newest position");
    }

    private static void testRecentOrderRemainsStable()
    {
        List<Entry> entries = new ArrayList<>();
        Entry first = new Entry(1, "first");
        Entry second = new Entry(2, "second");
        Entry third = new Entry(3, "third");

        RecentFormCategory.addRecent(entries, first);
        RecentFormCategory.addRecent(entries, second);
        RecentFormCategory.addRecent(entries, third);
        RecentFormCategory.addRecent(entries, second);

        check(entries.equals(List.of(first, third, second)),
            "re-adding an entry did not preserve oldest-to-newest order");
    }

    private static void testNullEntryIsIgnored()
    {
        List<Entry> entries = new ArrayList<>();
        Entry entry = new Entry(1, "entry");

        RecentFormCategory.addRecent(entries, entry);
        RecentFormCategory.addRecent(entries, null);

        check(entries.size() == 1 && entries.get(0) == entry,
            "a null form changed the recent queue");
    }

    private static void testDirectListUsesTheSamePolicy()
    {
        ValueInt previousOverlaySetting = BBSSettings.recordingPoseTransformOverlays;
        BBSSettings.recordingPoseTransformOverlays = new ValueInt("test_pose_transform_overlays", 0);

        try
        {
            RecentFormCategory category = new RecentFormCategory(new ValueBoolean("recent", true));
            List<Form> forms = new ArrayList<>();

            for (int i = 0; i < 130; i += 1)
            {
                forms.add(new TestForm(i));
            }

            /* This is the mutable list exposed to UI/category callers. It must
             * have exactly the same FIFO and duplicate behavior as addForm. */
            category.getDirectForms().addAll(forms);

            check(category.getDirectForms().size() == 128,
                "direct-list writes exceeded the 128-entry limit");
            check(((TestForm) category.getDirectForms().get(0)).key == 2,
                "direct-list writes did not evict the oldest entry");

            TestForm replacement = new TestForm(42);
            category.getDirectForms().add(replacement);

            check(category.getDirectForms().size() == 128,
                "re-adding a direct-list duplicate changed the queue size");
            check(category.getDirectForms().get(127) == replacement,
                "direct-list duplicate was not moved to the newest position");
            check(countKey(category.getDirectForms(), 42) == 1,
                "direct-list duplicate was retained twice");

            TestForm setReplacement = new TestForm(7);
            category.getDirectForms().set(0, setReplacement);

            check(category.getDirectForms().size() == 127,
                "replacing an entry with an existing duplicate did not collapse the duplicate");
            check(category.getDirectForms().get(category.getDirectForms().size() - 1) == setReplacement,
                "direct-list replacement did not use recent ordering");
            check(countKey(category.getDirectForms(), 7) == 1,
                "direct-list replacement introduced a duplicate");
        }
        finally
        {
            BBSSettings.recordingPoseTransformOverlays = previousOverlaySetting;
        }
    }

    private static void testReloadClearsThePreviousQueue()
    {
        ValueInt previousOverlaySetting = BBSSettings.recordingPoseTransformOverlays;
        BBSSettings.recordingPoseTransformOverlays = new ValueInt("test_pose_transform_overlays", 0);

        try
        {
            RecentFormCategory category = new RecentFormCategory(new ValueBoolean("recent", true));
            category.addForm(new TestForm(1));
            category.addForm(new TestForm(2));

            category.fromData(new MapType());

            check(category.getForms().isEmpty(),
                "reloading an empty recent-form payload retained stale entries");
        }
        finally
        {
            BBSSettings.recordingPoseTransformOverlays = previousOverlaySetting;
        }
    }

    private static int countKey(List<Form> forms, int key)
    {
        int count = 0;

        for (Form form : forms)
        {
            if (((TestForm) form).key == key)
            {
                count += 1;
            }
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

    private static final class Entry
    {
        private final int key;
        private final String instance;

        private Entry(int key, String instance)
        {
            this.key = key;
            this.instance = instance;
        }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof Entry entry && this.key == entry.key;
        }

        @Override
        public int hashCode()
        {
            return Integer.hashCode(this.key);
        }

        @Override
        public String toString()
        {
            return this.instance;
        }
    }

    private static final class TestForm extends Form
    {
        private final int key;

        private TestForm(int key)
        {
            this.key = key;
        }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof TestForm form && this.key == form.key;
        }

        @Override
        public int hashCode()
        {
            return Integer.hashCode(this.key);
        }
    }

    private RecentFormCategoryTest()
    {}
}
