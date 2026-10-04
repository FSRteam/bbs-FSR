package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;

public final class SettingsMigrationTest
{
    public static void main(String[] args)
    {
        MapType root = new MapType();
        MapType editor = new MapType();
        editor.putInt("speed", 7);
        editor.putInt("duration", 45);
        editor.putInt("preview_custom_width", 2560);
        root.put("editor", editor);

        MapType dc = new MapType();
        dc.putBool("enabled", false);
        root.put("dc", dc);

        check(BBSSettings.migrateLegacySettings(root), "legacy category migration was not reported");
        check(root.getMap("camera").getInt("speed") == 7, "camera setting did not migrate");
        check(root.getMap("timeline").getInt("duration") == 45, "timeline setting did not migrate");
        check(root.getMap("viewport").getInt("preview_custom_width") == 2560, "viewport setting did not migrate");
        check(!root.getMap("misc").getBool("damage_control"), "misc setting did not migrate");

        root.getMap("camera").putInt("speed", 9);
        BBSSettings.migrateLegacySettings(root);
        check(root.getMap("camera").getInt("speed") == 9, "migration overwrote a current value");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
