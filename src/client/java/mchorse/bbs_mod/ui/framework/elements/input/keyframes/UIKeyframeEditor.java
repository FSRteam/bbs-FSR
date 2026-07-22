package mchorse.bbs_mod.ui.framework.elements.input.keyframes;

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
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class UIKeyframeEditor extends UIElement
{
    public static final int[] COLORS = {Colors.RED, Colors.GREEN, Colors.BLUE, Colors.CYAN, Colors.MAGENTA, Colors.YELLOW, Colors.LIGHTEST_GRAY & 0xffffff, Colors.DEEP_PINK};

    /** Fixed top offset (px) for the parameters panel when target is set (space for drag icon). */
    private static final int EDIT_PANEL_TOP_OFFSET_PX = 20;

    public UIKeyframes view;
    public UIKeyframeFactory editor;

    private UIElement target;
    private Supplier<Integer> editPanelTopOffsetPx;
    /** Monotonically identifies the latest deferred property-panel replacement. */
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

        this.add(this.view.full(this).w(1F, -140));
    }

    public UIKeyframeEditor target(UIElement target)
    {
        this.target = target;

        this.view.resetFlex().full(this).w(1F);

        return this;
    }

    /** Optional: supply top offset in px for the parameters panel (e.g. 0 when layout locked). */
    public UIKeyframeEditor editPanelTopOffset(Supplier<Integer> supplier)
    {
        this.editPanelTopOffsetPx = supplier;
        return this;
    }

    private int getEditPanelTopOffsetPx()
    {
        return this.editPanelTopOffsetPx != null ? this.editPanelTopOffsetPx.get() : EDIT_PANEL_TOP_OFFSET_PX;
    }

    private void pickKeyframe(Keyframe keyframe)
    {
        UIKeyframeFactory previous = this.editor;

        /* A replacement can be known to this field before the hierarchy
         * barrier has mounted it.  Saving only a physically mounted panel
         * keeps an intermediate A -> B -> C pick from overwriting A's scroll
         * position with B's not-yet-laid-out viewport. */
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
                replacement.relative(this).x(1F, -140).w(140).h(1F);
            }
        }

        this.replaceEditor(previous, replacement, generation);
    }

    /** Commit one latest-wins property-panel replacement after input dispatch. */
    private void replaceEditor(
            UIKeyframeFactory previous,
            UIKeyframeFactory replacement,
            long generation
    )
    {
        Runnable mutation = () ->
        {
            /* Release the old physical attachment before admitting the new
             * generation.  The identity fence below makes older queued
             * replacements unable to add/layout/restore a stale panel. */
            if (previous != null && previous.getParent() == this)
            {
                this.remove(previous);
            }

            if (generation != this.editorGeneration || this.editor != replacement)
            {
                return;
            }

            /* A prior generation may have been queued before its add ran.
             * Remove every directly mounted factory so the latest generation
             * is the only property panel left in this editor. */
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
    }

    /** Re-applies edit panel position (e.g. after layout lock toggle). */
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

    /** Whether the active keyframe edits the root form anchor rather than an IK/physics anchor value. */
    public boolean isFormAnchorTrack()
    {
        if (!(this.editor instanceof UIAnchorKeyframeFactory))
        {
            return false;
        }

        UIKeyframeSheet sheet = this.getSheet(this.editor.getKeyframe());

        return sheet != null && sheet.property != null && "anchor".equals(sheet.id);
    }

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
