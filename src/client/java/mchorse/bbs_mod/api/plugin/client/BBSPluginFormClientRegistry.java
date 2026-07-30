package mchorse.bbs_mod.api.plugin.client;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.FormUtilsClient.IFormRendererFactory;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;

import java.util.function.Supplier;

public interface BBSPluginFormClientRegistry
{
    <T extends Form> BBSRegistrationResult registerRenderer(Class<T> type, IFormRendererFactory<T> factory);

    BBSRegistrationResult registerEditor(Class<? extends Form> type, Supplier<UIForm> factory);

    BBSRegistrationResult registerExtra(Form form);
}
