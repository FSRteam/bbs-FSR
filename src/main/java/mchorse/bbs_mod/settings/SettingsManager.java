package mchorse.bbs_mod.settings;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class SettingsManager
{
    public final Map<String, Settings> modules = new HashMap<>();

    public void reload()
    {
        for (Settings settings : this.modules.values())
        {
            this.load(settings, settings.file);
        }
    }

    public boolean load(Settings settings, File file)
    {
        if (!file.exists())
        {
            settings.save(file);

            return false;
        }

        try
        {
            BaseType data = DataToString.read(file);
            boolean migrated = settings.getId().equals("bbs") && data.isMap() && BBSSettings.migrateLegacySettings(data.asMap());

            settings.fromData(data);

            if (migrated)
            {
                settings.save(file);
            }

            return true;
        }
        catch (Exception e)
        {}

        return false;
    }
}
