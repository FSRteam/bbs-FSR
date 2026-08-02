package mchorse.bbs_mod.ui.forms.editors;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.bones.UIBonePicker;
import mchorse.bbs_mod.utils.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class UIBodyPartEditor extends UIScrollView
{
    public UIButton pick;
    public UIToggle useTarget;
    public UIBonePicker bone;
    public UIPropTransform transform;

    private final UIFormEditor editor;

    private BodyPart part;
    private Form owner;

    public UIBodyPartEditor(UIFormEditor editor)
    {
        this.editor = editor;

        this.pick = new UIButton(UIKeys.FORMS_EDITOR_PICK_FORM, (b) ->
        {
            UIForms.FormEntry current = this.editor.formsList.getCurrentFirst();

            this.editor.openFormList(current.part.getForm(), (f) ->
            {
                current.part.setForm(FormUtils.copy(f));

                Form partForm = current.part.getForm();

                if (partForm instanceof ModelForm modelForm)
                {
                    modelForm.boneTracks.set(false);
                }

                if (partForm != null && partForm.getFormId().contains("particle"))
                {
                    current.part.useTarget.set(true);

                    this.useTarget.setValue(true);
                }

                this.editor.refreshFormList();
                this.editor.switchEditor(partForm);
            });
        });

        this.useTarget = new UIToggle(UIKeys.FORMS_EDITOR_USE_TARGET, (b) ->
        {
            this.part.useTarget.set(b.getValue());
        });

        this.bone = new UIBonePicker((b) ->
        {
            if (this.part == null)
            {
                return;
            }

            this.part.bone.set(b);
            this.bone.setLabel(this.boneLabel(b));
        });
        this.bone.menu((picker) ->
        {
            if (this.part == null || this.owner == null)
            {
                return;
            }

            ModelInstance model = this.owner instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;

            if (model != null && model.model != null)
            {
                picker.bones(model.model, BBSSettings.poseShowDisabledBones.get() ? null : model.getDisabledBones());
            }
            else
            {
                List<String> bones = new ArrayList<>(FormUtilsClient.getBones(this.owner));

                bones.sort(String::compareToIgnoreCase);
                picker.list(bones);
            }

            picker.none().set(this.part.bone.get());
        });
        this.bone.viewport(new UIBonePicker.Viewport()
        {
            @Override
            public void startPicking(Consumer<String> callback)
            {
                UIBodyPartEditor.this.editor.startBonePicking((pair) ->
                {
                    BodyPart part = UIBodyPartEditor.this.part;

                    callback.accept(pair != null && part != null && part.getManager().getOwner() == pair.a ? pair.b : null);
                });
            }

            @Override
            public void stopPicking()
            {
                UIBodyPartEditor.this.editor.stopBonePicking();
            }
        });

        this.transform = new UIPropTransform().callbacks(() -> this.part.transform).barBackground();
        this.transform.enableHotkeys(this.editor::isBodyPartGizmoMode);
        this.transform.hotkeyDrag(() -> this.editor.buildHotkeyDrag(this.transform));

        this.pick.keys().register(Keys.FORMS_EDIT, this.pick::clickItself);

        this.column(UIConstants.MARGIN).vertical().stretch().scroll().padding(UIConstants.SCROLL_PADDING);
        this.scroll.cancelScrolling();
    }

    public void setPart(BodyPart part, Form form)
    {
        this.part = part;
        this.owner = form;

        this.removeAll();

        this.useTarget.setValue(part.useTarget.get());
        this.bone.setLabel(this.boneLabel(part.bone.get()));

        if (!FormUtilsClient.getBones(form).isEmpty())
        {
            this.add(this.pick, this.bone, this.useTarget, this.transform);
        }
        else
        {
            this.add(this.pick, this.useTarget, this.transform);
        }

        this.transform.setTransform(part.transform.get());

        this.scroll.setScroll(0);
        this.resize();
    }

    private IKey boneLabel(String bone)
    {
        return bone == null || bone.isEmpty() ? UIKeys.MODEL_EDITOR_PICK_BONE : IKey.constant(bone);
    }

    /** Attach the active body part to the clicked parent bone; returns whether it did. */
    public boolean pickBone(Pair<Form, String> pair)
    {
        /* Ctrl + clicking to pick the parent bone to attach to */
        if (this.part != null && !pair.b.isEmpty() && this.part.getManager().getOwner() == pair.a)
        {
            this.part.bone.set(pair.b);
            this.bone.setLabel(this.boneLabel(pair.b));

            return true;
        }

        return false;
    }
}
