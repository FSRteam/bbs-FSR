package mchorse.bbs_mod.ui.framework.elements.events;

import mchorse.bbs_mod.ui.framework.elements.input.UINumericInput;

public class UITrackpadDragEndEvent extends UIEvent<UINumericInput<?>>
{
    public UITrackpadDragEndEvent(UINumericInput<?> element)
    {
        super(element);
    }
}
