package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.ArrayList;
import java.util.List;

public class UIStringKeyframeFactory extends UIKeyframeFactory<String>
{
    private static final List<IStringKeyframeDecorator> DECORATORS = new ArrayList<>();

    private UITextbox string;

    public static void registerDecorator(IStringKeyframeDecorator decorator)
    {
        if (decorator != null)
        {
            DECORATORS.add(decorator);
        }
    }

    public UIStringKeyframeFactory(Keyframe<String> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.string = new UITextbox(1000, this::setValue);
        this.string.setText(keyframe.getValue());

        this.scroll.add(this.string);

        UIKeyframeSheet sheet = editor.getGraph().getSheet(keyframe);
        String track = sheet == null ? "" : sheet.id;

        for (IStringKeyframeDecorator decorator : DECORATORS)
        {
            decorator.decorate(this, track);
        }
    }

    public void addControl(UIElement element)
    {
        if (element != null)
        {
            this.scroll.add(element);
        }
    }

    public void setStringValue(String value)
    {
        if (value == null)
        {
            value = "";
        }

        this.editor.getGraph().setValue(value, true);
        this.string.setText(value);
    }

    @Override
    public void update()
    {
        super.update();

        this.string.setText(this.keyframe.getValue());
    }

    public static interface IStringKeyframeDecorator
    {
        public void decorate(UIStringKeyframeFactory factory, String track);
    }
}
