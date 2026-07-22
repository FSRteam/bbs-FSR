package mchorse.bbs_mod.api.plugin;

public enum BBSPluginReloadMode
{
    HOT("hot"),
    RESTART("restart");

    private final String wireName;

    BBSPluginReloadMode(String wireName)
    {
        this.wireName = wireName;
    }

    public String wireName()
    {
        return this.wireName;
    }

    public static BBSPluginReloadMode fromWireName(String value)
    {
        for (BBSPluginReloadMode mode : values())
        {
            if (mode.wireName.equals(value))
            {
                return mode;
            }
        }

        throw new IllegalArgumentException("unknown plugin reload mode '" + value + "'");
    }
}
