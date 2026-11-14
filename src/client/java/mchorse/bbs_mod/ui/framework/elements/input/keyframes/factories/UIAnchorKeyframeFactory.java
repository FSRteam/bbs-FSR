package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.AnchorRebase;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIconToggles;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.utils.bones.UIBonePickerContextMenu;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class UIAnchorKeyframeFactory extends UIKeyframeFactory<Anchor>
{
    private UIButton actor;
    private UIButton attachment;
    private UIIcon pickActor;
    private UIIcon pickAttachment;
    private UIIconToggles inherit;
    private UIToggle keepTransform;
    public UIPropTransform transform;

    public static void displayActors(UIContext context, Map<String, IEntity> entities, String value, Consumer<String> callback)
    {
        List<UIFilmPanel> children = context.menu.main.getChildren(UIFilmPanel.class);
        UIFilmPanel panel = children.isEmpty() ? null : children.get(0);
        List<Replay> replays = panel != null ? panel.getData().replays.getList() : null;

        context.replaceContextMenu((menu) ->
        {
            menu.action(Icons.CLOSE, UIKeys.GENERAL_NONE, BBSSettings.negativeColor() & Colors.RGB, () -> callback.accept(Anchor.NO_ATTACHMENT));

            if (replays == null)
            {
                return;
            }

            /* Walked by list position, not by the entity map. The rows still show the replay's list
             * position — that is how the animator counts actors — but what the choice hands back
             * (and what the data stores) is the id. Disabled replays have no entity and are skipped. */
            for (int i = 0; i < replays.size(); i++)
            {
                Replay replay = replays.get(i);
                IEntity entity = entities.get(replay.getId());

                if (entity == null)
                {
                    continue;
                }

                String stringLabel = i + " - " + replay.getName();
                IKey label = IKey.constant(stringLabel);

                menu.action(Icons.CLOSE, label, replay.getId().equals(value), () -> callback.accept(replay.getId()));
            }
        });
    }

    public static void displayAttachments(UIFilmPanel panel, String replayId, String value, Consumer<String> consumer)
    {
        IEntity entity = panel.getController().getEntities().get(replayId);

        if (entity == null || entity.getForm() == null)
        {
            return;
        }

        Form form = entity.getForm();
        Set<String> attachments = FormUtilsClient.getRenderer(form).collectMatrices(entity, 0F).keySet();

        if (attachments.isEmpty())
        {
            return;
        }

        /* The picker groups attachments by their form (body part tree) instead of the
         * old alphabetical strip that shuffled every part's bones together. */
        UIBonePickerContextMenu picker = new UIBonePickerContextMenu(consumer);

        picker.attachments(form, attachments).set(value);
        panel.getContext().replaceContextMenu(picker);
    }

    public UIAnchorKeyframeFactory(Keyframe<Anchor> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.actor = new UIButton(UIKeys.GENERIC_KEYFRAMES_ANCHOR_PICK_ACTOR, (b) -> this.displayActors());
        this.attachment = new UIButton(UIKeys.GENERIC_KEYFRAMES_ANCHOR_PICK_ATTACHMENT, (b) ->
        {
            this.getPanel().getController().picker.cancelTargetPick();
            displayAttachments(this.getPanel(), this.getDisplayValue().replay, this.getDisplayValue().attachment, this::setAttachment);
        });
        this.inherit = new UIIconToggles(null)
            .add(Icons.ALL_DIRECTIONS, UIKeys.INHERIT_POSITION, () -> this.getDisplayValue().inheritPosition,
                (v) -> this.retarget((anchor) -> anchor.inheritPosition = v))
            .add(Icons.ORBIT, UIKeys.INHERIT_ROTATION, () -> this.getDisplayValue().inheritRotation,
                (v) -> this.retarget((anchor) -> anchor.inheritRotation = v))
            .add(Icons.SCALE, UIKeys.INHERIT_SCALE, () -> this.getDisplayValue().inheritScale,
                (v) -> this.retarget((anchor) -> anchor.inheritScale = v));
        this.keepTransform = new UIToggle(UIKeys.GENERIC_KEYFRAMES_ANCHOR_KEEP_TRANSFORM,
            (b) -> BBSSettings.anchorKeepTransform.set(b.getValue()));
        this.keepTransform.setValue(BBSSettings.anchorKeepTransform.get());
        this.keepTransform.tooltip(UIKeys.GENERIC_KEYFRAMES_ANCHOR_KEEP_TRANSFORM_TOOLTIP);
        this.transform = new UIAnchorTransforms(this);
        this.transform.enableHotkeys();
        this.transform.setTransform(keyframe.getValue().transform);

        this.pickActor = new UIIcon(Icons.EYEDROPPER, (b) -> this.pickTarget(b, false));
        this.pickAttachment = new UIIcon(Icons.EYEDROPPER, (b) -> this.pickTarget(b, true));
        this.pickActor.wh(16, 16);
        this.pickAttachment.wh(16, 16);
        this.pickActor.highlight(() -> this.getPanel() != null
            && this.getPanel().getController().picker.isPickingTarget(this.pickActor), Direction.BOTTOM);
        this.pickAttachment.highlight(() -> this.getPanel() != null
            && this.getPanel().getController().picker.isPickingTarget(this.pickAttachment), Direction.BOTTOM);
        this.pickActor.tooltip(UIKeys.GENERIC_KEYFRAMES_ANCHOR_PICK_ACTOR);
        this.pickAttachment.tooltip(UIKeys.GENERIC_KEYFRAMES_ANCHOR_PICK_ATTACHMENT);

        this.scroll.add(UI.row(this.actor, this.pickActor), UI.row(this.attachment, this.pickAttachment), this.keepTransform,
            this.inherit.labelRow(UIKeys.INHERIT_TITLE), this.transform);
    }

    private void pickTarget(UIIcon owner, boolean bone)
    {
        UIFilmPanel panel = this.getPanel();
        Replay replay = panel == null ? null : panel.replayEditor.getReplay();

        if (replay == null)
        {
            return;
        }

        panel.getController().picker.toggleTargetPick(owner, replay.getId(), (actor, pair) ->
        {
            String attachment = bone ? StringUtils.combinePaths(FormUtils.getPath(pair.a), pair.b) : Anchor.NO_ATTACHMENT;

            this.retarget((anchor) ->
            {
                anchor.replay = actor;
                anchor.attachment = attachment;
            });
        });
    }

    private void cancelTargetPick()
    {
        UIFilmPanel panel = this.getPanel();

        if (panel != null && (panel.getController().picker.isPickingTarget(this.pickActor)
            || panel.getController().picker.isPickingTarget(this.pickAttachment)))
        {
            panel.getController().picker.cancelTargetPick();
        }
    }

    @Override
    protected void onRemove(UIElement parent)
    {
        this.cancelTargetPick();
        super.onRemove(parent);
    }

    @Override
    public void setVisible(boolean visible)
    {
        if (!visible) this.cancelTargetPick();
        super.setVisible(visible);
    }

    private void displayActors()
    {
        UIFilmPanel panel = this.getPanel();

        panel.getController().picker.cancelTargetPick();
        displayActors(this.getContext(), panel.getController().getEntities(), this.getDisplayValue().replay, this::setActor);
    }

    private void setActor(String actor)
    {
        this.retarget((anchor) -> anchor.replay = actor);
    }

    private void setAttachment(String attachment)
    {
        this.retarget((anchor) -> anchor.attachment = attachment);
    }

    /** Change the target as one undoable edit while preserving the current world transform. */
    private void retarget(Consumer<Anchor> change)
    {
        Anchor from = this.getDisplayValue().copy();
        Anchor to = from.copy();

        change.accept(to);

        UIFilmPanel panel = this.getPanel();
        Replay replay = panel == null ? null : panel.replayEditor.getReplay();
        Map<String, IEntity> entities = panel == null ? null : panel.getController().getEntities();
        IEntity entity = entities == null || replay == null ? null : entities.get(replay.getId());
        boolean rebased = replay != null && entity != null && BBSSettings.anchorKeepTransform.get()
            && AnchorRebase.keepWorldTransform(entities, entity, replay, 0F, from, to);

        BaseValue.edit(this.getEditTarget(), (value) ->
        {
            Anchor anchor = value.getValue();

            change.accept(anchor);

            if (rebased)
            {
                anchor.transform.copy(to.transform);
            }
        });

        if (rebased)
        {
            this.transform.setTransform(this.getDisplayValue().transform);
        }
    }

    private UIFilmPanel getPanel()
    {
        return this.getParent(UIFilmPanel.class);
    }

    public static class UIAnchorTransforms extends UIKeyframePropTransform
    {
        private final UIAnchorKeyframeFactory editor;

        public UIAnchorTransforms(UIAnchorKeyframeFactory editor)
        {
            this.editor = editor;
        }

        @Override
        protected void applyToSelection(Consumer<Transform> consumer)
        {
            apply(this.editor.editor, this.editor.keyframe, consumer);
        }

        @Override
        protected void applyDuringRecording(int tick, Consumer<Transform> consumer)
        {
            applyRecording(this.editor.editor, this.editor.keyframe, tick, consumer);
        }

        @Override
        protected Transform getRecordedTransform(int tick)
        {
            UIKeyframeSheet sheet = this.editor.editor.getGraph().getSheet(this.editor.keyframe);
            Keyframe<?> recorded = UIReplaysEditorUtils.ensureKeyframe(sheet, tick);

            return recorded == null ? null : ((Anchor) recorded.getValue()).transform;
        }

        @Override
        protected Transform getAutoKeyTransform(float tick)
        {
            UIKeyframeSheet sheet = this.editor.editor.getGraph().getSheet(this.editor.keyframe);
            Anchor sampled = (Anchor) UIReplaysEditorUtils.sampleKeyframeValue(sheet, tick);
            return sampled == null ? null : sampled.transform;
        }

        public static void applyRecording(UIKeyframes editor, Keyframe<?> keyframe, int tick, Consumer<Transform> consumer)
        {
            UIReplaysEditorUtils.forEachRecordedKeyframe(editor, keyframe, tick, (recorded) ->
            {
                Anchor anchor = (Anchor) recorded.getValue();

                recorded.preNotify();
                consumer.accept(anchor.transform);
                recorded.postNotify();
            });
        }

        public static void apply(UIKeyframes editor, Keyframe<?> keyframe, Consumer<Transform> consumer)
        {
            UIReplaysEditorUtils.forEachSelectedKeyframe(editor, keyframe, (selected) ->
            {
                Anchor anchor = (Anchor) selected.getValue();

                selected.preNotify();
                consumer.accept(anchor.transform);
                selected.postNotify();
            });
        }
    }
}
