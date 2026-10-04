package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

public class UIBooleanKeyframeFactory extends UIKeyframeFactory<Boolean>
{
    private UIToggle toggle;

    public UIBooleanKeyframeFactory(Keyframe<Boolean> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.toggle = new UIToggle(UIKeys.GENERIC_KEYFRAMES_BOOLEAN_TRUE, (b) -> this.setValue(b.getValue()));
        this.toggle.setValue(keyframe.getValue());

        /* The transform key the numeric editors use for their drag also toggles the value here: a
         * boolean key has no drag gesture, so without this the key did nothing in this editor. */
        this.keys().register(Keys.TRANSFORMATIONS_TRANSLATE, () ->
        {
            this.setValue(!this.keyframe.getValue());
            this.update();
        }).category(UIKeys.TRANSFORMS_KEYS_CATEGORY);

        this.scroll.add(this.toggle);
    }

    @Override
    public void update()
    {
        super.update();

        this.toggle.setValue(this.keyframe.getValue());
    }
}