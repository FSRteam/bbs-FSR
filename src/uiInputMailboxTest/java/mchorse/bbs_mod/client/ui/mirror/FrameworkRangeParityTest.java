package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.IUITreeEventListener;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.input.UINumericInput;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIDoubleKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UITabStrip;
import mchorse.bbs_mod.ui.framework.elements.layout.ILayoutSource;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.bones.UIBonePickerContextMenu;
import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;
import mchorse.bbs_mod.ui.utils.renderers.InputRenderer;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Executing regressions for the FS framework default, layout and binding changes. */
public final class FrameworkRangeParityTest
{
    private FrameworkRangeParityTest()
    {}

    public static void runAll()
    {
        long inputLease = BBSUiRemoteHeldState.install(0x4652414D45574F52L,
            new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), 0));
        Map<Field, Object> settings = new HashMap<>();

        try
        {
            for (Field field : BBSSettings.class.getFields())
            {
                if (BaseValue.class.isAssignableFrom(field.getType()))
                {
                    settings.put(field, field.get(null));
                }
            }

            BBSSettings.register(new SettingsBuilder(null, "framework-test", null));
            minimalElementsUseInterfaceDefaults();
            invalidationResizesTheLayoutOwner();
            descendantsAndUndoAreVisitedOnce();
            listenerFreeTreesSkipScanning();
            numericGesturesPauseValueBindings(new UITrackpad());
            numericGesturesPauseValueBindings(new UISliderTrackpad().limit(0D, 10D));
            hiddenPointerRoutesExclusivelyToItsOwner();
            keyframePropertiesUseTheSharedMotionAndTimeOwners();
            emptyTracksUseTheirVisibleSeed();
            colorPopupUsesLiveSharedTabs();
            wrappedTextSettlesItsLayout();
            dockTabsActivateOnlyAfterRelease();
            previewUsesWorldDistanceAndProportionalZoom();
            attachmentTreeKeepsStableNestedPaths();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not initialize framework parity settings", exception);
        }
        finally
        {
            BBSUiRemoteHeldState.clear(inputLease);

            for (Map.Entry<Field, Object> setting : settings.entrySet())
            {
                try
                {
                    setting.getKey().set(null, setting.getValue());
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore framework parity settings", exception);
                }
            }
        }

        System.out.println("FrameworkRangeParityTest passed");
    }

    private static void minimalElementsUseInterfaceDefaults()
    {
        IUIElement empty = new IUIElement() {};
        empty.resize();
        empty.render(null);
        empty.mouseCanceled(null);
        check(!empty.isEnabled() && empty.isVisible() && !empty.canBeRendered(new Area()),
            "minimal elements changed the default input, visibility or render contract");
        check(empty.mouseClicked(null) == null && empty.mouseScrolled(null) == null
                && empty.mouseReleased(null) == null && empty.keyPressed(null) == null
                && empty.textInput(null) == null,
            "minimal elements claimed an input event");
        AtomicInteger paints = new AtomicInteger();
        UIRenderable renderable = new UIRenderable(context -> paints.incrementAndGet());
        renderable.render(null);
        check(renderable.canBeRendered(new Area()) && !renderable.isEnabled() && paints.get() == 1,
            "render-only elements did not inherit the shared defaults");
        IUIElement keyOnly = new IUIElement()
        {
            @Override
            public IUIElement keyPressed(UIContext context)
            {
                return this;
            }
        };
        check(keyOnly.keyPressed(null) == keyOnly && keyOnly.mouseClicked(null) == null,
            "key-only elements could not override one input method");
    }

    private static void invalidationResizesTheLayoutOwner()
    {
        UIBaseMenu menu = new UIBaseMenu() {};
        UIElement row = new UIElement();
        row.set(10, 20, 120, 70);
        row.row(4);
        UIElement first = new UIElement();
        UIElement second = new UIElement();
        first.set(0, 0, 30, 20);
        second.set(0, 0, 30, 20);
        row.add(first, second);
        menu.main.add(row);
        menu.resize(300, 200);
        menu.context.flushLayout();
        check(first.area.x == 10 && second.area.x == 44, "initial row layout is invalid");

        first.add(new UIElement());
        menu.context.flushLayout();
        check(first.area.x == 10 && second.area.x == 44,
            "a changed row child was resized using its parent's stale running cursor");
        UIElement inserted = new UIElement();
        inserted.set(0, 0, 15, 20);
        row.addAfter(first, inserted);
        menu.context.flushLayout();
        check(inserted.area.x == 44 && second.area.x == 63,
            "addAfter did not invalidate the real row owner");

        UIElement column = new UIElement();
        column.set(150, 20, 70, 120);
        column.column(4).vertical();
        UIElement top = new UIElement();
        UIElement bottom = new UIElement();
        top.set(0, 0, 30, 20);
        bottom.set(0, 0, 30, 20);
        column.add(top, bottom);
        menu.main.add(column);
        menu.context.flushLayout();
        check(top.area.y == 20 && bottom.area.y == 44, "initial column layout is invalid");
        top.add(new UIElement());
        menu.context.flushLayout();
        check(top.area.y == 20 && bottom.area.y == 44,
            "a changed column child was resized using its parent's stale running cursor");

        UISection section = new UISection(IKey.constant("Fold"));
        UIElement nested = new UIElement();
        nested.column(4).vertical();
        nested.add(section);
        column.removeAll();
        column.add(nested);
        menu.context.flushLayout();
        int sectionY = section.area.y;
        section.setExpanded(false);
        check(section.area.y == sectionY, "folding a section advanced a nested column's layout cursor");
    }

    private static void descendantsAndUndoAreVisitedOnce()
    {
        UIElement root = new UIElement();
        UIElement branch = new UIElement();
        UndoElement leaf = new UndoElement();
        branch.add(leaf);
        root.add(branch);
        check(root.getChildren(UIElement.class, new ArrayList<>(), true).equals(List.of(root, branch, leaf)),
            "include-self traversal repeated descendants");
        List<UIElement> visited = new ArrayList<>();
        root.visitChildren(UIElement.class, true, visited::add);
        check(visited.equals(List.of(root, branch, leaf)), "visitor repeated descendants");
        MapType data = root.collectAllUndoData();
        root.applyAllUndoData(data);
        check(leaf.collected == 1 && leaf.applied == 1,
            "the actual undo consumer processed a nested element more than once");
    }

    private static void listenerFreeTreesSkipScanning()
    {
        UIElement holder = new UIElement();
        TreeProbe empty = new TreeProbe();
        empty.add(new UIElement());
        holder.add(empty);
        holder.remove(empty);
        check(empty.scans == 0, "listener-free attach/removal still scans the subtree");
        ListenerElement listener = new ListenerElement();
        empty.add(listener);
        listener.added = listener.removed = 0;
        holder.add(empty);
        check(listener.added == 1 && empty.scans == 1,
            "adding a nested listener did not propagate the tree flag to ancestors");
        holder.remove(empty);
        check(listener.removed == 1 && empty.scans == 2,
            "listener-carrying removal skipped or duplicated notifications");
    }

    private static void numericGesturesPauseValueBindings(UINumericInput<?> input)
    {
        UIElement parent = new UIElement();
        AtomicInteger reads = new AtomicInteger();
        parent.valueBinding(reads::incrementAndGet);
        input.set(0, 0, 100, 20);
        input.resize();
        input.setVisible(false);
        parent.add(input);
        UIContext context = new UIContext(null);
        context.setMouse(40, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(input.mouseClicked(context) == input && input.isDragging() && parent.isUserEditing(),
            "a numeric drag was not exposed as editing to its bound parent");
        parent.render(context);
        check(reads.get() == 0, "a value binding reread its property during a numeric drag");
        context.mouseButton = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        input.mouseReleased(context);
        check(parent.isUserEditing(), "a foreign release ended the numeric editing state");
        context.setMouse(150, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        input.mouseReleased(context);
        context.unfocus();
        check(!input.isDragging() && !parent.isUserEditing(), "numeric editing remained after the owned release");
        parent.render(context);
        check(reads.get() == 1, "the value binding did not resume after numeric editing");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void hiddenPointerRoutesExclusivelyToItsOwner()
    {
        AtomicInteger ownerEvents = new AtomicInteger();
        AtomicInteger treeEvents = new AtomicInteger();
        IUIElement owner = new IUIElement()
        {
            @Override
            public IUIElement mouseClicked(UIContext context) { ownerEvents.incrementAndGet(); return this; }
            @Override
            public IUIElement mouseScrolled(UIContext context) { ownerEvents.incrementAndGet(); return this; }
            @Override
            public IUIElement mouseReleased(UIContext context) { ownerEvents.incrementAndGet(); return this; }
        };
        UIBaseMenu menu = new UIBaseMenu()
        {
            @Override
            public IUIElement getPointerOwner() { return owner; }
        };
        menu.main.add(new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context) { treeEvents.incrementAndGet(); return true; }
            @Override
            protected boolean subMouseScrolled(UIContext context) { treeEvents.incrementAndGet(); return true; }
            @Override
            protected boolean subMouseReleased(UIContext context) { treeEvents.incrementAndGet(); return true; }
        });
        check(menu.mouseClicked(1, 2, 0) && menu.mouseScrolled(3, 4, 0, 1) && menu.mouseReleased(5, 6, 0)
                && menu.isPointerHidden() && ownerEvents.get() == 3 && treeEvents.get() == 0,
            "a hidden pointer dispatched a flight gesture to the visible interface");
        boolean keystrokes = BBSSettings.enableKeystrokeRendering.get();
        try
        {
            BBSSettings.enableKeystrokeRendering.set(false);
            new InputRenderer().render(menu, 5, 6);
        }
        finally
        {
            BBSSettings.enableKeystrokeRendering.set(keystrokes);
        }
    }

    private static void keyframePropertiesUseTheSharedMotionAndTimeOwners()
    {
        boolean snapping = BBSSettings.editorSnapToTicks.get();
        boolean seconds = BBSSettings.editorSeconds.get();
        UIKeyframes editor = new UIKeyframes(key -> {});
        KeyframeChannel<Double> channel = new KeyframeChannel<>("value", KeyframeFactories.DOUBLE);
        Keyframe<Double> key = channel.get(channel.insert(0F, 3D));
        UIKeyframeSheet sheet = new UIKeyframeSheet(0xffffff, false, channel, null);
        sheet.selection.add(key);
        editor.getGraph().getSheets().add(sheet);
        NumericFactory panel = new NumericFactory(key, editor);
        try
        {
            check(panel.getChildren(UITrackpad.class).contains(panel.motionShift),
                "motion-shift controls are not attached to the actual keyframe property panel");
            panel.motionShift.setValueAndNotify(25D);
            check(key.getMotionShift() == 0.25F, "motion-shift property edits did not reach the selected key");
            BBSSettings.editorSnapToTicks.set(false);
            BBSSettings.editorSeconds.set(false);
            panel.setTick(2.375D);
            check(key.getTick() == 2.375F, "the tick property discarded unsnapped fractional ticks");
            BBSSettings.editorSeconds.set(true);
            panel.setTick(0.375D);
            check(key.getTick() == 7.5F, "the seconds property discarded unsnapped fractional ticks");
            BBSSettings.editorSnapToTicks.set(true);
            BBSSettings.editorSeconds.set(false);
            panel.setTick(2.375D);
            check(key.getTick() == 2F, "the tick property ignored enabled snapping");
            panel.set(0, 0, 240, 320);
            panel.resize();
            UIContext context = new UIContext(null);
            context.setMouse(panel.numeric().area.mx(), panel.numeric().area.my(), 0);
            check(panel.numeric().mouseClicked(context) == panel.numeric() && panel.numeric().isDragging(),
                "numeric factory fixture did not begin its real drag");
            double beforeDrag = panel.numeric().getValue();
            key.setValue(9D);
            panel.update();
            check(panel.numeric().getValue() == beforeDrag, "playhead refresh overwrote an active numeric gesture");
            context.setMouse(150, 10, 0);
            panel.numeric().mouseReleased(context);
            context.unfocus();
            panel.update();
            check(panel.numeric().getValue() == 9D, "numeric property refresh did not resume after the gesture");

            UIKeyframeFactory.register(KeyframeFactories.DOUBLE, NumericFactory::new);
            UIKeyframeFactory.setup();
            check(UIKeyframeFactory.createPanel(key, editor) instanceof NumericFactory,
                "explicit built-in setup overwrote an addon keyframe editor");
        }
        finally
        {
            UIKeyframeFactory.register(KeyframeFactories.DOUBLE, UIDoubleKeyframeFactory::new);
            BBSSettings.editorSnapToTicks.set(snapping);
            BBSSettings.editorSeconds.set(seconds);
        }
    }

    private static void attachmentTreeKeepsStableNestedPaths()
    {
        Form root = new Form() {};
        Form child = new Form() {};
        Form grandchild = new Form() {};
        Form sibling = new Form() {};
        root.trackName.set("Root");
        child.trackName.set("Child");
        grandchild.trackName.set("Grandchild");
        sibling.trackName.set("Sibling");
        BodyPart outer = new BodyPart("");
        BodyPart inner = new BodyPart("");
        BodyPart other = new BodyPart("");
        outer.setForm(child);
        inner.setForm(grandchild);
        other.setForm(sibling);
        root.parts.addBodyPart(outer);
        root.parts.addBodyPart(other);
        child.parts.addBodyPart(inner);
        String childPath = FormUtils.getPath(child);
        String nestedPath = FormUtils.getPath(grandchild);
        String siblingPath = FormUtils.getPath(sibling);
        List<String> keys = List.of("", childPath, nestedPath, siblingPath);
        AttachmentTree tree = new AttachmentTree();
        tree.fillAttachments(root, keys);
        check(tree.getList().equals(keys) && tree.depth(nestedPath) == 2 * UIBoneTreeList.INDENT,
            "stable nested attachments fell back to the flat leftover list");
        root.parts.moveBodyPart(other, 0);
        tree.fillAttachments(root, keys);
        check(tree.getList().equals(List.of("", siblingPath, childPath, nestedPath))
                && tree.depth(nestedPath) == 2 * UIBoneTreeList.INDENT,
            "a body-part reorder changed the attachment path or lost its tree depth");
        List<String> picked = new ArrayList<>();
        UIBonePickerContextMenu picker = new UIBonePickerContextMenu(picked::add).attachments(root, keys);
        picker.bones.set(0, 0, 200, 200);
        picker.bones.resize();
        UIContext context = new UIContext(null);
        int row = picker.bones.getList().indexOf(nestedPath);
        context.setMouse(100, row * picker.bones.rowHeight() + 5, 0);
        picker.bones.mouseClicked(context);
        picker.bones.mouseReleased(context);
        check(picked.equals(List.of(nestedPath)) && FormUtils.getPath(grandchild).equals(nestedPath),
            "the actual attachment picker returned an index path after nested reordering");
    }

    private static void emptyTracksUseTheirVisibleSeed()
    {
        UIKeyframes editor = new UIKeyframes(key -> {});
        KeyframeChannel<Double> channel = new KeyframeChannel<>("seed", KeyframeFactories.DOUBLE);
        UIKeyframeSheet sheet = new UIKeyframeSheet("seed", IKey.constant("Seed"), 0xffffff, false, channel, new ValueDouble("property", 0D));
        sheet.seed(() -> 1D);
        editor.getGraph().getSheets().add(sheet);
        check(editor.getGraph().getFirstTrackSheet() == sheet, "graph fallback did not return the first real track");
        Keyframe key = editor.getGraph().addKeyframe(sheet, 3.5F, null);
        check(key.getValue().equals(1D), "an empty track copied its invisible property instead of its display seed");
        editor.getGraph().applyValue(KeyframeFactories.DOUBLE, 4D, key, true, false);
        check(key.getValue().equals(4D), "shared value application did not reach the selected track");
    }

    private static void colorPopupUsesLiveSharedTabs()
    {
        boolean hsv = BBSSettings.hsvColorPicker.get();
        try
        {
            BBSSettings.hsvColorPicker.set(true);
            UIBaseMenu menu = new UIBaseMenu() {};
            UIColor color = new UIColor(value -> {}).withAlpha();
            color.set(10, 10, 80, 20);
            menu.main.add(color);
            menu.resize(600, 500);
            check(menu.mouseClicked(20, 20, 0) && color.isUserEditing(),
                "opening a color popup did not pause its bound owner");
            menu.context.flushLayout();
            UIElement rgb = color.picker.tabs.getTab(1);
            menu.context.setMouse(rgb.area.mx(), rgb.area.my(), 0);
            check(color.picker.tabs.mouseClicked(menu.context) == color.picker.tabs && !BBSSettings.hsvColorPicker.get(),
                "the actual RGB tab did not switch the color model through UITabStrip");
            menu.context.flushLayout();
            List<UITrackpad> channels = color.picker.fields.getChildren(UITrackpad.class);
            check(channels.size() == 4 && channels.get(0).max == 255D,
                "RGB tabs did not update the mounted numeric channels");
            color.picker.removeFromParent();
            check(!color.isUserEditing(), "closing the color popup left its owner editing");
        }
        finally
        {
            BBSSettings.hsvColorPicker.set(hsv);
        }
    }

    private static void wrappedTextSettlesItsLayout()
    {
        Font previous = Minecraft.getInstance().font;

        try
        {
            setHeadlessFont(new WrappingFont());
            UIBaseMenu menu = new UIBaseMenu() {};
            UIElement column = new UIElement();
            column.set(10, 10, 180, 300);
            column.column(4).stretch().vertical();
            UIText text = new UIText("A wrapped message must move the button below it when its width changes.");
            UIElement button = new UIElement();
            button.h(20);
            column.add(text, button);
            menu.main.add(column);
            menu.resize(400, 400);
            menu.context.flushLayout();
            int wideHeight = text.area.h;
            column.w(70);
            column.invalidateLayout();
            menu.context.flushLayout();
            menu.context.flushLayout();
            check(text.area.h > wideHeight && button.area.y == text.area.ey() + 4,
                "wrapping at a new width did not reposition the following control: " + wideHeight + " -> " + text.area.h + ", width " + text.area.w + ", gap " + (button.area.y - text.area.ey()));
            int settledY = button.area.y;
            menu.context.flushLayout();
            check(button.area.y == settledY, "a repeated layout pass kept shifting wrapped content");
        }
        finally
        {
            setHeadlessFont(previous);
        }
    }

    private static void setHeadlessFont(Font font)
    {
        try
        {
            Field field = Minecraft.class.getDeclaredField("font");
            field.setAccessible(true);
            field.set(Minecraft.getInstance(), font);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("cannot install headless wrapping metrics", exception);
        }
    }


    private static void previewUsesWorldDistanceAndProportionalZoom()
    {
        float smoothness = BBSSettings.editorCameraSmoothness.get();
        try
        {
            BBSSettings.editorCameraSmoothness.set(0F);
            Preview preview = new Preview();
            preview.set(0, 0, 100, 100);
            preview.resize();
            preview.setDistance(20F);
            preview.setupPosition();
            check(Math.abs(preview.camera.position.distance(preview.pos.x, preview.pos.y, preview.pos.z) - 20D) < 0.0001D,
                "preview distance still used the old quadratic Factor units");
            UIContext context = new UIContext(null);
            context.setMouse(40, 40, 0);
            context.mouseWheel = 1D;
            check(preview.mouseScrolled(context) == preview, "preview did not consume proportional zoom");
            preview.smooth();
            preview.setupPosition();
            check(Math.abs(preview.camera.position.distance(preview.pos.x, preview.pos.y, preview.pos.z) - 18D) < 0.0001D,
                "preview zoom did not move by a share of the world distance");
            preview.setRotation(0F, 1000F);
            check(Math.abs(preview.camera.rotation.x) < Math.PI * 0.5D, "preview pitch was allowed through its singularity");
        }
        finally
        {
            BBSSettings.editorCameraSmoothness.set(smoothness);
        }
    }

    private static void dockTabsActivateOnlyAfterRelease()
    {
        UIBaseMenu menu = new UIBaseMenu() {};
        menu.context.render = new UIRenderingContext(new GuiGraphics(Minecraft.getInstance(), null));
        MemoryLayout storage = new MemoryLayout();
        UIDockLayout dock = new UIDockLayout().source(storage);
        dock.addPanel("first", new UIElement(), null);
        dock.addPanel("second", new UIElement(), null);
        dock.full(menu.main);
        menu.main.add(dock);
        dock.mount();
        menu.resize(400, 300);
        menu.context.flushLayout();
        List<UITabStrip> strips = dock.getChildren(UITabStrip.class);
        check(strips.size() == 1 && strips.get(0).getTabCount() == 2,
            "dock stacks did not use the shared tab strip");
        UITabStrip strip = strips.get(0);
        UIElement tab = strip.getTab(1);
        int x = tab.area.mx();
        int y = tab.area.my();
        check(dock.isPanelActive("first") && menu.mouseClicked(x, y, 0) && dock.isPanelActive("first"),
            "a dock tab activated on press before deciding between a click and a drag");
        menu.mouseReleased(x, y, 0);
        menu.context.flushLayout();
        check(dock.isPanelActive("second"), "releasing a dock tab did not activate its panel");
        dock.resize();
        check(strip.getTab(1) == tab && strip.getTabIndex(x, strip.area.ey() + 5) == -1,
            "dock relayout rebuilt stable tabs or accepted a cursor outside their strip");
    }

    private static final class NumericFactory extends UIDoubleKeyframeFactory
    {
        private NumericFactory(Keyframe<Double> keyframe, UIKeyframes editor) { super(keyframe, editor); }
        private UITrackpad numeric() { return this.value; }
    }

    private static final class WrappingFont extends Font
    {
        private WrappingFont() { super(location -> null, false); }
        @Override public int width(String text) { return text.length() * 6; }
        @Override
        public List<FormattedCharSequence> split(FormattedText text, int width)
        {
            List<FormattedCharSequence> lines = new ArrayList<>();
            String string = text.getString();
            int characters = Math.max(1, width / 6);

            for (int start = 0; start < string.length(); start += characters)
            {
                lines.add(Component.literal(string.substring(start, Math.min(string.length(), start + characters))).getVisualOrderText());
            }

            return lines;
        }
    }

    private static final class Preview extends UIModelRenderer
    {
        @Override
        protected void renderUserModel(UIContext context) {}
        private void smooth() { this.applySmoothing(1F); }
    }

    private static final class MemoryLayout implements ILayoutSource
    {
        private final BaseValue value = new ValueDouble("layout", 0D);
        private EditorLayoutNode root = new EditorLayoutNode.StackNode(List.of("first", "second"), "first");
        private Set<String> hidden = Set.of();

        @Override public BaseValue value() { return this.value; }
        @Override public EditorLayoutNode getRoot() { return this.root; }
        @Override public void setRoot(EditorLayoutNode root) { this.root = root; }
        @Override public List<EditorLayoutNode.SplitterNode> getSplitters() { return List.of(); }
        @Override public List<EditorLayoutNode.SplitterNode> getSplittersForWrite() { return List.of(); }
        @Override public EditorLayoutNode getDefault() { return this.root; }
        @Override public Set<String> getHiddenPanels() { return this.hidden; }
        @Override public void setHiddenPanels(Set<String> hidden) { this.hidden = hidden; }
    }

    private static final class AttachmentTree extends UIBoneTreeList
    {
        private AttachmentTree() { super(null); }
        private int depth(String key) { return this.indent(key); }
    }

    private static final class UndoElement extends UIElement
    {
        private int collected;
        private int applied;

        private UndoElement()
        {
            this.setUndoId("leaf");
        }

        @Override
        public void collectUndoData(MapType data)
        {
            this.collected++;
        }

        @Override
        public void applyUndoData(MapType data)
        {
            this.applied++;
        }
    }

    private static final class TreeProbe extends UIElement
    {
        private int scans;

        @Override
        public <T> List<T> getChildren(Class<T> type)
        {
            this.scans++;

            return super.getChildren(type);
        }
    }

    private static final class ListenerElement extends UIElement implements IUITreeEventListener
    {
        private int added;
        private int removed;

        @Override
        public void onAddedToTree(UIElement element)
        {
            this.added++;
        }

        @Override
        public void onRemovedFromTree(UIElement element)
        {
            this.removed++;
        }
    }
}
