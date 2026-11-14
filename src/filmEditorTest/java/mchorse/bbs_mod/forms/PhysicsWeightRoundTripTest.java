package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.cubic.physics.ModelPhysicsConfig;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsIO;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIBoneListFormPanel;
import java.util.function.Consumer;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelPhysicsFormPanel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sun.misc.Unsafe;

/**
 * The physics panel has to write back every scalar its own data layer understands.
 *
 * <p>{@code ModelPhysicsConfig.Bone} carries a {@code weight} — the blend factor the runtime reads,
 * and the only one that switches a chain off through {@code weight <= 0} — and
 * {@code ModelPhysicsIO} both reads and writes it under the {@code weight} key. The panel's editor
 * state used to have no field for it at all, so every save rebuilt each chain from that state and
 * wrote the default back: opening the physics panel and touching anything, even another chain's
 * switch, silently reset a project's weights to 1 with no control anywhere to notice or restore
 * them. The round trip below is what keeps that from coming back.</p>
 *
 * <p>The tests bypass widget construction to drive the production typed control edit and preset
 * export paths directly, without a Minecraft font or GL context.</p>
 */
public final class PhysicsWeightRoundTripTest
{
    private static final float WEIGHT = 0.37F;
    private static final float ZERO_WEIGHT = 0F;
    private static final float RADIUS = 0.23F;
    private static final float GRAVITY = -0.75F;

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception
    {
        runAll();

        System.out.println("PhysicsWeightRoundTripTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        Runnable restoreRuntime = HeadlessClientTestBootstrap.install();

        FAILURES.clear();

        try
        {
            run("the editor state carries every scalar", PhysicsWeightRoundTripTest::testTypedControlEditPreservesWeight);
            run("a save writes the weight back unchanged", PhysicsWeightRoundTripTest::testPanelLoadSaveRoundTripPreservesWeight);
        }
        finally
        {
            restoreRuntime.run();
        }

        if (!FAILURES.isEmpty())
        {
            throw new AssertionError("the physics panel does not round trip its own data layer:\n  "
                + String.join("\n  ", FAILURES));
        }
    }

    private interface Check
    {
        void run() throws Exception;
    }

    /**
     * Run every check before reporting, so one run shows the whole defect instead of its first
     * symptom — the missing field and the weight it drops are two faces of the same thing.
     */
    private static void run(String name, Check check)
    {
        try
        {
            check.run();
        }
        catch (Throwable failure)
        {
            FAILURES.add(name + ": " + failure);
        }
    }

    private static void testTypedControlEditPreservesWeight() throws Exception
    {
        ModelForm form = new ModelForm();
        form.bones.getOrCreate("root").physicsEnd.set("tip");
        PhysicsControl configured = new PhysicsControl();
        configured.weight = WEIGHT;
        form.bones.getBone("root").physics.set(configured);
        Object panel = newPanelWithoutConstructor(form);
        Method edit = UIModelPhysicsFormPanel.class.getDeclaredMethod("editControl", Consumer.class);
        edit.setAccessible(true);
        edit.invoke(panel, (Consumer<PhysicsControl>) control -> control.gravity = GRAVITY);
        PhysicsControl actual = form.bones.getBone("root").physics.get();
        require(actual.gravity == GRAVITY && actual.weight == WEIGHT,
            "editing a typed physics scalar overwrote the authored weight");
        require(form.bones.getBone("root").physicsEnd.get().equals("tip"), "scalar edit erased chain topology");
    }

    /**
     * Load a configured chain into the panel, save it back, and read the result the way the render
     * path does. Every scalar must come out exactly as it went in — the weight bit for bit, since it
     * is a blend factor rather than a measurement.
     */
    private static void testPanelLoadSaveRoundTripPreservesWeight() throws Exception
    {
        Map<String, ModelPhysicsConfig.Bone> configured = new LinkedHashMap<>();

        configured.put("root", new ModelPhysicsConfig.Bone("tip", "spine", GRAVITY, 0.15F,
            ModelPhysicsConfig.DEFAULT_STIFFNESS, 4, false, 0F, 0F, 0F, false, RADIUS, WEIGHT));
        configured.put("hair", new ModelPhysicsConfig.Bone("hairEnd", "", 1F, 0.15F,
            ModelPhysicsConfig.DEFAULT_STIFFNESS, 4, false, 0F, 0F, 0F, false, RADIUS, ZERO_WEIGHT));

        MapType saved = loadAndSave(new ModelPhysicsConfig(configured, ModelPhysicsConfig.Wind.NONE));
        ModelPhysicsConfig roundTripped = ModelPhysicsIO.fromData(saved);

        require(roundTripped != null && roundTripped.bones() != null, "the save path wrote no chains at all");

        ModelPhysicsConfig.Bone root = roundTripped.bones().get("root");

        require(root != null, "the save path lost the root chain");
        require(Float.floatToIntBits(root.weight()) == Float.floatToIntBits(WEIGHT),
            "a save reset the chain's weight: expected " + WEIGHT + ", got " + root.weight()
                + " - the panel wrote the default back instead of the weight it loaded");
        require(root.end().equals("tip") && root.targetBone().equals("spine"),
            "a save changed the chain's bones: got " + root.end() + " -> " + root.targetBone());
        require(root.gravity() == GRAVITY && root.damping() == 0.15F && root.radius() == RADIUS,
            "a save changed the chain's scalars: gravity " + root.gravity() + ", damping " + root.damping()
                + ", radius " + root.radius());

        ModelPhysicsConfig.Bone hair = roundTripped.bones().get("hair");

        require(hair != null, "the save path lost the chain disabled by a zero weight");
        require(Float.floatToIntBits(hair.weight()) == Float.floatToIntBits(ZERO_WEIGHT),
            "a save turned the disabled chain's weight into " + hair.weight());
    }

    /** Exercise the same compatibility load and canonical preset export used by the panel. */
    private static MapType loadAndSave(ModelPhysicsConfig config) throws Exception
    {
        ModelForm form = new ModelForm();
        form.physics.set(ModelPhysicsIO.toData(config));
        Object panel = newPanelWithoutConstructor(form);
        Method toPresetData = UIModelPhysicsFormPanel.class.getDeclaredMethod("toPresetData");
        toPresetData.setAccessible(true);
        return (MapType) toPresetData.invoke(panel);
    }

    private static Object newPanelWithoutConstructor(ModelForm form) throws Exception
    {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object panel = ((Unsafe) theUnsafe.get(null)).allocateInstance(UIModelPhysicsFormPanel.class);
        Field formField = UIFormPanel.class.getDeclaredField("form");
        formField.setAccessible(true);
        formField.set(panel, form);
        Field selection = UIBoneListFormPanel.class.getDeclaredField("selectedBone");
        selection.setAccessible(true);
        selection.set(panel, "root");
        return panel;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private PhysicsWeightRoundTripTest()
    {}
}
