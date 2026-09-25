package mchorse.bbs_mod.ui.forms.editors.panels.widgets;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;

import java.util.function.Consumer;

public class UIModelPicker
{
    public static void open(UIContext context, String current, Consumer<String> callback)
    {
        UIListOverlayPanel list = new UIListOverlayPanel(UIKeys.FORMS_EDITOR_MODEL_MODELS, callback);
        list.addValues(BBSModClient.getModels().getAvailableKeys());
        list.list.list.sort();
        list.setValue(current == null ? "" : current);
        UIOverlay.addOverlay(context, list);
    }
}
