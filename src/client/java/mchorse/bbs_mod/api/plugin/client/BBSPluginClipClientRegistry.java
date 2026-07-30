package mchorse.bbs_mod.api.plugin.client;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.utils.clips.Clip;

public interface BBSPluginClipClientRegistry
{
    <T extends Clip> BBSRegistrationResult registerEditor(Class<T> type, UIClip.IUIClipFactory<T> factory);
}
