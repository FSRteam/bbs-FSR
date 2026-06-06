package mchorse.bbs_mod.api.settings;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.io.File;
import java.util.function.Consumer;

public interface BBSSettingsRegistry
{
    BBSRegistrationResult register(Icon icon, String id, Consumer<SettingsBuilder> registerer);

    BBSRegistrationResult register(Icon icon, String id, File destination, Consumer<SettingsBuilder> registerer);
}
