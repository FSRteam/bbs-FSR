package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What happens to the form data a build newer than this one wrote.
 *
 * <p>The case these cover is the one that cannot be undone: an upstream project holds its IK
 * chains, joint limits and physics in a {@code bones} child of the model form, and this build has
 * no such child. Before the preservation rules under test, opening that project here and saving it
 * deleted them from disk. The assertions below are the two halves of the rule — an unknown child
 * of a parent this build models survives, and an unknown key of a parent it does not know the
 * child of still disappears, because that is what makes a removed property go away.</p>
 */
public final class FormDataPreservationTest
{
    public static void main(String[] args) throws Exception
    {
        runAll();

        System.out.println("FormDataPreservationTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        Runnable restoreRuntime = HeadlessClientTestBootstrap.install();
        Runnable restoreForms = installFormArchitect();

        try
        {
            testUpstreamModelFormSurvivesRoundTrip();
            testBodyPartInheritanceFlagsSurviveRoundTrip();
            testPreservedKeyIsNotResurrected();
            testClearedOwnPropertyStaysCleared();
            testUnknownKeysAreStillDropped();
            testNamespacedKeysAreStillKept();
            testChildTakesTheKeyOverFromAPreservedCopy();
        }
        finally
        {
            restoreForms.run();
            restoreRuntime.run();
        }
    }

    /**
     * The whole round trip an upstream project takes: the form is read through the same factory a
     * real load uses, then written back through the same factory a save uses.
     */
    private static void testUpstreamModelFormSurvivesRoundTrip()
    {
        MapType upstream = upstreamModelForm();

        FormArchitect architect = BBSMod.getForms();
        ModelForm form = (ModelForm) architect.fromData(upstream);
        MapType saved = architect.toData(form);

        assertSameSubtree(upstream, saved, "bones");
        assertSameSubtree(upstream, saved, "materials");
        assertSameSubtree(upstream, saved, "wind");

        /* The form's own values still load and save normally next to the preserved ones. */
        check("bbs:models/example".equals(saved.getString("model")), "the form's own model link was lost");
        check("bbs:skins/example.png".equals(saved.getString("texture")), "the form's own texture was lost");
        check(!saved.getBool("bone_tracks", true), "the form's own bone_tracks switch was lost");

        /* And the whole thing is stable: saving what was read, then reading and saving again,
         * produces the same bytes. A preserved blob that drifts by one byte per save would be
         * worse than a dropped one. */
        MapType again = architect.toData(architect.fromData(saved));

        check(DataToString.toString(saved).equals(DataToString.toString(again)),
            "a second round trip changed the saved data");

        report("model form: preserved", preservedKeys(upstream, saved));
        report("model form: still dropped", droppedKeys(upstream, saved));
    }

    private static void testBodyPartInheritanceFlagsSurviveRoundTrip()
    {
        MapType upstream = new MapType();

        upstream.putString("bone", "head");
        upstream.putBool("useTarget", true);
        upstream.putBool("inheritPosition", false);
        upstream.putBool("inheritRotation", true);
        upstream.putBool("inheritScale", false);

        BodyPart part = new BodyPart("0");
        part.fromData(upstream);

        MapType saved = part.toData().asMap();

        assertSameSubtree(upstream, saved, "inheritPosition");
        assertSameSubtree(upstream, saved, "inheritRotation");
        assertSameSubtree(upstream, saved, "inheritScale");
        check(!saved.getBool("inheritPosition", true), "the part's own bone selection was lost");

        report("body part: preserved", preservedKeys(upstream, saved));
        report("body part: still dropped", droppedKeys(upstream, saved));
    }

    /**
     * The other half of the rule: preservation is not memory. Data that does not carry a key must
     * not write that key back, or a property the user deleted would reappear on the next save.
     */
    private static void testPreservedKeyIsNotResurrected()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType withChildren = upstreamModelForm();
        MapType withoutChildren = upstreamModelForm();

        withoutChildren.remove("bones");
        withoutChildren.remove("materials");
        withoutChildren.remove("wind");

        MapType first = architect.toData(architect.fromData(withChildren));

        check(first.has("bones") && first.has("materials") && first.has("wind"),
            "the setup for this test is broken: nothing was read");

        MapType second = architect.toData(architect.fromData(withoutChildren));

        check(!second.has("bones"), "a preserved key came back although the data no longer had it");
        check(!second.has("materials"), "a preserved key came back although the data no longer had it");
        check(!second.has("wind"), "a preserved key came back although the data no longer had it");

        /* A property this build does model is written only when it has something to write, so a
         * file without it does not gain an empty one — the deletion stays a deletion. */
        check(!second.has("ik"), "an absent own property was written back as an empty one");

        report("model form: read without bones/materials/wind",
            "(bones, materials, wind absent from the data) -> absent from the save");
    }

