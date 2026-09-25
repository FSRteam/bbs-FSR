package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIModelPicker;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

public class UIModelKeyframeFactory extends UIKeyframeFactory<String>
{
    private final UIButton pick;

    public UIModelKeyframeFactory(Keyframe<String> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);
        this.pick = new UIButton(UIKeys.FORMS_EDITOR_MODEL_PICK_MODEL, (b) -> UIModelPicker.open(
            this.getContext(), this.keyframe.getValue(), (model) ->
            {
                this.setValue(model);
                this.updateModel();
            }
        ));
        this.scroll.add(UI.column(UI.label(UIKeys.FORMS_EDITOR_MODEL_MODELS), this.pick).marginTop(UIConstants.SECTION_GAP));
        this.updateModel();
    }

    private void updateModel()
    {
        String model = this.keyframe.getValue();
        boolean empty = model == null || model.isEmpty();
        this.pick.label = empty ? UIKeys.FORMS_EDITOR_MODEL_PICK_MODEL : IKey.constant(model);
        this.pick.tooltip(empty ? UIKeys.FORMS_EDITOR_MODEL_PICK_MODEL : IKey.constant(model));
    }

    @Override
    public void update()
    {
        super.update();
        this.updateModel();
    }
}
