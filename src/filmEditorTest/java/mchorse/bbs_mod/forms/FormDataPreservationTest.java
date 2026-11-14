package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.cubic.constraints.BoneConstraintsIO;
import mchorse.bbs_mod.cubic.ik.JointDoF;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import mchorse.bbs_mod.forms.forms.utils.FormMaterial;
import mchorse.bbs_mod.forms.forms.utils.ValueBones;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.utils.BoneSelection;
import mchorse.bbs_mod.ui.utils.IBoneSelectionHost;
import mchorse.bbs_mod.ui.utils.UI;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Canonical bone migration, addon preservation and the shared bone-panel layout contracts. */
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
            testMaterialEditsPreserveForeignData();
            testBodyPartInheritanceFlagsSurviveRoundTrip();
            testPreservedKeyIsNotResurrected();
            testClearedOwnPropertyStaysCleared();
            testUnknownKeysAreStillDropped();
            testNamespacedKeysAreStillKept();
            testChildTakesTheKeyOverFromAPreservedCopy();
            testTypedBonesRoundTrip();
            testUnmodelledBoneKeySurvivesWithoutConstraints();
            testLegacyConstraintsBlobIsMigrated();
            testLegacyBlobWinsOverBonesGroup();
            testPerAxisLimitsSurviveTheExchangeFormat();
            testConstraintsStayReadableAsAPreset();
            testEmptyBonesGroupIsNotWritten();
            testAdaptiveBoneListLayout();
            testEditorOwnedBoneSelection();
        }
        finally
        {
            restoreForms.run();
            restoreRuntime.run();
        }
    }

    private static void testEditorOwnedBoneSelection()
    {
        SelectionHost film = new SelectionHost();
        SelectionHost forms = new SelectionHost();
        SelectionPanel filmPanel = new SelectionPanel();
        SelectionPanel formPanel = new SelectionPanel();
        film.add(filmPanel);
        forms.add(formPanel);
        filmPanel.pick("hand");
        formPanel.pick("head");
        check(filmPanel.selected().equals("hand") && formPanel.selected().equals("head"),
            "Film and form editors overwrite each other's selected bone");
        film.remove(filmPanel);
        SelectionPanel rebuilt = new SelectionPanel();
        film.add(rebuilt);
        check(rebuilt.selected().equals("hand"), "Panel rebuild loses the editor's selected bone");
        forms.add(filmPanel);
        check(filmPanel.selected().equals("head"), "Reattached panel retains a stale selection host");
        SelectionPanel detached = new SelectionPanel();
        detached.pick("detached");
        check(detached.selected().equals("detached") && rebuilt.selected().equals("hand"),
            "Detached panel modifies another editor's selection");
    }

    private static final class SelectionHost extends UIElement implements IBoneSelectionHost
    {
        private final BoneSelection selection = new BoneSelection();

        @Override
        public BoneSelection getBoneSelection()
        {
            return this.selection;
        }
    }

    private static final class SelectionPanel extends UIFormPanel<ModelForm>
    {
        SelectionPanel()
        {
            super(null);
        }

        void pick(String bone) { this.boneSelection().set(bone); }
        String selected() { return this.boneSelection().get(); }
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

        assertSameSubtree(upstream, saved, "materials");
        FormBone arm = form.bones.getBone("bone_upper_arm");

        check(arm != null && arm.ikTarget.get().equals("bone_hand"), "the IK target was lost");
        check(arm.joint.get().limitY && arm.joint.get().stiffnessX == 0.5F, "the typed joint was lost");
        check(form.wind.get().strength == 0.75F && form.wind.get().local, "the typed wind was lost");
        check(!saved.has("ik") && !saved.has("physics"), "legacy projections were persisted");

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

    private static void testMaterialEditsPreserveForeignData()
    {
        MapType source = new MapType();
        MapType foreign = new MapType();
        foreign.putString("mode", "future-overlay");
        source.put("overlay", foreign);
        source.putBool("culling", false);
        FormMaterial material = new FormMaterial("skin");
        material.fromData(source);
        foreign.putString("mode", "changed-after-load");

        material.smoothness.set(0.75F);
        MapType saved = material.toData().asMap();
        check(saved.getMap("overlay").getString("mode").equals("future-overlay"),
            "editing a material changed or dropped its unmodelled payload");
        check(saved.get("culling").equals(source.get("culling")) && saved.size() == 3
            && saved.getFloat("smoothness") == 0.75F,
            "editing one material value rewrote untouched encodings or added neutral fields");

        material.culling.set(FormMaterial.CULLING_OFF);
        saved = material.toData().asMap();
        check(saved.getInt("culling") == FormMaterial.CULLING_OFF,
            "a loaded material's raw encoding overrode an authored edit");
        FormMaterial copy = new FormMaterial("copy");
        copy.copy(material);
        check(copy.toData().equals(saved), "a copied material lost unknown data or its edited values");
        FormMaterial reloaded = new FormMaterial("skin");
        reloaded.fromData(saved);
        check(reloaded.toData().equals(saved), "a second material round trip changed its sparse payload");
        copy.fromData(new MapType());
        check(copy.toData().asMap().isEmpty(), "removed material payload reappeared after a later read");
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

        check(!second.has("bones"), "bones came back although the data no longer had it");
        check(!second.has("materials"), "materials came back although the data no longer had it");
        check(!second.has("wind"), "wind came back although the data no longer had it");

        ModelForm cleared = (ModelForm) architect.fromData(withChildren);
        cleared.bones.fromData(new MapType());
        cleared.materials.fromData(new MapType());
        cleared.wind.reset();
        MapType clearedData = architect.toData(cleared);
        check(!clearedData.has("bones") && !clearedData.has("materials") && !clearedData.has("wind"),
            "explicitly cleared dynamic groups came back on save");

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

        MapType chain = new MapType();

        chain.putString("target", "bone_hand");
        chain.putInt("chain_length", 4);
        data.put("ik", new MapType());
        data.getMap("ik").put("bone_upper_arm", chain);
        BoneConstraint constraint = new BoneConstraint();
        constraint.limitX = true;
        constraint.minX = -20F;
        data.getMap("bones").getMap("bone_upper_arm").put("constraints", constraint.toData());

        ModelForm form = (ModelForm) architect.fromData(data);
        MapType before = architect.toData(form);

        check(form.bones.getBone("bone_upper_arm").ikChainLength.get() == 4,
            "the legacy chain was not read into the canonical bone");
        check(!before.has("ik"), "a legacy projection was persisted next to canonical bones");

        form.ik.fromData(null);

        MapType after = architect.toData(form);

        check(!after.has("ik"), "a property the user cleared came back on save");
        check(after.has("bones"), "clearing an own property also dropped a preserved one");
        check(form.bones.getBone("bone_upper_arm").ikTarget.get().isEmpty(), "clearing IK kept its chain");
        check(form.bones.getBone("bone_upper_arm").constraints.get().limitX
            && form.bones.getBone("bone_upper_arm").constraints.get().minX == -20F,
            "clearing IK also cleared the bone's rotation constraints");
        check(form.wind.get().strength == 0.75F, "clearing IK also cleared global wind");
        assertSameSubtree(before, after, "materials");

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

    /* The constraints group */

    /** Typed controls normalize default fields while preserving authored values and addon data. */
    private static void testTypedBonesRoundTrip()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType upstream = upstreamBonesGroup();
        MapType data = upstreamModelForm();

        data.put("bones", upstream);

        ModelForm form = (ModelForm) architect.fromData(data);
        MapType saved = architect.toData(form);

        /* Both halves are actually in the fixture — an empty round trip would pass vacuously. */
        MapType savedBones = saved.getMap("bones");
        MapType arm = savedBones.getMap("bone_upper_arm");

        check(arm.has("constraints"), "the constraint block was not read");
        check(arm.getMap("constraints").getBool("limit_x", false), "the constraint's X switch was lost");
        check(!arm.getBool("ik_stretch", false), "the neutral IK stretch flag changed");
        check(arm.has("joint"), "the typed joint group was lost");
        check(form.bones.getBone("bone_upper_arm").joint.get().limitY, "the joint axis limit was lost");
        check(form.bones.getBone("bone_upper_arm").joint.get().stiffnessX == 0.5F,
            "the joint stiffness was lost");
        check(form.bones.getBone("bone_upper_arm").physicsRadius.get() == 0.25F,
            "the physics radius was lost");
        check(savedBones.has("bone_only_unmodelled"), "a bone carrying nothing this build models was dropped");
        assertSameSubtree(upstream.getMap("bone_upper_arm"), arm, "exampleaddon:driver");
        check(DataToString.toString(saved).equals(DataToString.toString(architect.toData(architect.fromData(saved)))),
            "canonical typed bone serialization drifted on the second save");

        report("model form: bones subtree", "typed constraints/IK/joint/physics survive; addon subtree identical; canonical save stable");
    }

    /**
     * The bone-level half of the red line. A bone whose every property this build models is
     * neutral, but which carries a key it does not, is not an absent bone: the neutral-bone rule
     * in {@code ValueBones} must not drop it, or the preservation never gets a chance to run.
     */
    private static void testUnmodelledBoneKeySurvivesWithoutConstraints()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType data = new MapType();

        data.putString("id", "bbs:model");
        data.putString("model", "bbs:models/example");

        MapType bones = new MapType();
        MapType bone = new MapType();

        bone.putString("ik_target", "bone_hand");
        bones.put("bone_upper_arm", bone);
        data.put("bones", bones);

        ModelForm form = (ModelForm) architect.fromData(data);
        FormBone read = form.bones.getBone("bone_upper_arm");

        check(read != null, "a bone carrying only unmodelled keys was not read at all");
        check(read.constraints.get().isDefault(), "the setup is broken: the bone is not neutral");

        MapType saved = architect.toData(form);

        assertSameSubtree(data, saved, "bones");
        check(!saved.getMap("bones").getMap("bone_upper_arm").has("constraints"),
            "a switch nothing turned on was written back as a default constraint block");

        report("model form: neutral bone with unmodelled keys", "kept, without gaining a constraint block");
    }

    /**
     * The migration itself. A form saved before the bones group kept its constraints in the
     * {@code constraints} blob, in the exchange format; reading it has to move the values into the
     * per-bone properties, and the blob must not be written back next to them.
     */
    private static void testLegacyConstraintsBlobIsMigrated()
    {
        FormArchitect architect = BBSMod.getForms();
        ModelForm form = (ModelForm) architect.fromData(legacyModelForm());

        BoneConstraint arm = form.bones.getBone("bone_upper_arm").constraints.get();

        check(arm.isActive(), "the blob's enabled switch did not reach the bone");
        check(arm.minX == -30F && arm.minY == -10F && arm.minZ == 0F,
            "the blob's min did not reach the bone: " + arm.minX + ", " + arm.minY + ", " + arm.minZ);
        check(arm.maxX == 30F && arm.maxY == 10F && arm.maxZ == 0F,
            "the blob's max did not reach the bone: " + arm.maxX + ", " + arm.maxY + ", " + arm.maxZ);

        /* The blob's own gate: a bone it names with enabled: false is not constrained, and is
         * not created as a bone either. */
        check(form.bones.getBone("bone_leg") == null,
            "a bone the blob switched off came back as a bone");

        MapType saved = architect.toData(form);

        check(!saved.has("constraints"), "the migrated blob was written back next to the bones group");
        check(saved.has("bones", BaseType.TYPE_MAP), "the migrated constraints were not written as bones");

        /* And it is stable: the second read sees the bones group alone. */
        ModelForm again = (ModelForm) architect.fromData(saved);

        check(again.bones.getBone("bone_upper_arm").constraints.get().equals(arm),
            "the migrated constraint changed on the second read");

        report("model form: legacy constraints blob",
            "constraints -> bones.bone_upper_arm.constraints, blob absent from the save");
    }

    /**
     * A file carrying both is a form written by a build that had the group and then by one that
     * still wrote the blob. The blob is the older writer and is applied after the group — upstream
     * order — so it wins for the bones it names, and a bone it does not name keeps what the group
     * gave it, because the legacy read merges rather than resets.
     */
    private static void testLegacyBlobWinsOverBonesGroup()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType data = upstreamModelForm();

        data.put("bones", upstreamBonesGroup());
        data.put("constraints", legacyConstraintsBlob());

        ModelForm form = (ModelForm) architect.fromData(data);
        BoneConstraint named = form.bones.getBone("bone_upper_arm").constraints.get();
        BoneConstraint unnamed = form.bones.getBone("bone_forearm").constraints.get();

        check(named.minX == -30F && named.maxX == 30F,
            "the legacy blob did not win over the bones group for the bone it names");
        check(unnamed.limitX && unnamed.minX == -45F,
            "a bone only the newer group names lost its constraint to the legacy read");
        check(unnamed.limitY == false,
            "the newer group's per-axis switch was flattened by the legacy read");

        /* A bone the blob names with enabled: false is not created by it, so this is the bone the
         * blob switches off rather than a bone it silently deletes. */
        check(form.bones.getBone("bone_leg") == null,
            "a bone the blob switched off was created by the legacy read");

        /* The combination that is easiest to miss: both writers present, and the save keeps only
         * the newer one — the blob is consumed, not copied forward, and the per-bone keys only the
         * group had survive the merge untouched. */
        MapType saved = architect.toData(form);

        check(!saved.has("constraints"), "the blob was written back although the bones group is the newer writer");
        check(saved.has("bones", BaseType.TYPE_MAP), "the bones group was not written");
        check(!form.bones.getBone("bone_upper_arm").ikStretch.get(),
            "reading the legacy blob changed the IK stretch flag");
        check(saved.getMap("bones").getMap("bone_upper_arm").has("joint"),
            "reading the legacy blob dropped the unmodelled joint group");
        check(saved.getMap("bones").has("bone_only_unmodelled"),
            "reading the legacy blob dropped a bone the group had");

        report("model form: blob and bones group",
            "blob wins for the bones it names, the group keeps the rest, only bones is saved");
    }

    /**
     * The per-axis locks are the second of the two silent degradations this branch set out to
     * remove: the old client IO read {@code enabled}, {@code min} and {@code max} and ignored
     * {@code limits}, so the moment a project written by a newer build was opened here and saved,
     * every axis that was left free came back locked.
     */
    private static void testPerAxisLimitsSurviveTheExchangeFormat()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType data = legacyModelForm();
        MapType entry = data.getMap("constraints").getMap("bones").getMap("bone_upper_arm");
        ListType limits = new ListType();

        limits.addBool(true);
        limits.addBool(false);
        limits.addBool(true);
        entry.put("limits", limits);

        ModelForm form = (ModelForm) architect.fromData(data);
        BoneConstraint arm = form.bones.getBone("bone_upper_arm").constraints.get();

        check(arm.limitX && !arm.limitY && arm.limitZ,
            "the exchange format's per-axis limits were not read: " + arm.limitX + ", " + arm.limitY + ", " + arm.limitZ);

        /* And they survive being written back as a preset. */
        MapType preset = BoneConstraintsIO.write(form.bones);

        check(!preset.getMap("bones").getMap("bone_upper_arm").getList("limits").getBool(1, true),
            "the per-axis limits were flattened on the way out");

        MapType saved = architect.toData(form);

        check(saved.getMap("bones").getMap("bone_upper_arm").getMap("constraints").getBool("limit_y", true) == false,
            "the per-axis limits did not reach the form's own data");

        report("constraints: per-axis limits", "limits [x, -, z] survive read and write on both formats");
    }

    /** The preset exchange format round trip: what the panel saves, the panel can read back. */
    private static void testConstraintsStayReadableAsAPreset()
    {
        FormArchitect architect = BBSMod.getForms();
        ModelForm form = (ModelForm) architect.fromData(legacyModelForm());
        ValueBones original = form.bones;
        MapType preset = BoneConstraintsIO.write(original);

        check(preset.has("bones", BaseType.TYPE_MAP), "the preset writer produced nothing to read");
        check(preset.getMap("bones").has("bone_upper_arm"), "the constrained bone is missing from the preset");

        ValueBones restored = new ValueBones("bones");

        BoneConstraintsIO.read(preset, restored, false);

        check(restored.getBone("bone_upper_arm") != null, "the preset reader did not recreate the bone");
        check(restored.getBone("bone_upper_arm").constraints.get().equals(original.getBone("bone_upper_arm").constraints.get()),
            "the constraint changed on the preset round trip");
        check(!restored.toData().asMap().has("bone_leg"),
            "a bone with nothing switched on was written into the preset");

        report("constraints: preset round trip", "BoneConstraintsIO.write -> read -> the same constraints");
    }

    /**
     * The reverse risk of counting preserved data as "not neutral": a bone with nothing but
     * neutral properties must not be written back, and a form that never had a bones group must
     * not gain an empty one — otherwise opening a project that predates this change would rewrite
     * every model form in it.
     */
    private static void testEmptyBonesGroupIsNotWritten()
    {
        FormArchitect architect = BBSMod.getForms();
        MapType bare = new MapType();

        bare.putString("id", "bbs:model");
        bare.putString("model", "bbs:models/example");

        ModelForm form = (ModelForm) architect.fromData(bare);

        check(form.bones.getAll().isEmpty(), "a form with no bones data came back with bones");

        MapType saved = architect.toData(form);

        check(!saved.has("bones"), "a form with no bone properties gained an empty bones group");

        /* A bone whose constraint block is all defaults is neutral too, and goes with the group. */
        MapType data = new MapType();
        MapType bones = new MapType();
        MapType bone = new MapType();

        data.putString("id", "bbs:model");
        data.putString("model", "bbs:models/example");
        bone.put("constraints", defaultConstraintBlock());
        bones.put("bone_arm", bone);
        data.put("bones", bones);

        MapType neutralSaved = architect.toData(architect.fromData(data));

        check(!neutralSaved.has("bones"), "a bone with a default constraint block was written back");

        report("model form: nothing to write", "no data -> no bones group; default bone -> no bones group");
    }

    /* Fixtures */

    private static void testAdaptiveBoneListLayout()
    {
        UIScrollView options = UI.scrollView(4, 4);
        options.wh(240, 500);
        UIElement list = new UIElement().h(180).expand();
        UISection section = new UISection();
        section.fields.add(new UIElement().h(280));
        options.add(list, section);
        options.resize();
        int expandedListHeight = list.area.h;
        int expandedSectionHeight = section.area.h;

        check(expandedListHeight >= 180, "the bone list shrank below its eight-row minimum");
        section.setExpanded(false);
        check(list.area.h == expandedListHeight + expandedSectionHeight - section.area.h,
            "folding a section did not remeasure the parent and give its space to the bone list");
        options.h(160);
        options.resize();
        check(list.area.h == 180, "a short options panel shrank the bone list instead of scrolling");
        check(options.scroll.scrollSize > options.area.h, "overflowing bone options lost their scroll range");

        UIElement row = new UIElement();
        row.wh(240, 60);
        row.row(4);
        UIElement first = new UIElement().w(20);
        UIElement hidden = new UIElement().w(100);
        UIElement last = new UIElement().expand();
        hidden.setVisible(false);
        row.add(first, hidden, last);
        row.resize();
        check(hidden.area.w == 0 && last.area.x == first.area.ex() + 4,
            "a hidden row child still occupied a layout slot");
        check(last.area.h == row.area.h && last.area.ex() == row.area.ex(),
            "row expansion did not use the remaining width and available height");
    }

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

        bone.put("joint", jointFixture());

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

    /**
     * A {@code bones} group shaped the way a newer build writes one: the modelled constraint block
     * in {@link BoneConstraint}'s own shape plus the per-bone keys this build only carries.
     */
    private static MapType upstreamBonesGroup()
    {
        MapType bones = new MapType();
        MapType arm = new MapType();

        MapType constraints = new MapType();

        constraints.putBool("limit_x", true);
        constraints.putBool("limit_y", false);
        constraints.putBool("limit_z", true);
        constraints.putDouble("min_x", -45D);
        constraints.putDouble("min_y", -45D);
        constraints.putDouble("min_z", -45D);
        constraints.putDouble("max_x", 45D);
        constraints.putDouble("max_y", 45D);
        constraints.putDouble("max_z", 45D);
        arm.put("constraints", constraints);

        arm.putString("ik_target", "bone_hand");
        arm.putString("ik_pole_target", "bone_elbow");
        arm.putInt("ik_chain_length", 2);
        arm.putBool("ik_tip_rotation", false);
        arm.putBool("ik_stretch", false);
        arm.putBool("ik_squash", false);
        arm.putBool("ik_classic", true);

        arm.put("joint", jointFixture());

        MapType addon = new MapType();

        addon.putString("mode", "spring");
        addon.putFloat("influence", 0.625F);
        arm.put("exampleaddon:driver", addon);

        arm.putBool("physics_collisions", true);
        arm.putFloat("physics_radius", 0.25F);

        bones.put("bone_upper_arm", arm);

        /* Nothing this build models — only keys it preserves. */
        MapType unmodelled = new MapType();

        unmodelled.putString("ik_target", "bone_foot");
        unmodelled.putBool("ik_stretch", true);
        bones.put("bone_only_unmodelled", unmodelled);

        /* A second constrained bone, so a legacy read can be shown to leave the bones it does
         * not name alone. */
        MapType forearm = new MapType();
        MapType forearmConstraints = new MapType();

        forearmConstraints.putBool("limit_x", true);
        forearmConstraints.putBool("limit_y", false);
        forearmConstraints.putBool("limit_z", true);
        forearmConstraints.putDouble("min_x", -45D);
        forearmConstraints.putDouble("min_y", -45D);
        forearmConstraints.putDouble("min_z", -45D);
        forearmConstraints.putDouble("max_x", 45D);
        forearmConstraints.putDouble("max_y", 45D);
        forearmConstraints.putDouble("max_z", 45D);
        forearm.put("constraints", forearmConstraints);
        bones.put("bone_forearm", forearm);

        return bones;
    }

    private static MapType jointFixture()
    {
        JointDoF joint = new JointDoF();

        joint.limitY = true;
        joint.stiffnessX = 0.5F;

        return joint.toData();
    }

    private static MapType defaultConstraintBlock()
    {
        MapType constraints = new MapType();

        constraints.putBool("limit_x", false);
        constraints.putBool("limit_y", false);
        constraints.putBool("limit_z", false);
        constraints.putDouble("min_x", -180D);
        constraints.putDouble("min_y", -180D);
        constraints.putDouble("min_z", -180D);
        constraints.putDouble("max_x", 180D);
        constraints.putDouble("max_y", 180D);
        constraints.putDouble("max_z", 180D);

        return constraints;
    }

    /** A form shaped the way this build wrote one before the bones group: the blob, and no bones. */
    private static MapType legacyModelForm()
    {
        MapType data = new MapType();

        data.putString("id", "bbs:model");
        data.putString("model", "bbs:models/example");
        data.putBool("bone_tracks", false);
        data.put("constraints", legacyConstraintsBlob());

        return data;
    }

    /** The exchange format a constraint preset is saved as, and what {@code constraints} held. */
    private static MapType legacyConstraintsBlob()
    {
        MapType root = new MapType();
        MapType bones = new MapType();

        MapType arm = new MapType();

        arm.putBool("enabled", true);
        arm.put("min", floats(-30F, -10F, 0F));
        arm.put("max", floats(30F, 10F, 0F));
        bones.put("bone_upper_arm", arm);

        /* Named, but switched off: the blob's own gate. */
        MapType leg = new MapType();

        leg.putBool("enabled", false);
        leg.put("min", floats(-5F, -5F, -5F));
        leg.put("max", floats(5F, 5F, 5F));
        bones.put("bone_leg", leg);

        root.put("bones", bones);

        return root;
    }

    private static ListType floats(float x, float y, float z)
    {
        ListType list = new ListType();

        list.addFloat(x);
        list.addFloat(y);
        list.addFloat(z);

        return list;
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
