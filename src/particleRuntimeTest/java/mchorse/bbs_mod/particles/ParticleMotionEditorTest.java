package mchorse.bbs_mod.particles;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.motion.MotionComponents;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeMotionSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeRotationSection;
import mchorse.bbs_mod.ui.particles.utils.UIMolangExpression;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Exercises the real motion section callbacks without creating a game window. */
public final class ParticleMotionEditorTest
{
    private ParticleMotionEditorTest()
    {}

    public static void run() throws Exception
    {
        Field l10n = BBSModClient.class.getDeclaredField("l10n");
        Field cameras = BBSMod.class.getDeclaredField("factoryCameraClips");
        Field actions = BBSMod.class.getDeclaredField("factoryActionClips");
        Field minecraft = Minecraft.class.getDeclaredField("instance");

        l10n.setAccessible(true);
        cameras.setAccessible(true);
        actions.setAccessible(true);
        minecraft.setAccessible(true);

        Object oldL10n = l10n.get(null);
        Object oldCameras = cameras.get(null);
        Object oldActions = actions.get(null);
        Object oldMinecraft = minecraft.get(null);

        try
        {
            l10n.set(null, new L10n());
            cameras.set(null, new MapFactory<>());
            actions.set(null, new MapFactory<>());

            Field singleton = Unsafe.class.getDeclaredField("theUnsafe");

            singleton.setAccessible(true);

            Unsafe unsafe = (Unsafe) singleton.get(null);

            if (oldMinecraft == null)
            {
                Object headless = unsafe.allocateInstance(Minecraft.class);

                unsafe.putObject(headless, unsafe.objectFieldOffset(Minecraft.class.getField("font")), new HeadlessFont());
                minecraft.set(null, headless);
            }

            ProbePanel panel = (ProbePanel) unsafe.allocateInstance(ProbePanel.class);

            testIndependentEditors(panel);
            testLayoutUpgrade(panel);
            testRelativeFieldAndDrag();
            testMutatingInputChildren();
        }
        finally
        {
            l10n.set(null, oldL10n);
            cameras.set(null, oldCameras);
            actions.set(null, oldActions);
            minecraft.set(null, oldMinecraft);
        }
    }

    private static void testIndependentEditors(ProbePanel panel)
    {
        ParticleScheme scheme = new ParticleScheme();
        UIParticleSchemeMotionSection position = new UIParticleSchemeMotionSection(panel);
        UIParticleSchemeRotationSection rotation = new UIParticleSchemeRotationSection(panel);

        position.link(rotation);
        rotation.link(position);
        position.setScheme(scheme);
        rotation.setScheme(scheme);
        check(position.mode.getValue() == 0 && rotation.mode.getValue() == 0, "new scheme did not start dynamic");

        edit(position.x, "3");
        edit(rotation.acceleration, "2");
        check(MotionComponents.dynamic(scheme).motionAcceleration[0].get() == 3D
            && MotionComponents.dynamic(scheme).rotationAcceleration.get() == 2D, "dynamic expression edited wrong axis");

        position.mode.setValue(1);
        position.mode.callback.accept(position.mode);
        check(MotionComponents.isPositionParametric(scheme) && !MotionComponents.isRotationParametric(scheme), "position toggle changed rotation mode");
        check(rotation.mode.getValue() == 0 && rotation.angle.hasParent() && !position.speed.hasParent(), "mode-dependent fields did not refresh");
        check(MotionComponents.dynamic(scheme).rotationAcceleration.get() == 2D, "position mode discarded active rotation data");

        edit(position.y, "5");
        check(MotionComponents.parametric(scheme).position[1].get() == 5D, "parametric position expression reached acceleration");

        rotation.mode.setValue(1);
        rotation.mode.callback.accept(rotation.mode);
        edit(rotation.acceleration, "7");
        check(MotionComponents.isPositionParametric(scheme) && MotionComponents.isRotationParametric(scheme), "rotation toggle changed position mode");
        check(!rotation.angle.hasParent() && !rotation.rate.hasParent() && !rotation.drag.hasParent(), "parametric rotation retained dynamic-only fields");
        check(MotionComponents.parametric(scheme).rotation.get() == 7D && MotionComponents.parametric(scheme).position[1].get() == 5D, "rotation edit discarded position expression");

        position.mode.setValue(0);
        position.mode.callback.accept(position.mode);
        check(!MotionComponents.isPositionParametric(scheme) && MotionComponents.isRotationParametric(scheme), "mixed dynamic-position/parametric-rotation not representable");
        check(rotation.mode.getValue() == 1 && MotionComponents.parametric(scheme).rotation.get() == 7D, "position toggle discarded active parametric rotation");
        check(panel.restarts == 3 && panel.changes == 7, "mode switches did not restart or expression changes did not mark dirty");

        UISimpleContextMenu menu = (UISimpleContextMenu) rotation.acceleration.createContextMenu(null);

        menu.actions.getList().getFirst().runnable.run();
        panel.expressionEdit.accept("9");
        check(MotionComponents.parametric(scheme).rotation.get() == 9D && rotation.acceleration.getText().equals(MotionComponents.parametric(scheme).rotation.toString()), "docked edit did not refresh the inline expression");

        edit(rotation.acceleration, "");
        check(MotionComponents.parametric(scheme).rotation.get() == 0D && panel.changes == 9, "clearing the expression did not update the scheme");
    }

