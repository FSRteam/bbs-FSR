package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.api.client.events.RegisterReplayActionsEvent;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.UINestedEdit;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.CollectionUtils;

import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class UIReplayPropertiesPanel extends UIElement
{
    private final UIFilmPanel filmPanel;

    public UIElement properties;
    public UINestedEdit pickEdit;
    public UIToggle enabled;
    public UITextbox label;
    public UITextbox nameTag;
    public UIToggle shadow;
    public UITrackpad shadowSize;
    public UIToggle shadowFollow;
    public UITrackpad shadowOffsetX;
    public UITrackpad shadowOffsetY;
    public UITrackpad shadowOffsetZ;
    public UITrackpad looping;
    public UIToggle actor;
    public UIToggle actorPickup;
    public UIToggle fp;
    public UIToggle relative;
    public UITrackpad relativeOffsetX;
    public UITrackpad relativeOffsetY;
    public UITrackpad relativeOffsetZ;
    public UIToggle axesPreview;
    public UIButton pickAxesPreviewBone;

    private UIReplayList list;
    private Replay replay;

    /** Read the displayed replay while writes keep using the selected replays. */
    private <E extends UIElement> E bound(E element, Consumer<Replay> read)
    {
        element.valueBinding(() ->
        {
            if (this.replay != null)
            {
                read.accept(this.replay);
            }
        });

        return element;
    }

    public UIReplayPropertiesPanel(UIFilmPanel filmPanel)
    {
        this.filmPanel = filmPanel;

        this.pickEdit = new UINestedEdit((editing) ->
        {
            if (this.list == null)
            {
                return;
            }

            Replay r = this.list.getSelectedReplayFirst();

            if (r != null)
            {
                this.list.openFormEditor(r.form, editing, this.pickEdit::setForm);
            }
        });
        this.pickEdit.pick.tooltip(UIKeys.SCENE_REPLAYS_CONTEXT_PICK_FORM);
        this.pickEdit.edit.tooltip(UIKeys.SCENE_REPLAYS_CONTEXT_EDIT_FORM);
        this.enabled = this.bound(new UIToggle(UIKeys.CAMERA_PANELS_ENABLED, (b) ->
        {
            this.edit((replay) -> replay.enabled.set(b.getValue()));
            filmPanel.getController().createEntities();
        }), (shown) -> this.enabled.setValue(shown.enabled.get()));
        this.label = this.bound(new UITextbox(1000, (s) -> this.edit((replay) -> replay.label.set(s))), (shown) -> this.label.setText(shown.label.get()));
        this.label.textbox.setPlaceholder(UIKeys.FILM_REPLAY_LABEL);
        this.nameTag = this.bound(new UITextbox(1000, (s) -> this.edit((replay) -> replay.nameTag.set(s))), (shown) -> this.nameTag.setText(shown.nameTag.get()));
        this.nameTag.textbox.setPlaceholder(UIKeys.FILM_REPLAY_NAME_TAG);
        this.shadow = this.bound(new UIToggle(UIKeys.CAMERA_PANELS_ENABLED, (b) -> this.edit((replay) -> replay.shadow.set(b.getValue()))), (shown) -> this.shadow.setValue(shown.shadow.get()));
        this.shadowSize = this.bound(new UITrackpad((v) -> this.edit((replay) -> replay.shadowSize.set(v.floatValue()))), (shown) -> this.shadowSize.setValue(shown.shadowSize.get()));
        this.shadowSize.tooltip(UIKeys.FILM_REPLAY_SHADOW_SIZE);
        this.shadowFollow = this.bound(new UIToggle(UIKeys.FILM_REPLAY_SHADOW_FOLLOW, (b) -> this.edit((replay) -> replay.shadowFollow.set(b.getValue()))), (shown) -> this.shadowFollow.setValue(shown.shadowFollow.get()));
        this.shadowFollow.tooltip(UIKeys.FILM_REPLAY_SHADOW_FOLLOW_TOOLTIP);
        this.shadowOffsetX = this.bound(new UITrackpad((v) -> this.edit((replay) -> BaseValue.edit(replay.shadowOffset, (value) -> value.get().x = v))), (shown) -> this.shadowOffsetX.setValue(shown.shadowOffset.get().x));
        this.shadowOffsetX.tooltip(UIKeys.FILM_REPLAY_SHADOW_OFFSET);
        this.shadowOffsetY = this.bound(new UITrackpad((v) -> this.edit((replay) -> BaseValue.edit(replay.shadowOffset, (value) -> value.get().y = v))), (shown) -> this.shadowOffsetY.setValue(shown.shadowOffset.get().y));
        this.shadowOffsetY.tooltip(UIKeys.FILM_REPLAY_SHADOW_OFFSET);
        this.shadowOffsetZ = this.bound(new UITrackpad((v) -> this.edit((replay) -> BaseValue.edit(replay.shadowOffset, (value) -> value.get().z = v))), (shown) -> this.shadowOffsetZ.setValue(shown.shadowOffset.get().z));
        this.shadowOffsetZ.tooltip(UIKeys.FILM_REPLAY_SHADOW_OFFSET);
        this.looping = this.bound(new UITrackpad((v) -> this.edit((replay) -> replay.looping.set(v.intValue()))), (shown) -> this.looping.setValue(shown.looping.get()));
        this.looping.limit(0).integer().tooltip(UIKeys.FILM_REPLAY_LOOPING_TOOLTIP);
        this.actor = this.bound(new UIToggle(UIKeys.FILM_REPLAY_ACTOR, (b) -> this.edit((replay) -> replay.actor.set(b.getValue()))), (shown) -> this.actor.setValue(shown.actor.get()));
        this.actor.tooltip(UIKeys.FILM_REPLAY_ACTOR_TOOLTIP);
        this.actorPickup = this.bound(new UIToggle(UIKeys.FILM_REPLAY_ACTOR_PICKUP, (b) -> this.edit((replay) -> replay.actorPickup.set(b.getValue()))), (shown) -> this.actorPickup.setValue(shown.actorPickup.get()));
        this.actorPickup.tooltip(UIKeys.FILM_REPLAY_ACTOR_PICKUP_TOOLTIP);
        this.fp = new UIToggle(UIKeys.FILM_REPLAY_FP, (b) ->
        {
            if (filmPanel.getData() != null)
            {
                for (Replay replay : filmPanel.getData().replays.getList())
                {
                    if (replay.fp.get())
                    {
                        replay.fp.set(false);
                    }
                }
            }

            Replay first = this.list == null ? null : this.list.getSelectedReplayFirst();

            if (first != null)
            {
                first.fp.set(b.getValue());
            }
        });
        this.bound(this.fp, (shown) -> this.fp.setValue(shown.fp.get()));
        this.relative = this.bound(new UIToggle(UIKeys.CAMERA_PANELS_RELATIVE, (b) -> this.edit((replay) -> replay.relative.set(b.getValue()))), (shown) -> this.relative.setValue(shown.relative.get()));
        this.relative.tooltip(UIKeys.FILM_REPLAY_RELATIVE_TOOLTIP);
        this.relativeOffsetX = this.bound(new UITrackpad((v) -> this.edit((replay) -> BaseValue.edit(replay.relativeOffset, (value) -> value.get().x = v))), (shown) -> this.relativeOffsetX.setValue(shown.relativeOffset.get().x));
        this.relativeOffsetY = this.bound(new UITrackpad((v) -> this.edit((replay) -> BaseValue.edit(replay.relativeOffset, (value) -> value.get().y = v))), (shown) -> this.relativeOffsetY.setValue(shown.relativeOffset.get().y));
        this.relativeOffsetZ = this.bound(new UITrackpad((v) -> this.edit((replay) -> BaseValue.edit(replay.relativeOffset, (value) -> value.get().z = v))), (shown) -> this.relativeOffsetZ.setValue(shown.relativeOffset.get().z));
        this.axesPreview = this.bound(new UIToggle(UIKeys.FILM_REPLAY_AXES_PREVIEW, (b) -> this.edit((replay) -> replay.axesPreview.set(b.getValue()))), (shown) -> this.axesPreview.setValue(shown.axesPreview.get()));
        this.pickAxesPreviewBone = new UIButton(UIKeys.FILM_REPLAY_PICK_AXES_PREVIEW, (b) ->
        {
            Replay replay = filmPanel.replayEditor.getReplay();

            if (replay != null && filmPanel.getData() != null)
            {
                UIAnchorKeyframeFactory.displayAttachments(filmPanel, replay.getId(), replay.axesPreviewBone.get(), (s) ->
                {
                    this.edit((r) -> r.axesPreviewBone.set(s));
                });
            }
        });

        UISection shadowSection = new UISection(UIKeys.FILM_REPLAY_SHADOW);

        shadowSection.fields.add(this.shadow, this.shadowSize, this.shadowFollow, UI.row(this.shadowOffsetX, this.shadowOffsetY, this.shadowOffsetZ));

        UISection other = new UISection(UIKeys.FILM_REPLAY_SECTION_OTHER);

        other.fields.add(
            this.looping, this.actor, this.actorPickup, this.fp,
            this.relative, UI.row(this.relativeOffsetX, this.relativeOffsetY, this.relativeOffsetZ),
            this.axesPreview, this.pickAxesPreviewBone
        );

        shadowSection.setExpanded(false);
        other.setExpanded(false);

        this.properties = UI.scrollView(UIConstants.MARGIN, UIConstants.SCROLL_PADDING,
            this.pickEdit, this.enabled, this.label, this.nameTag,
            shadowSection,
            other
        );
        this.refreshEditPanelOffset();

        for (BiFunction<UIFilmPanel, Supplier<Replay>, UIElement> factory : RegisterReplayActionsEvent.getActions())
        {
            UIElement action = factory.apply(this.filmPanel, () -> this.replay);

            if (action != null)
            {
                this.properties.add(action);
            }
        }

        this.add(this.properties);
        this.setReplay(null);
    }

    public void refreshEditPanelOffset()
    {
        int top = this.filmPanel.getEditPanelTopOffsetPx();
        this.properties.relative(this).x(0).y(0, top).w(1F).h(1F, -top);
        this.resize();
    }

    public void attachReplayList(UIReplayList list)
    {
        this.list = list;
    }

    public Consumer<Form> getFormConsumer()
    {
        return this.pickEdit::setForm;
    }

    private void edit(Consumer<Replay> consumer)
    {
        if (consumer != null && this.list != null)
        {
            for (Replay replay : this.list.getSelectedReplays())
            {
                consumer.accept(replay);
            }
        }
    }

    public void setReplay(Replay replay)
    {
        this.replay = replay;
        this.properties.setVisible(replay != null);

        if (replay != null)
        {
            this.pickEdit.setForm(replay.form.get());
        }
    }

}
