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
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelinePanel;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
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

public class UIKeyframeEditor extends UITimelinePanel
{
    public static final int[] COLORS = {Colors.RED, Colors.GREEN, Colors.BLUE, Colors.CYAN, Colors.MAGENTA, Colors.YELLOW, Colors.LIGHTEST_GRAY & 0xffffff, Colors.DEEP_PINK};

    private static final int DEFAULT_PROPERTIES_WIDTH = 140;
    private static final int MIN_PROPERTIES_WIDTH = 80;
    private static final int MAX_PROPERTIES_WIDTH = 480;
    private static final int MIN_TIMELINE_WIDTH = 100;
    private static final int SPLITTER_WIDTH = 6;

    public UIKeyframes view;
    public UIKeyframeFactory editor;

    private UIDraggable splitter;
    private int propertiesWidth = DEFAULT_PROPERTIES_WIDTH;
    private long editorGeneration;

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

    @Override
    public void removeFromParent()
    {
        /* A queued replacement may still be waiting for the current input dispatch to finish. */
        this.editorGeneration++;
        super.removeFromParent();
    }

    public UIKeyframeEditor target(UIElement target)
    {
        this.setTarget(target);
        this.setEmptyState(this::getEmptyLabel);

        this.view.resetFlex().full(this).w(1F);
        this.updateSplitterState();

        return this;
    }

    /** Compatibility adapter: the dock slot now reserves the drag strip above this target. */
    public UIKeyframeEditor editPanelTopOffset(Supplier<Integer> supplier)
    {
        return this;
    }

    @Override
    protected UIElement getPropertiesPanel()
    {
        return this.editor;
    }

    @Override
    protected UIElement getTimeline()
    {
        return this.view;
    }

    private IKey getEmptyLabel()
    {
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (!sheet.channel.isEmpty()) return UIKeys.KEYFRAMES_EMPTY_PICK;
        }
        return UIKeys.KEYFRAMES_EMPTY_ADD;
    }

    private void pickKeyframe(Keyframe keyframe)
    {
        UIKeyframeFactory previous = this.editor;

        if (previous != null)
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
                replacement.relative(this.target).x(0).y(0).w(1F).h(1F);
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
            if (previous != null)
            {
                previous.removeFromParent();
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

            if (replacement != null && replacement.getParent() != (this.target == null ? this : this.target))
            {
                this.attachPropertiesPanel(replacement, this.propertiesWidth);
                this.moveToFront(this.splitter);
            }

            if (replacement != null)
            {
                replacement.setVisible(this.propertiesVisible);

                if (this.target != null)
                {
                    this.target.resize();
                    replacement.resize();
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



    @Override
    public void setPropertiesVisible(boolean visible)
    {
        super.setPropertiesVisible(visible);
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
            this.editor.relative(this.target).x(0).y(0).w(1F).h(1F);
            this.target.resize();
            this.resize();
        }
    }

    public void setChannel(KeyframeChannel channel, int color)
    {
        this.view.removeAllSheets();
        this.view.addSheet(new UIKeyframeSheet(color, channel, null));

        this.pickKeyframe(null);
    }

    public void setClip(KeyframeClip clip)
    {
        this.view.removeAllSheets();

        for (int i = 0; i < clip.channels.length; i++)
        {
            KeyframeChannel channel = clip.channels[i];

            this.view.addSheet(new UIKeyframeSheet(COLORS[i], channel, null));
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

    /** Pose tracks name the picked bone; other targets use the timeline title, including user renames. */
    public String getTargetLabel()
    {
        if (this.editor instanceof UIPoseKeyframeFactory pose)
        {
            String bone = pose.poseEditor.groups.list.getCurrentFirst();

            if (bone != null && !bone.isEmpty())
            {
                return bone;
            }
        }

        UIKeyframeSheet sheet = this.editor == null ? null : this.getSheet(this.editor.getKeyframe());

        return sheet == null || sheet.title == null ? null : sheet.title.get();
    }

    /** Preserve the complete reference frame for the anchor's gizmo and drag. */
    public TransformSpace getAnchorSpace()
    {
        return this.editor instanceof UIAnchorKeyframeFactory anchor
            ? anchor.transform.getSpace()
            : TransformSpace.LOCAL;
    }

    /** Property-less IK/pole/physics targets share the Anchor value type but are not form anchors. */
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