    private static void testLayoutUpgrade(ProbePanel panel) throws Exception
    {
        Field panels = UIParticleSchemePanel.class.getDeclaredField("panelById");
        Map<String, UIElement> registered = new LinkedHashMap<>();

        for (String id : new String[] {"preview", "file", "emitter", "motion", "appearance", "time", "events", "curves", "molang"})
        {
            registered.put(id, null);
        }

        panels.setAccessible(true);
        panels.set(panel, registered);

        Method ensure = UIParticleSchemePanel.class.getDeclaredMethod("ensureParticleLayoutPanels", EditorLayoutNode.class);

        ensure.setAccessible(true);

        EditorLayoutNode original = EditorLayoutNode.defaultParticleLayout();
        EditorLayoutNode upgraded = (EditorLayoutNode) ensure.invoke(panel, original);
        Map<String, float[]> before = new HashMap<>();
        Map<String, float[]> after = new HashMap<>();

        original.computeBounds(0F, 0F, 1F, 1F, before);
        upgraded.computeBounds(0F, 0F, 1F, 1F, after);
        check(after.keySet().equals(registered.keySet()), "layout upgrade lost or omitted a particle panel");

        for (String id : before.keySet())
        {
            check(Arrays.equals(before.get(id), after.get(id)), "layout upgrade changed saved panel bounds: " + id);
        }

        check(Arrays.equals(after.get("curves"), after.get("molang")), "MoLang editor was not added to the curves stack");
        check(ensure.invoke(panel, upgraded) == upgraded, "layout upgrade was not idempotent");
        check(ensure.invoke(panel, new Object[] {null}) != null, "missing layout did not receive the new editor");
    }

    private static void edit(UIMolangExpression field, String code)
    {
        field.setText(code);
        field.textbox.acceptText();
    }

    private static void testRelativeFieldAndDrag()
    {
        double[] delta = {Double.NaN};
        UITrackpad field = new ProbeTrackpad((value) -> delta[0] = value);

        field.relative(true).limit(0D, 10D);
        field.setValueAndNotify(20D);
        check(field.getValue() == 10D && delta[0] == 20D, "relative programmatic edit lost the requested delta");

        double[] target = {0D};
        int[] writes = {0};
        ProbeSlider slider = new ProbeSlider((value) ->
        {
            target[0] += value;
            writes[0]++;
        });

        slider.relative(true).limit(0D, 10D);
        slider.dragValue(20D);
        check(slider.getValue() == 10D && writes[0] == 1 && target[0] == 20D, "relative drag did not apply the requested delta once");
        slider.dragValue(30D);
        check(writes[0] == 1, "drag held beyond its limit repeated the relative delta");
        slider.dragValue(9D);
        check(slider.getValue() == 9D && target[0] == 19D && writes[0] == 2, "drag reversal at the limit did not apply one negative delta");
    }

    private static void testMutatingInputChildren()
    {
        for (int event = 0; event < 5; event++)
        {
            ProbeContainer parent = new ProbeContainer();

            parent.add(new UIElement(), new UIElement(), new MutatingChild(parent));
            check(parent.dispatch(event) == null && parent.getChildren().isEmpty(),
                "input child removal did not finish the traversal: " + event);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class ProbePanel extends UIParticleSchemePanel
    {
        private int restarts;
        private int changes;
        private Consumer<String> expressionEdit;

        private ProbePanel()
        {
            super(null);
        }

        @Override
        public void dirty()
        {
            this.changes++;
        }

        @Override
        public void restartEmitter()
        {
            this.restarts++;
        }

        @Override
        public void editMoLang(String id, Consumer<String> callback, MolangExpression expression)
        {
            this.expressionEdit = callback;
        }
    }

    private static final class HeadlessFont extends Font
    {
        private HeadlessFont()
        {
            super((location) -> null, false);
        }

        @Override
        public int width(String text)
        {
            return text == null ? 0 : text.length();
        }
    }

    private static final class ProbeTrackpad extends UITrackpad
    {
        private ProbeTrackpad(Consumer<Double> callback)
        {
            super(callback);
        }

        @Override
        protected void updateTextField()
        {
            this.textbox.setText(String.valueOf(this.value));
        }
    }

    private static final class ProbeSlider extends UISliderTrackpad
    {
        private ProbeSlider(Consumer<Double> callback)
        {
            super(callback);
        }

        private void dragValue(double value)
        {
            this.applyValue(value);
        }

        @Override
        protected void updateTextField()
        {
            this.textbox.setText(String.valueOf(this.value));
        }
    }

    private static final class ProbeContainer extends UIElement
    {
        private IUIElement dispatch(int event)
        {
            return switch (event)
            {
                case 0 -> this.childrenMouseClicked(null);
                case 1 -> this.childrenMouseScrolled(null);
                case 2 -> this.childrenMouseReleased(null);
                case 3 -> this.childrenKeyPressed(null);
                default -> this.childrenTextInput(null);
            };
        }
    }

    private static final class MutatingChild implements IUIElement
    {
        private final UIElement parent;

        private MutatingChild(UIElement parent)
        {
            this.parent = parent;
        }

        @Override
        public boolean isEnabled()
        {
            return true;
        }

        private IUIElement removeSiblings()
        {
            this.parent.removeAll();

            return null;
        }

        @Override
        public IUIElement mouseClicked(UIContext context) { return this.removeSiblings(); }

        @Override
        public IUIElement mouseScrolled(UIContext context) { return this.removeSiblings(); }

        @Override
        public IUIElement mouseReleased(UIContext context) { return this.removeSiblings(); }

        @Override
        public IUIElement keyPressed(UIContext context) { return this.removeSiblings(); }

        @Override
        public IUIElement textInput(UIContext context) { return this.removeSiblings(); }
    }
}
