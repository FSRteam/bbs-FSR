package mchorse.bbs_mod.api.plugin;

public enum BBSPluginSide
{
    COMMON("common"),
    CLIENT("client"),
    DEDICATED_SERVER("dedicated_server");

    private final String wireName;

    BBSPluginSide(String wireName)
    {
        this.wireName = wireName;
    }

    public String wireName()
    {
        return this.wireName;
    }

    public static BBSPluginSide fromWireName(String value)
    {
        for (BBSPluginSide side : values())
        {
            if (side.wireName.equals(value))
            {
                return side;
            }
        }

        throw new IllegalArgumentException("unknown plugin side '" + value + "'");
    }
}