    /**
     * The same rule seen from the editor's side: an own property the user clears is gone from the
     * save, while the preserved children of the same form are untouched by it.
     */
    private static void testClearedOwnPropertyStaysCleared()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType data = upstreamModelForm();

        data.put("ik", new MapType());
        data.getMap("ik").putInt("chain_length", 4);

        ModelForm form = (ModelForm) architect.fromData(data);
        MapType before = architect.toData(form);

        check(before.has("ik"), "the own property was not read in the first place");

        form.ik.fromData(null);

        MapType after = architect.toData(form);

        check(!after.has("ik"), "a property the user cleared came back on save");
        check(after.has("bones"), "clearing an own property also dropped a preserved one");

        report("model form: cleared ik", "a cleared own property -> absent from the save, bones untouched");
    }

    /**
     * A key this build has no child for, and that is not on the list of known newer children, is
     * still dropped — this is what lets a property that was removed from the code disappear from
     * the file instead of trailing after it forever.
     */
    private static void testUnknownKeysAreStillDropped()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType data = upstreamModelForm();

        data.putInt("old_removed_property", 5);

        MapType saved = architect.toData(architect.fromData(data));

        check(!saved.has("old_removed_property"), "an unknown property of the model form was kept");

        ValueGroup plain = new ValueGroup("plain");
        MapType plainData = new MapType();

        plainData.putInt("bones", 5);
        plain.fromData(plainData);

        check(!plain.toData().asMap().has("bones"),
            "the base group started preserving unknown keys; the whitelist is not per parent type");

        report("model form: still dropped", "old_removed_property (not on the whitelist) -> absent from the save");
        report("base value group: still dropped", "bones (no whitelist on the base group) -> absent from the save");
    }

    /** The addon-key rule from before this change still holds: a namespaced key survives. */
    private static void testNamespacedKeysAreStillKept()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType data = upstreamModelForm();
        MapType addonValue = new MapType();

        addonValue.putFloat("wobble", 2.5F);
        addonValue.putString("mode", "wobbly");
        data.put("exampleaddon:wobble", addonValue);

        MapType saved = architect.toData(architect.fromData(data));

        assertSameSubtree(data, saved, "exampleaddon:wobble");

        report("model form: keep", "exampleaddon:wobble (namespaced, addon not loaded) -> preserved");
    }

    /**
     * The guard in {@code ValueGroup.toData}: once a group has the real child, the child's value is
     * what is written, and the preserved copy stops being written by itself. That is what lets the
     * whitelist stay in place when this build finally models what it preserves.
     */
    private static void testChildTakesTheKeyOverFromAPreservedCopy()
    {
        GrowableGroup group = new GrowableGroup();
        MapType data = new MapType();
        MapType raw = new MapType();

        raw.putInt("a", 1);
        data.put("bones", raw);
        group.fromData(data);

        MapType before = group.toData().asMap();

        check(before.has("bones") && before.getMap("bones").equals(raw), "the preserved copy was not written");

        group.add(new ValueInt("bones", 3));

        MapType after = group.toData().asMap();

        check(after.getInt("bones", -1) == 3, "the real child did not take the key over from the preserved copy");

        report("value group: precedence", "a child added after the data wins over the preserved copy");
    }

    /* Fixtures */

    /**
     * A model form shaped the way an upstream build writes one: the per-bone IK group, the material
     * overrides and the global wind, none of which this build has a child for, plus its own shape
     * so the ordinary path is exercised on the same instance.
     */
    private static MapType upstreamModelForm()
    {
        MapType data = new MapType();

        data.putString("id", "bbs:model");
        data.putString("model", "bbs:models/example");
        data.putString("texture", "bbs:skins/example.png");
        data.putBool("bone_tracks", false);

        MapType bones = new MapType();
        MapType bone = new MapType();

        bone.putString("ik_target", "bone_hand");
        bone.putString("ik_pole_target", "bone_elbow");
        bone.putInt("ik_chain_length", 2);
        bone.putBool("ik_classic", true);
        bone.putBool("ik_stretch", false);
        bone.putBool("ik_squash", false);

        MapType joint = new MapType();

        joint.putBool("lock", false);
        joint.putBool("limited", true);
        joint.putFloat("stiffness", 0.5F);
        bone.put("joint", joint);

        bones.put("bone_upper_arm", bone);
        data.put("bones", bones);

        MapType materials = new MapType();
        MapType skin = new MapType();

        skin.putFloat("overlay", 0.25F);
        skin.putBool("culling", false);
        materials.put("skin", skin);
        data.put("materials", materials);

        MapType wind = new MapType();

        wind.putFloat("strength", 0.75F);
        wind.putFloat("x", 1F);
        wind.putFloat("y", 0F);
        wind.putBool("local", true);
        data.put("wind", wind);

        return data;
    }

    private static final class GrowableGroup extends ValueGroup
    {
        private static final Set<String> KEYS = Set.of("bones");

        GrowableGroup()
        {
            super("growable");
        }

        @Override
        protected Set<String> preservedUnknownKeys()
        {
            return KEYS;
        }
    }

    /* Assertions and reporting */

    private static void assertSameSubtree(MapType expected, MapType actual, String key)
    {
        check(expected.has(key), "the test fixture has no " + key);

        BaseType before = expected.get(key);
        BaseType after = actual.get(key);

        check(after != null, "'" + key + "' was dropped on save");
        check(before.equals(after), "'" + key + "' came back different: " + before + " -> " + after);
        check(DataToString.toString(before).equals(DataToString.toString(after)),
            "'" + key + "' does not round trip byte for byte");
    }

    private static String preservedKeys(MapType input, MapType output)
    {
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, BaseType> entry : ordered(input))
        {
            if (output.has(entry.getKey()) && entry.getValue().equals(output.get(entry.getKey())))
            {
                append(builder, entry.getKey());
            }
        }

        return builder.isEmpty() ? "(none)" : builder.toString();
    }

    private static String droppedKeys(MapType input, MapType output)
    {
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, BaseType> entry : ordered(input))
        {
            if (!output.has(entry.getKey()))
            {
                append(builder, entry.getKey());
            }
        }

        return builder.isEmpty() ? "(none)" : builder.toString();
    }

    private static Collection<Map.Entry<String, BaseType>> ordered(MapType map)
    {
        return new TreeMap<>(map.asMap().elements).entrySet();
    }

    private static void append(StringBuilder builder, String key)
    {
        if (!builder.isEmpty())
        {
            builder.append(", ");
        }

        builder.append(key);
    }

    private static void report(String subject, String detail)
    {
        System.out.println("[form-data] " + subject + ": " + detail);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    /* Harness */

    /**
     * A form factory with just enough registered to write a model form, installed the same
     * reflective way {@link HeadlessClientTestBootstrap} installs the clip factories — the
     * standalone harness never runs BBS's own registration.
     *
     * <p>A model form reads one setting while it is being built (how many pose overlays the user
     * asked for), and that setting is only wired up by the real settings registration, so it is
     * standing in for here — the same way {@code IKPhysicsConsistencyTest} does it.</p>
     */
    private static Runnable installFormArchitect()
    {
        try
        {
            Field field = BBSMod.class.getDeclaredField("forms");

            field.setAccessible(true);

            Object previous = field.get(null);
            ValueInt previousOverlays = BBSSettings.recordingPoseTransformOverlays;
            FormArchitect architect = new FormArchitect();

            architect.register(Link.bbs("model"), ModelForm.class, null);
            architect.register(Link.bbs("billboard"), BillboardForm.class, null);
            field.set(null, architect);

            if (previousOverlays == null)
            {
                BBSSettings.recordingPoseTransformOverlays = new ValueInt("test_pose_transform_overlays", 0);
            }

            return () ->
            {
                try
                {
                    field.set(null, previous);

                    if (previousOverlays == null)
                    {
                        BBSSettings.recordingPoseTransformOverlays = null;
                    }
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the form factory", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the form factory", exception);
        }
    }

    private FormDataPreservationTest()
    {}
}
