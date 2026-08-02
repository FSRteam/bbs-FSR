package mchorse.bbs_mod.ui.framework.elements.events;

import mchorse.bbs_mod.ui.framework.elements.input.UINumericInput;

public class UITrackpadDragStartEvent extends UIEvent<UINumericInput<?>>
{
    public UITrackpadDragStartEvent(UINumericInput<?> element)
    {
        super(element);
    }
}
