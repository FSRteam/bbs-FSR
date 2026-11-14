package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.utils.values.UIValues;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.film.replays.overlays.UIKeyframeSheetFilterOverlayPanel;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIKeybind;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class UIGeneralFormPanel extends UIFormPanel
{
    public UIKeybind hotkey;

    public UIToggle visible;
    public UIToggle pickable;
    public UIButton filterTracks;
    public UIToggle boneTracks;

    public UITextbox trackName;
    public UIToggle lighting;
    public UIToggle shaderShadow;
    public UIToggle additiveColor;
    public UIToggle renderLast;
    public UICirculate glintMode;
    public UIColor glintColor;
    public UITrackpad glintSpeed;
    public UIPropTransform glintTransform;
    public UISection glintSection;
    private UISection tracksSection;
    public UITrackpad uiScale;
    public UITextbox name;
    public UIPropTransform transform;

    public UIToggle hitbox;
    public UITrackpad hitboxWidth;
    public UITrackpad hitboxHeight;
    public UISliderTrackpad hitboxSneakMultiplier;
    public UISliderTrackpad hitboxEyeHeight;

    public UITrackpad hp;
    public UITrackpad speed;
    public UITrackpad stepHeight;

    public UIGeneralFormPanel(UIForm editor)
    {
        super(editor);

        this.hotkey = new UIKeybind((combo) ->
        {
            this.form.hotkey.set(combo.keys.isEmpty() ? 0 : combo.keys.get(0));
        });
        this.hotkey.single().tooltip(UIKeys.FORMS_EDITORS_GENERAL_HOTKEY);

        this.pickable = UIValues.toggle(UIKeys.FORMS_EDITORS_GENERAL_PICKABLE, () -> this.form.pickable);
        this.visible = UIValues.toggle(UIKeys.FORMS_EDITORS_GENERAL_VISIBLE, () -> this.form.visible);
        this.filterTracks = new UIButton(UIKeys.FORMS_EDITORS_GENERAL_FILTER_TRACKS, (b) -> this.openTrackFilter());
        this.filterTracks.tooltip(UIKeys.FORMS_EDITORS_GENERAL_FILTER_TRACKS_TOOLTIP);
        this.boneTracks = new UIToggle(UIKeys.FORMS_EDITORS_GENERAL_BONE_TRACKS, (b) ->
        {
            if (this.form instanceof PoseForm poseForm) poseForm.getBoneTracks().set(b.getValue());
        });
        this.boneTracks.tooltip(UIKeys.FORMS_EDITORS_GENERAL_BONE_TRACKS_TOOLTIP);
        this.trackName = UIValues.textbox(120, () -> this.form.trackName);
        this.trackName.tooltip(UIKeys.FORMS_EDITORS_GENERAL_TRACK_NAME_TOOLTIP);
        this.lighting = new UIToggle(UIKeys.FORMS_EDITORS_GENERAL_LIGHTING, (b) -> this.form.lighting.set(b.getValue() ? 1F : 0F));
        this.lighting.tooltip(UIKeys.FORMS_EDITORS_GENERAL_LIGHTING_TOOLTIP);
        this.shaderShadow = UIValues.toggle(UIKeys.FORMS_EDITORS_GENERAL_SHADER_SHADOW, () -> this.form.shaderShadow);
        this.additiveColor = UIValues.toggle(UIKeys.FORMS_EDITORS_ADDITIVE_COLOR, () -> this.form.additiveColor);
        this.renderLast = UIValues.toggle(UIKeys.FORMS_EDITORS_GENERAL_RENDER_LAST, () -> this.form.renderLast);
        this.renderLast.tooltip(UIKeys.FORMS_EDITORS_GENERAL_RENDER_LAST_TOOLTIP);
        this.glintMode = new UICirculate((c) -> this.form.glintMode.set(c.getValue()));
        this.glintMode.addLabel(UIKeys.POSE_CONTEXT_GLINT_OFF);
        this.glintMode.addLabel(UIKeys.POSE_CONTEXT_GLINT_FULL);
        this.glintMode.addLabel(UIKeys.POSE_CONTEXT_GLINT_EDGE);
        this.glintMode.addLabel(UIKeys.POSE_CONTEXT_GLINT_VANILLA);
        this.glintMode.tooltip(UIKeys.FORMS_EDITORS_GENERAL_GLINT_TOOLTIP);
        this.glintMode.h(UIConstants.CONTROL_HEIGHT);
        this.glintColor = UIValues.color(() -> this.form.glintColor);
        this.glintColor.withAlpha();
        this.glintColor.tooltip(UIKeys.POSE_CONTEXT_GLINT_COLOR_TOOLTIP);
        this.glintSpeed = UIValues.trackpad(() -> this.form.glintSpeed);
        this.glintSpeed.limit(-4D, 4D).increment(0.1D).values(0.1D, 0.05D, 0.5D);
        this.glintSpeed.tooltip(UIKeys.POSE_CONTEXT_GLINT_SPEED_TOOLTIP);
        this.glintTransform = new UIPropTransform().callbacks(() -> this.form.glintTransform).barBackground();
        this.uiScale = UIValues.trackpad(() -> this.form.uiScale);
        this.uiScale.limit(0.01D, 100D);
        this.name = UIValues.textbox(120, () -> this.form.name);

        this.transform = new UIPropTransform().callbacks(() -> this.form.transform).barBackground();
        this.transform.enableHotkeys();

        this.hitbox = UIValues.toggle(UIKeys.CAMERA_PANELS_ENABLED, () -> this.form.hitbox);
        this.hitboxWidth = UIValues.trackpad(() -> this.form.hitboxWidth);
        this.hitboxWidth.limit(0).tooltip(UIKeys.FORMS_EDITORS_GENERAL_HITBOX_WIDTH);
        this.hitboxHeight = UIValues.trackpad(() -> this.form.hitboxHeight);
        this.hitboxHeight.limit(0).tooltip(UIKeys.FORMS_EDITORS_GENERAL_HITBOX_HEIGHT);
        this.hitboxSneakMultiplier = new UISliderTrackpad((v) -> this.form.hitboxSneakMultiplier.set(v.floatValue()));
        this.hitboxSneakMultiplier.limit(0, 1);
        this.hitboxEyeHeight = new UISliderTrackpad((v) -> this.form.hitboxEyeHeight.set(v.floatValue()));
        this.hitboxEyeHeight.limit(0, 1);

        this.hp = UIValues.trackpad(() -> this.form.hp);
        this.hp.limit(1F);
        this.speed = UIValues.trackpad(() -> this.form.speed);
        this.speed.limit(0F);
        this.stepHeight = UIValues.trackpad(() -> this.form.stepHeight);
        this.stepHeight.limit(0F);

        UISection display = new UISection(UIKeys.FORMS_EDITORS_GENERAL_SECTION_DISPLAY);

        display.fields.add(
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_DISPLAY, this.name),
            this.hotkey, this.visible, this.pickable,
            this.lighting, this.shaderShadow, this.additiveColor,
            this.renderLast,
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_UI_SCALE, this.uiScale)
        );

        this.glintSection = new UISection(UIKeys.POSE_CONTEXT_GLINT_TRANSFORM);
        this.glintSection.fields.add(
            UI.row(this.glintMode, this.glintColor),
            UI.labelRow(UIKeys.POSE_CONTEXT_GLINT_SPEED, this.glintSpeed),
            this.glintTransform
        );
        this.glintSection.setExpanded(false);

        this.tracksSection = new UISection(UIKeys.FORMS_EDITORS_GENERAL_SECTION_TRACKS);

        this.tracksSection.fields.add(this.filterTracks, this.boneTracks, this.trackName);

        UISection transform = new UISection(UIKeys.FORMS_EDITORS_GENERAL_SECTION_TRANSFORM);

        transform.fields.add(this.transform);

        UISection hitbox = new UISection(UIKeys.FORMS_EDITORS_GENERAL_HITBOX);

        hitbox.fields.add(
            this.hitbox,
            UI.row(this.hitboxWidth, this.hitboxHeight),
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_HITBOX_SNEAK_MULTIPLIER, this.hitboxSneakMultiplier),
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_HITBOX_EYE_HEIGHT, this.hitboxEyeHeight)
        );

        UISection movement = new UISection(UIKeys.FORMS_EDITORS_GENERAL_SECTION_MOVEMENT);

        movement.fields.add(
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_HP, this.hp),
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_MOVEMENT_SPEED, this.speed.tooltip(UIKeys.FORMS_EDITORS_GENERAL_MOVEMENT_SPEED_TOOLTIP)),
            UI.labelRow(UIKeys.FORMS_EDITORS_GENERAL_STEP_HEIGHT, this.stepHeight)
        );

        this.options.add(
            display,
            this.glintSection,
            this.tracksSection,
            transform,
            hitbox,
            movement
        );
    }

    @Override
    public void startEdit(Form form)
    {
        super.startEdit(form);

        this.hotkey.setKeyCombo(new KeyCombo(IKey.EMPTY, form.hotkey.get()));

        this.visible.setValue(form.visible.get());
        this.pickable.setValue(form.pickable.get());
        if (form instanceof PoseForm poseForm)
        {
            this.boneTracks.setValue(poseForm.getBoneTracks().get());
            this.boneTracks.setVisible(true);
        }
        else
        {
            this.boneTracks.setVisible(false);
        }
        this.trackName.setText(form.trackName.get());
        this.lighting.setValue(form.lighting.get() > 0F);
        this.shaderShadow.setValue(form.shaderShadow.get());
        this.additiveColor.setValue(form.additiveColor.get());
        this.renderLast.setValue(form.renderLast.get());

        /* Only offered where the renderer actually draws it. Model forms have their own
         * per-bone glint in the pose editor, so they leave these hidden as well. */
        boolean glint = form.supportsGlint();

        this.glintSection.removeFromParent();
        this.glintMode.setVisible(glint);
        this.glintColor.setVisible(glint);
        this.glintSpeed.setVisible(glint);
        this.glintSection.setVisible(glint);

        if (glint)
        {
            this.options.addBefore(this.tracksSection, this.glintSection);
            this.glintMode.setValue(form.glintMode.get());
            this.glintColor.setColor(form.glintColor.get().getARGBColor());
            this.glintSpeed.setValue(form.glintSpeed.get());
            this.glintTransform.setTransform(form.glintTransform.get());
        }

        this.uiScale.setValue(form.uiScale.get());
        this.name.setText(form.name.get());
        this.transform.setTransform(form.transform.get());

        this.hitbox.setValue(form.hitbox.get());
        this.hitboxWidth.setValue(form.hitboxWidth.get());
        this.hitboxHeight.setValue(form.hitboxHeight.get());
        this.hitboxSneakMultiplier.setValue(form.hitboxSneakMultiplier.get());
        this.hitboxEyeHeight.setValue(form.hitboxEyeHeight.get());

        this.hp.setValue(form.hp.get());
        this.speed.setValue(form.speed.get());
        this.stepHeight.setValue(form.stepHeight.get());

        this.options.resize();
    }

    private void openTrackFilter()
    {
        if (this.form == null)
        {
            return;
        }

        Set<String> disabled = this.form.disabledTracks.get();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Integer> keyToColor = new HashMap<>();
        Map<String, String> keyToLabel = new HashMap<>();

        for (TrackDescriptor track : TrackCatalog.of(this.form))
        {
            String key = track.filterKey();
            keys.add(key);
            keyToColor.put(key, track.color());
            keyToLabel.put(key, track.title().get());
        }

        UIKeyframeSheetFilterOverlayPanel panel = new UIKeyframeSheetFilterOverlayPanel(disabled, keys, keyToColor, keyToLabel);

        UIOverlay.addOverlay(this.getContext(), panel, 240, 0.9F);

        panel.onClose(e -> this.form.disabledTracks.set(disabled));
    }
}
