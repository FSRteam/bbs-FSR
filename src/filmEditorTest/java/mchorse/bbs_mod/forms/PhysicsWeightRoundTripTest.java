package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.cubic.physics.ModelPhysicsConfig;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsIO;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelPhysicsFormPanel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 * <p>The panel cannot be constructed here: its constructor builds text boxes, and those ask a live
 * {@code Minecraft} instance for a font. So the tests allocate one without running its constructor
 * and fill the three fields {@link UIModelPhysicsFormPanel} reads back. The two methods under test
 * are the real production ones, not a reimplementation of them.</p>
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
            run("the editor state carries every scalar", PhysicsWeightRoundTripTest::testEditorStateHasAWeightField);
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

    /**
     * The editor state is what the save path rebuilds the chain from, so a scalar it cannot hold is
     * a scalar a save cannot write. This is the half that names the defect directly; the round trip
     * below is the half that shows the consequence.
     */
    private static void testEditorStateHasAWeightField() throws Exception
    {
        Class<?> boneData = Class.forName("mchorse.bbs_mod.ui.forms.editors.panels.UIModelPhysicsFormPanel$BoneData");
        Field weight = null;

        try
        {
            weight = boneData.getDeclaredField("weight");
        }
        catch (NoSuchFieldException exception)
        {
            throw new AssertionError("the physics panel's editor state has no weight field, so saving a chain "
                + "drops its weight back to the default: " + describeFields(boneData), exception);
        }

        require(weight.getType() == float.class, "the editor state's weight is " + weight.getType()
            + ", but ModelPhysicsConfig.Bone.weight is a float");
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

    /** Run one configured chain through the panel's own load and save, as the editor does. */
    private static MapType loadAndSave(ModelPhysicsConfig config) throws Exception
    {
        Object panel = newPanelWithoutConstructor();
        Method load = UIModelPhysicsFormPanel.class.getDeclaredMethod("load", ModelPhysicsConfig.class);
        Method toPresetData = UIModelPhysicsFormPanel.class.getDeclaredMethod("toPresetData");

        load.setAccessible(true);
        toPresetData.setAccessible(true);

        load.invoke(panel, config);

        return (MapType) toPresetData.invoke(panel);
    }

    /**
     * An instance with the constructor skipped. {@code load} and {@code toPresetData} read only
     * these three fields, so filling them is enough to drive both real paths.
     */
    private static Object newPanelWithoutConstructor() throws Exception
    {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");

        theUnsafe.setAccessible(true);

        Object panel = ((Unsafe) theUnsafe.get(null)).allocateInstance(UIModelPhysicsFormPanel.class);

        setField(panel, "data", new LinkedHashMap<Object, Object>());
        setField(panel, "wind", newWindData());
        setField(panel, "availableBones", Collections.emptyList());

        return panel;
    }

    private static Object newWindData() throws Exception
    {
        Class<?> windData = Class.forName("mchorse.bbs_mod.ui.forms.editors.panels.UIModelPhysicsFormPanel$WindData");
        Constructor<?> constructor = windData.getDeclaredConstructor();

        constructor.setAccessible(true);

        return constructor.newInstance();
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        Field field = UIModelPhysicsFormPanel.class.getDeclaredField(name);

        field.setAccessible(true);
        field.set(target, value);
    }

    private static String describeFields(Class<?> type)
    {
        Map<String, String> fields = new TreeMap<>();

        for (Field field : type.getDeclaredFields())
        {
            if (!field.isSynthetic())
            {
                fields.put(field.getName(), field.getType().getSimpleName());
            }
        }

        return "it has " + fields;
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
