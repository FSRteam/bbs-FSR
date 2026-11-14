package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.ui.UISettingsLayout;
import mchorse.bbs_mod.settings.ui.UIValueFactory;
import mchorse.bbs_mod.settings.ui.UIValueMap;
import mchorse.bbs_mod.settings.value.ValueKeyCombo;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.ui.ValueColors;
import mchorse.bbs_mod.settings.values.ui.ValueKeyframeStyle;
import mchorse.bbs_mod.settings.values.ui.ValueOrder;
import mchorse.bbs_mod.settings.values.ui.ValueTrackStyles;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIconToggles;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UINumericInput;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPalette;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_mod.utils.keyframes.KeyframeStyle;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Executing settings reset, binding and palette consumer regressions. */
public final class SettingsUiParityTest
{
    private SettingsUiParityTest()
    {}

    public static void runAll()
    {
        long lease = BBSUiRemoteHeldState.install(0x53455454494E4753L,
            new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), 0));
        Map<Field, Object> saved = new HashMap<>();

        try
        {
            for (Field field : BBSSettings.class.getFields())
            {
                if (BaseValue.class.isAssignableFrom(field.getType())) saved.put(field, field.get(null));
            }

            BBSSettings.register(new SettingsBuilder(null, "settings-ui-test", null));
            keybindResetRetainsTheLiveCombo();
            palettesUseSharedSelectionAndOwnedRelease();
            widgetBindingsReadChangedSettings();
            gizmoStripWritesAllFiveValues();
            mutableStylesAndOrdersKeepIndependentDefaults();
            System.out.println("SettingsUiParityTest passed");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
        finally
        {
            for (Map.Entry<Field, Object> entry : saved.entrySet())
            {
                try
                {
                    entry.getKey().set(null, entry.getValue());
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError(exception);
                }
            }

            BBSUiRemoteHeldState.clear(lease);
        }
    }

    private static void keybindResetRetainsTheLiveCombo()
    {
        KeyCombo live = new KeyCombo(IKey.raw("key"), GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT_CONTROL);
        ValueKeyCombo value = new ValueKeyCombo("key", live);
        BaseType original = value.toData();

        check(value.getDefaultValue() != live && value.isDefault(), "keybind default aliases its live keys");
        value.set(new KeyCombo(IKey.raw("other label"), GLFW.GLFW_KEY_B));
        check(value.get() == live && !value.isDefault(), "rebinding detached the live combo or changed its default");
        value.reset();
        check(value.get() == live && value.isDefault() && original.equals(value.toData()),
            "keybind reset did not restore the declared keys in place");
        value.set(new KeyCombo(IKey.raw("same keys"), GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT_CONTROL));
        check(value.isDefault(), "equivalent key lists were compared by combo identity");
        ListType legacy = new ListType();
        legacy.addInt(-GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        value.fromData(legacy);
        check(!value.isDefault() && value.toData().equals(legacy), "legacy mouse key ids changed during loading");
        value.reset();
        check(value.isDefault() && original.equals(value.toData()), "loading overwrote the keybind's declared default");
    }

    private static void palettesUseSharedSelectionAndOwnedRelease()
    {
        Color a = Color.rgb(0xff0000);
        Color b = Color.rgb(0x00ff00);
        Color c = Color.rgb(0x0000ff);
        Color d = Color.rgb(0xffffff);
        ValueColors values = new ValueColors("palette").limit(4);
        values.setColors(List.of(a, b, c, d));
        check(values.getCurrentColors().get(0) != a, "setting a palette retained caller-owned colors");
        List<Color> live = values.getCurrentColors();
        a = live.get(0);
        b = live.get(1);
        c = live.get(2);
        d = live.get(3);
        List<Color> original = new ArrayList<>(live);
        AtomicInteger pre = new AtomicInteger();
        AtomicInteger post = new AtomicInteger();
        AtomicInteger resized = new AtomicInteger();
        values.preCallback((value, flag) -> pre.incrementAndGet());
        values.postCallback((value, flag) -> post.incrementAndGet());
        Palette palette = new Palette(values);
        palette.editable().sortable().onChanged(resized::incrementAndGet);
        palette.set(0, 0, 40, 20);
        palette.resize();
        UIContext context = new UIContext(null);
        context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(palette.getIndex(context) == 0 && palette.getColor(0) == a,
            "palette hit testing still reverses the displayed value order");
        check(!palette.subMouseScrolled(context), "a fixed-height palette consumed wheel scrolling");
        palette.selection.setAll(List.of(a, c));
        check(palette.subMouseClicked(context) && palette.drag.isPressed(), "palette press did not acquire its drag");
        context.setMouse(39, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        palette.subMouseReleased(context);
        check(palette.drag.isPressed() && live.equals(original), "foreign release committed the palette drag: pressed="
            + palette.drag.isPressed() + ", live=" + live + ", original=" + original);
        context.mouseButton = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        palette.subMouseReleased(context);
        check(!palette.drag.isPressed() && live.equals(List.of(b, d, a, c)) && pre.get() == 1 && post.get() == 1,
            "owned palette release did not reorder the group in one value notification");
        context.setKeyEvent(GLFW.GLFW_KEY_DELETE, 0, GLFW.GLFW_PRESS);
        check(palette.key(context) && live.equals(List.of(b, d)) && palette.selection.isEmpty()
                && resized.get() == 1 && pre.get() == 2 && post.get() == 2,
            "palette Delete bypassed the value owner, selection cleanup or popup resize");
        Palette fixed = new Palette(values);
        fixed.set(0, 0, 40, 20);
        fixed.resize();
        fixed.selection.set(b, fixed);
        check(!fixed.key(context) && live.equals(List.of(b, d)), "a fixed preset palette allowed deletion");
        BaseType saved = values.toData();
        ValueColors loaded = new ValueColors("loaded").limit(4);
        loaded.fromData(saved);
        check(saved.equals(loaded.toData()), "palette reorder/delete changed the legacy saved color format");
        loaded.setColors(loaded.getCurrentColors());
        check(saved.equals(loaded.toData()), "replacing a palette from its current list erased it");

        ValueColors recent = new ValueColors("recent").limit(2);
        Palette recentUI = new Palette(recent);
        recent.addColor(a);
        recent.addColor(b);
        recent.addColor(c);
        check(recent.getCurrentColors().equals(List.of(c, b)) && recentUI.getColor(0).equals(c),
            "the palette reversed its newest-first bounded history");
        recent.addColor(b);
        check(recent.getCurrentColors().equals(List.of(c, b)), "adding an existing color duplicated or reordered history");
    }

    private static void widgetBindingsReadChangedSettings() throws ReflectiveOperationException
    {
        ValueGroup group = new ValueGroup("bindings");
        ValueInt integer = new ValueInt("int", 1);
        ValueFloat floating = new ValueFloat("float", 1F);
        ValueDouble precise = new ValueDouble("double", 1D);
        ValueInt color = new ValueInt("color", 0xff112233);
        ValueString string = new ValueString("string", "before");
        for (BaseValue value : List.of(integer, floating, precise, color, string)) group.add(value);
        UINumericInput<?> integerUI = UIValueFactory.intUI(integer, null);
        UINumericInput<?> floatUI = UIValueFactory.floatUI(floating, null);
        UINumericInput<?> doubleUI = UIValueFactory.doubleUI(precise, null);
        UIColor colorUI = UIValueFactory.colorUI(color, null);
        UITextbox stringUI = UIValueFactory.stringUI(string, null);
        integer.set(7);
        floating.set(2.5F);
        precise.set(9.125D);
        color.set(0xff445566);
        string.set("after");
        for (UIElement element : List.of(integerUI, floatUI, doubleUI, colorUI, stringUI)) readBinding(element);
        check(integerUI.getValue() == 7 && floatUI.getValue() == 2.5 && doubleUI.getValue() == 9.125,
            "numeric settings widgets kept their construction-time values");
        check(colorUI.picker.color.getARGBColor() == 0xff445566 && stringUI.textbox.getText().equals("after"),
            "color/text settings widgets kept their construction-time values");

        UIValueMap.setup();
        UIElement custom = new UIElement();
        UIValueMap.register(TestValue.class, (value, ui) -> List.of(custom));
        UIValueMap.setup();
        check(UIValueMap.create(new TestValue(), new UIElement()).equals(List.of(custom)),
            "repeated widget setup overwrote an addon registration");
    }

    private static void readBinding(UIElement element) throws ReflectiveOperationException
    {
        Field binding = UIElement.class.getDeclaredField("valueBinding");
        binding.setAccessible(true);
        Runnable read = (Runnable) binding.get(element);
        check(read != null, "a settings factory omitted the shared value binding");
        read.run();
    }

    private static void gizmoStripWritesAllFiveValues() throws ReflectiveOperationException
    {
        List<BaseValue> values = List.of(BBSSettings.gizmoShowTranslate, BBSSettings.gizmoShowScale,
            BBSSettings.gizmoShowRotate, BBSSettings.gizmoShowViewRotate, BBSSettings.gizmoShowSphere);
        Field registry = UISettingsLayout.class.getDeclaredField("rows");
        registry.setAccessible(true);
        Object previous = registry.get(null);
        registry.set(null, null);

        try
        {
            UISettingsLayout.IValueRow row = UISettingsLayout.getRow(BBSSettings.gizmoShowTranslate);
            check(row instanceof UISettingsLayout.ToggleStripRow && row.getValues().equals(values),
                "the settings layout did not register all five gizmo values together");
            List<UIIconToggles> strips = row.create(new UIElement()).get(0)
                .getChildren(UIIconToggles.class);
            check(strips.size() == 1, "the registered gizmo row did not create its shared toggle strip");
            UIIconToggles strip = strips.get(0);

            for (int i = 0; i < values.size(); i++)
            {
                strip.setValue(i, false);
                check(!((ValueBoolean) values.get(i)).get(), "a gizmo strip cell did not write its bound value");
            }

            BBSSettings.gizmoShowRotate.set(true);
            check(strip.getValue(2), "the gizmo strip cached an old flag after an external edit");
        }
        finally
        {
            registry.set(null, previous);
        }
    }

    private static void mutableStylesAndOrdersKeepIndependentDefaults()
    {
        ValueOrder order = new ValueOrder("order", "new", "a", "b");
        ListType old = new ListType();
        old.addString("b");
        old.addString("foreign");
        old.addString("a");
        order.fromData(old);
        check(order.get().equals(List.of("new", "b", "a")), "a newly introduced order token lost its default position");
        order.reset();
        order.get().remove(0);
        order.reset();
        check(order.get().equals(List.of("new", "a", "b")), "order reset shared its mutable default list");

        ValueTrackStyles tracks = new ValueTrackStyles("tracks");
        tracks.setName("position", "Custom");
        tracks.setColor("position", 0xff123456);
        ValueTrackStyles loaded = new ValueTrackStyles("loaded");
        loaded.fromData(tracks.toData());
        check(loaded.has("position") && loaded.name("position", "fallback").equals("Custom")
                && loaded.color("position", 0) == 0x123456, "saved track style lost a field or its stable key");
        tracks.reset();
        check(!tracks.has("position") && tracks.isDefault(), "track style edits changed the captured default map");

        ValueKeyframeStyle style = new ValueKeyframeStyle("style");
        KeyframeStyle edited = new KeyframeStyle();
        edited.setShape(KeyframeShape.CIRCLE);
        edited.setFilled(true);
        edited.setColor(Color.rgb(0x123456));
        style.set(edited);
        edited.reset();
        check(style.get().getShape() == KeyframeShape.CIRCLE && style.get().isFilled(),
            "the default-style editor shared its mutable draft with the saved value");
        MapType data = style.toData().asMap();
        ValueKeyframeStyle roundTrip = new ValueKeyframeStyle("roundTrip");
        roundTrip.fromData(data);
        check(roundTrip.get().equals(style.get()), "keyframe style shape/color/fill did not round-trip");
        style.reset();
        check(style.isDefault() && style.get().isDefault(), "keyframe styling changed the captured default style");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Palette extends UIColorPalette
    {
        Palette(ValueColors colors) { super(colors, null); }
        boolean key(UIContext context) { return this.subKeyPressed(context); }
    }

    private static final class TestValue extends ValueString
    {
        TestValue() { super("addon-test", "value"); }
    }
}
