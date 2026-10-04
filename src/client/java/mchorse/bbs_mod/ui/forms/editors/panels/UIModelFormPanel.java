package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.BoneHierarchy;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIModelPicker;
<<<<<<< HEAD
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
=======
>>>>>>> origin/master
import mchorse.bbs_mod.ui.utils.shapes.UIShapeKeys;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class UIModelFormPanel extends UIPoseFormPanel<ModelForm>
{
    public UIColor color;
    public UIShapeKeys shapeKeys;
    public UISection shapeKeysSection;

    /** Only for a model that carries a CEM program — see {@link mchorse.bbs_mod.cubic.jem.CemStatus}. */
    public UISection cemSection;

    /* The CEM state widgets, built once and refreshed per form in startEdit — see buildCemSection(). */
    private UIToggle cemSitting;
    private UIToggle cemTamed;
    private UIToggle cemAggressive;
    private UIToggle cemOnShoulder;
    private UIToggle cemBurning;
    private UIToggle cemInLava;
    private UIToggle cemClimbing;
    private UIToggle cemCrawling;
    private UITrackpad cemHealth;

    public UIButton pickModel;
    public UIButton pick;

    public UIModelFormPanel(UIForm editor)
    {
        super(editor);

        this.pickModel = new UIButton(UIKeys.FORMS_EDITOR_MODEL_PICK_MODEL, (b) ->
        {
            UIModelPicker.open(this.getContext(), this.form.model.get(), (l) ->
            {
                this.form.model.set(l);

                if (Window.isCtrlPressed())
                {
                    ModelInstance model = ModelFormRenderer.getModel(this.form);

                    if (model != null)
                    {
                        this.form.texture.set(model.getTexture());
                    }
                }

                this.editor.startEdit(this.form);
            });
        });
        this.color = new UIColor((c) -> this.form.color.set(new Color().set(c))).withAlpha();
        this.color.direction(Direction.LEFT);
        this.shapeKeys = new UIShapeKeys();
        this.shapeKeys.title.removeFromParent();
        this.shapeKeysSection = this.section(UIKeys.SHAPE_KEYS_TITLE, "model.shape_keys", false);
        this.shapeKeysSection.fields.add(this.shapeKeys);
        this.pick = new UIButton(UIKeys.FORMS_EDITOR_MODEL_PICK_TEXTURE, (b) ->
        {
            ModelInstance model = ModelFormRenderer.getModel(this.form);
            List<String> materials = model == null ? Collections.emptyList() : model.materials;

            /* At most one material (a single global texture, e.g. cubic, or one unambiguous material):
             * pick the form's default texture. Multiple: choose which material to pick - the form's
             * "Default" texture is ambiguous then, so it isn't offered. */
            if (materials.size() <= 1)
            {
                this.openTexturePicker(null);
            }
            else
            {
                this.getContext().replaceContextMenu((menu) ->
                {
                    for (String material : materials)
                    {
                        menu.action(Icons.MATERIAL, IKey.constant(material), () -> this.openTexturePicker(material));
                    }
                });
            }
        });

        this.options.add(this.pickModel, this.pick, this.color, this.poseEditor);

        this.buildCemSection();
    }

    /**
     * The states a CEM pack asks about that a form cannot know — whether the creature sits, is tamed,
     * is angry. In the world a morph reads them off the entity it rides; a film's actor is a stand-in
     * with no owner and no target, so here they are set by hand, and each is a track of its own.
     */
    private void buildCemSection()
    {
        this.cemSitting = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_SITTING, false, (b) -> this.form.cemSitting.set(b.getValue()));
        this.cemTamed = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_TAMED, false, (b) -> this.form.cemTamed.set(b.getValue()));
        this.cemAggressive = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_AGGRESSIVE, false, (b) -> this.form.cemAggressive.set(b.getValue()));
        this.cemOnShoulder = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_ON_SHOULDER, false, (b) -> this.form.cemOnShoulder.set(b.getValue()));
        this.cemBurning = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_BURNING, false, (b) -> this.form.cemBurning.set(b.getValue()));
        this.cemInLava = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_IN_LAVA, false, (b) -> this.form.cemInLava.set(b.getValue()));
        this.cemClimbing = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_CLIMBING, false, (b) -> this.form.cemClimbing.set(b.getValue()));
        this.cemCrawling = new UIToggle(UIKeys.FORMS_EDITOR_MODEL_CEM_CRAWLING, false, (b) -> this.form.cemCrawling.set(b.getValue()));
        this.cemHealth = new UITrackpad((v) -> this.form.cemHealth.set(v.floatValue()));

        this.cemHealth.limit(0D, 1D).tooltip(UIKeys.FORMS_EDITOR_MODEL_CEM_HEALTH_TOOLTIP);

        this.cemSection = this.section(UIKeys.FORMS_EDITOR_MODEL_CEM, "model.cem", false);
        this.cemSection.fields.add(
            this.cemSitting,
            this.cemTamed,
            this.cemAggressive,
            this.cemOnShoulder,
            this.cemBurning,
            this.cemInLava,
            this.cemClimbing,
            this.cemCrawling,
            UI.labelRow(UIKeys.FORMS_EDITOR_MODEL_CEM_HEALTH, this.cemHealth)
        );
        this.cemSection.title.tooltip(UIKeys.FORMS_EDITOR_MODEL_CEM_TOOLTIP);
    }

    /**
     * Open the texture picker for either the form's default texture ({@code material == null}) or a
     * specific material's static texture. The picker starts at the texture currently in effect, so it
     * opens beside it rather than at the root.
     */
    private void openTexturePicker(String material)
    {
        ModelInstance model = ModelFormRenderer.getModel(this.form);
        Link link;
        Consumer<Link> callback;

        if (material == null)
        {
            link = this.form.texture.get();

            if (model != null && link == null)
            {
                link = model.getTexture();
            }

            callback = (l) -> this.form.texture.set(l);
        }
        else
        {
            link = this.form.materialTextures.getLink(material);

            if (link == null && model != null)
            {
                Link fallback = this.form.texture.get() != null ? this.form.texture.get() : model.getTexture();

                link = model.getMaterialTexture(material, fallback);
            }

            callback = (l) -> this.form.materialTextures.setLink(material, l);
        }

        UITexturePicker picker = UITexturePicker.open(this.getContext(), link, callback);

        if (picker != null && this.form.model.get() != null && !this.form.model.get().isEmpty())
        {
            picker.withModelPreview(this.form.model.get());
        }
    }

    @Override
    public void startEdit(ModelForm form)
    {
        super.startEdit(form);

        ModelInstance model = ModelFormRenderer.getModel(this.form);
        BoneHierarchy hierarchy = FormUtilsClient.getBoneHierarchy(form);

        this.bindPose(form, model == null ? this.form.model.get() : model.getPoseGroup());
        this.poseEditor.migratePose(hierarchy);
        this.poseEditor.fillGroups(
            model == null ? null : model.model,
            model == null ? null : model.getFlippedParts(),
            true,
            model == null ? null : model.getDisabledBones(),
            hierarchy
        );
        this.color.setColor(form.color.get().getARGBColor());

        Set<String> modelShapeKeys = model == null ? Collections.emptySet() : model.model.getShapeKeys();

        this.shapeKeysSection.removeFromParent();
        this.options.add(this.shapeKeysSection);
        this.shapeKeys.setShapeKeys(model == null ? "" : model.getPoseGroup(), modelShapeKeys, this.form.shapeKeys.get());

        /* Nothing reads these unless a CEM program is what animates the model, so they only show there. */
        this.cemSection.removeFromParent();

        if (model != null && model.cemAnimation != null && model.config.cemAnimation.get())
        {
            this.cemSitting.setValue(this.form.cemSitting.get());
            this.cemTamed.setValue(this.form.cemTamed.get());
            this.cemAggressive.setValue(this.form.cemAggressive.get());
            this.cemOnShoulder.setValue(this.form.cemOnShoulder.get());
            this.cemBurning.setValue(this.form.cemBurning.get());
            this.cemInLava.setValue(this.form.cemInLava.get());
            this.cemClimbing.setValue(this.form.cemClimbing.get());
            this.cemCrawling.setValue(this.form.cemCrawling.get());
            this.cemHealth.setValue(this.form.cemHealth.get());

            this.options.add(this.cemSection);
        }

        this.options.resize();
    }
}
