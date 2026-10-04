package mchorse.bbs_mod.ui.framework.elements.input.keyframes;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseTransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UITransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockStyleRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class UIKeyframeEditor extends UIElement
{
    public static final int[] COLORS = {Colors.RED, Colors.GREEN, Colors.BLUE, Colors.CYAN, Colors.MAGENTA, Colors.YELLOW, Colors.LIGHTEST_GRAY & 0xffffff, Colors.DEEP_PINK};
    private static final int EDIT_PANEL_TOP_OFFSET_PX = 20;

    private static final int DEFAULT_PROPERTIES_WIDTH = 140;
    private static final int MIN_PROPERTIES_WIDTH = 80;
    private static final int MAX_PROPERTIES_WIDTH = 480;
    private static final int MIN_TIMELINE_WIDTH = 100;
    private static final int SPLITTER_WIDTH = 6;

    public UIKeyframes view;
    public UIKeyframeFactory editor;

    private UIElement target;
    private UIDraggable splitter;
    private int propertiesWidth = DEFAULT_PROPERTIES_WIDTH;
    private Supplier<Integer> editPanelTopOffsetPx;
    private long editorGeneration;
    private boolean timelineVisible = true;
    private boolean propertiesVisible = true;

    public UIKeyframeEditor(Function<Consumer<Keyframe>, UIKeyframes> factory)
    {
        this.view = factory.apply(this::pickKeyframe);
        this.view.changed(() ->
        {
            if (this.editor != null)
            {
                this.editor.update();
            }
        });

        this.propertiesWidth = BBSSettings.editorKeyframePanelWidth != null
            ? BBSSettings.editorKeyframePanelWidth.get()
            : DEFAULT_PROPERTIES_WIDTH;
        this.splitter = this.createSplitter();

        this.add(this.view.full(this).w(1F, -this.propertiesWidth));
        this.add(this.splitter);
    }

    /**
     * The parameters panel is parented to {@link #target}, not to this editor, so nothing would take
     * it down when this editor is dropped &mdash; it would stay in the edit area, clickable, and the
     * next editor would stack its own panel on top of it.
     */
    @Override
    public void removeFromParent()
    {
        super.removeFromParent();

        if (this.editor != null)
        {
            this.editor.removeFromParent();
        }
    }

    public UIKeyframeEditor target(UIElement target)
    {
        this.target = target;

        this.view.resetFlex().full(this).w(1F);
        this.updateSplitterState();

        return this;
    }

    public UIKeyframeEditor editPanelTopOffset(Supplier<Integer> supplier)
    {
        this.editPanelTopOffsetPx = supplier;

        return this;
    }

    private int getEditPanelTopOffsetPx()
    {
        return this.editPanelTopOffsetPx == null ? EDIT_PANEL_TOP_OFFSET_PX : this.editPanelTopOffsetPx.get();
    }

    private void pickKeyframe(Keyframe keyframe)
    {
        UIKeyframeFactory previous = this.editor;

        if (previous != null && previous.getParent() == this)
        {
            UIKeyframeFactory.saveScroll(previous);
        }

        long generation = this.editorGeneration == Long.MAX_VALUE
            ? 1L
            : this.editorGeneration + 1L;

        this.editorGeneration = generation;
        this.editor = null;

        UIKeyframeFactory replacement = null;

        if (keyframe != null)
        {
            replacement = UIKeyframeFactory.createPanel(keyframe, this.view);
            this.editor = replacement;

            if (replacement != null && this.target != null)
            {
                int top = this.getEditPanelTopOffsetPx();

                replacement.relative(this.target).x(0).y(0, top).w(1F).h(1F, -top);
            }
            else if (replacement != null)
            {
                /* Embedded camera clip editors own the right-side inspector. Set
                 * its full bounds explicitly; a fresh keyframe factory has no
                 * default height, so omitting h(1F) leaves the value controls
                 * mounted at zero height. */
                replacement.relative(this)
                    .x(1F, -this.propertiesWidth)
                    .y(0)
                    .w(this.propertiesWidth)
                    .h(1F);
            }
        }

        this.replaceEditor(previous, replacement, generation);
    }

    private void replaceEditor(UIKeyframeFactory previous, UIKeyframeFactory replacement, long generation)
    {
        Runnable mutation = () ->
        {
            if (previous != null && previous.getParent() == this)
            {
                this.remove(previous);
            }

            if (generation != this.editorGeneration || this.editor != replacement)
            {
                return;
            }

            for (UIKeyframeFactory mounted : new ArrayList<>(this.getChildren(UIKeyframeFactory.class)))
            {
                if (mounted != replacement && mounted.getParent() == this)
                {
                    this.remove(mounted);
                }
            }

            if (replacement != null && replacement.getParent() != this)
            {
                this.add(replacement);
                this.moveToFront(this.splitter);
            }

            if (replacement != null)
            {
                replacement.setVisible(this.propertiesVisible);

                if (this.target != null)
                {
                    this.target.resize();
                }
            }

            this.resize();

            if (replacement != null
                && generation == this.editorGeneration
                && this.editor == replacement)
            {
                replacement.restoreScroll();
            }
        };

        UIContext context = this.getContext();

        if (context == null)
        {
            mutation.run();
        }
        else if (previous == null)
        {
            context.menu.runAfterHierarchyMutation(mutation);
        }
        else
        {
            context.menu.runAfterHierarchyMutation(mutation, previous);
        }
    }

    public void setTimelineVisible(boolean visible)
    {
        this.timelineVisible = visible;
        this.view.setVisible(visible);
    }

    public void setPropertiesVisible(boolean visible)
    {
        this.propertiesVisible = visible;

        if (this.editor != null)
        {
            this.editor.setVisible(visible);
        }

        this.updateSplitterState();
    }

    /* Properties splitter (embedded, non-target mode only) */

    private UIDraggable createSplitter()
    {
        UIDraggable handle = new UIDraggable(this::dragPropertiesSplitter)
            .enabled(() -> BBSSettings.editorResizablePanels.get())
            .cursors(GLFW.GLFW_HRESIZE_CURSOR, GLFW.GLFW_HRESIZE_CURSOR)
            .dragEnd(this::savePropertiesWidth)
            .rendering((context) -> UIDockStyleRenderer.renderSplitter(context, this.splitter.area, false, this.splitter.isDragging() || this.splitter.area.isInside(context), false));

        handle.relative(this).x(1F, -(this.propertiesWidth + SPLITTER_WIDTH / 2)).y(0).w(SPLITTER_WIDTH).h(1F);

        return handle;
    }

    private void updateSplitterState()
    {
        this.splitter.setVisible(this.target == null && this.propertiesVisible);
    }

    private void dragPropertiesSplitter(UIContext context)
    {
        int max = Math.max(MIN_PROPERTIES_WIDTH, Math.min(MAX_PROPERTIES_WIDTH, this.area.w - MIN_TIMELINE_WIDTH));
        int width = MathUtils.clamp(this.area.ex() - context.mouseX, MIN_PROPERTIES_WIDTH, max);

        if (width != this.propertiesWidth)
        {
            this.propertiesWidth = width;
            this.applyPropertiesWidth();
        }
    }

    private void applyPropertiesWidth()
    {
        this.view.w(1F, -this.propertiesWidth);
        this.splitter.x(1F, -(this.propertiesWidth + SPLITTER_WIDTH / 2));

        if (this.editor != null && this.target == null)
        {
            this.editor.x(1F, -this.propertiesWidth).w(this.propertiesWidth);
        }

        this.resize();
    }

    private void savePropertiesWidth()
    {
        if (BBSSettings.editorKeyframePanelWidth != null)
        {
            BBSSettings.editorKeyframePanelWidth.set(this.propertiesWidth);
        }
    }

    public void refreshEditPanelOffset()
    {
        if (this.editor != null && this.target != null)
        {
            int top = this.getEditPanelTopOffsetPx();

            this.editor.relative(this.target).x(0).y(0, top).w(1F).h(1F, -top);
            this.target.resize();
            this.resize();
        }
    }

    public void setChannel(KeyframeChannel channel, int color)
    {
        this.view.removeAllSheets();
        this.view.addSheet(new UIKeyframeSheet(color, false, channel, null));

        this.pickKeyframe(null);
    }

    public void setClip(KeyframeClip clip)
    {
        this.view.removeAllSheets();

        for (int i = 0; i < clip.channels.length; i++)
        {
            KeyframeChannel channel = clip.channels[i];

            this.view.addSheet(new UIKeyframeSheet(COLORS[i], false, channel, null));
        }

        this.pickKeyframe(null);
    }

    public UIKeyframeSheet getSheet(Keyframe keyframe)
    {
        if (keyframe == null)
        {
            return null;
        }

        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (sheet.channel == keyframe.getParent())
            {
                return sheet;
            }
        }

        return null;
    }

    public Pair<String, Boolean> getBone()
    {
        UIKeyframeFactory editor = this.editor;
        String bone = null;
        boolean local = false;

        if (editor instanceof UIPoseKeyframeFactory pose)
        {
            UIKeyframeSheet sheet = this.getSheet(editor.getKeyframe());
            String currentFirst = pose.poseEditor.groups.list.getCurrentFirst();

            if (sheet != null)
            {
                String id = StringUtils.fileName(sheet.id);

                if (id.startsWith("pose"))
                {
                    PerLimbService.PoseBonePath path = PerLimbService.parsePoseBonePath(sheet.id);
                    if (path != null)
                        bone = path.formPath().isEmpty() ? currentFirst : path.formPath() + "/" + currentFirst;
                    else
                    {
                        int i = sheet.id.lastIndexOf('/');
                        bone = i >= 0 ? sheet.id.substring(0, i + 1) + currentFirst : currentFirst;
                    }
                    local = pose.poseEditor.transform.isLocal();
                }
            }
        }
        else if (editor instanceof UITransformKeyframeFactory transform)
        {
            UIKeyframeSheet sheet = this.getSheet(editor.getKeyframe());

            if (sheet != null)
            {
                String id = StringUtils.fileName(sheet.id);

                PerLimbService.PoseBonePath poseBonePath = PerLimbService.parsePoseBonePath(sheet.id);

                if (poseBonePath != null)
                {
                    bone = poseBonePath.formPath().isEmpty() ? poseBonePath.bone() : poseBonePath.formPath() + "/" + poseBonePath.bone();
                    local = transform.transform.isLocal();
                }
                else if (id.startsWith("transform"))
                {
                    int i = sheet.id.lastIndexOf('/');

                    bone = i >= 0 ? sheet.id.substring(0, i) : "";
                    local = transform.transform.isLocal();
                }
            }
        }
        else if (editor instanceof UIPoseTransformKeyframeFactory poseTransform)
        {
            UIKeyframeSheet sheet = this.getSheet(editor.getKeyframe());

            if (sheet != null)
            {
                PerLimbService.PoseBonePath poseBonePath = PerLimbService.parsePoseBonePath(sheet.id);

                if (poseBonePath != null)
                {
                    bone = poseBonePath.formPath().isEmpty() ? poseBonePath.bone() : poseBonePath.formPath() + "/" + poseBonePath.bone();
                    local = poseTransform.transform.isLocal();
                }
            }
        }

        if (bone != null)
        {
            return new Pair<>(bone, local);
        }

        return null;
    }

    /** The space of the active editable transform (mirrors
     *  {@code UIReplaysEditorUtils.getEditableTransform}'s dispatch — the bone
     *  tracks AND the form anchor), so the film gizmo is drawn in the very space
     *  its drag operates in. */
    public TransformSpace getBoneSpace()
    {
        UIKeyframeFactory editor = this.editor;

        if (editor instanceof UIPoseKeyframeFactory pose)
        {
            return pose.poseEditor.transform.getSpace();
        }
        else if (editor instanceof UITransformKeyframeFactory transform)
        {
            return transform.transform.getSpace();
        }
        else if (editor instanceof UIPoseTransformKeyframeFactory poseTransform)
        {
            return poseTransform.transform.getSpace();
        }
        else if (editor instanceof UIAnchorKeyframeFactory anchor)
        {
            return anchor.transform.getSpace();
        }

        return TransformSpace.LOCAL;
    }

    /**
     * Whether the active editor is the form's "anchor" property track — the one
     * that re-parents the whole form to another replay's attachment and carries
     * a {@link mchorse.bbs_mod.utils.pose.Transform} offset the gizmo can edit.
     * The IK/pole/physics target tracks reuse the {@code Anchor} value type but
     * are created without a backing property, so the {@code property != null}
     * test excludes them; the {@code "anchor"} id keeps it to the root form's
     * track, whose placement {@link mchorse.bbs_mod.film.BaseFilmController}
     * resolves from the entity's own {@code form.anchor}.
     */
    public boolean isFormAnchorTrack()
    {
        if (!(this.editor instanceof UIAnchorKeyframeFactory))
        {
            return false;
        }

        UIKeyframeSheet sheet = this.getSheet(this.editor.getKeyframe());

        return sheet != null && sheet.property != null && "anchor".equals(sheet.id);
    }

    /** Whether the anchor gizmo should be oriented in the bone's local space (mirrors {@link #getBone()}'s flag). */
    public boolean getAnchorLocal()
    {
        return this.editor instanceof UIAnchorKeyframeFactory factory && factory.transform.isLocal();
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);

        KeyframeState state = new KeyframeState();

        state.extra = data.getMap("extra");

        for (BaseType type : data.getList("selection"))
        {
            state.selected.add(DataStorageUtils.intListFromData(type));
        }

        this.view.applyState(state);
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);

        KeyframeState keyframeState = this.view.cacheState();
        ListType selection = new ListType();

        for (List<Integer> integers : keyframeState.selected)
        {
            selection.add(DataStorageUtils.intListToData(integers));
        }

        data.put("extra", keyframeState.extra);
        data.put("selection", selection);
    }
}
