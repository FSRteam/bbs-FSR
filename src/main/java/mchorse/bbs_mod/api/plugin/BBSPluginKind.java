package mchorse.bbs_mod.api.plugin;

public enum BBSPluginKind
{
    CODE("code"),
    CONTENT("content");

    private final String wireName;

    BBSPluginKind(String wireName)
    {
        this.wireName = wireName;
    }

    public String wireName()
    {
        return this.wireName;
    }

    public static BBSPluginKind fromWireName(String value)
    {
        for (BBSPluginKind kind : values())
        {
            if (kind.wireName.equals(value))
            {
                return kind;
            }
        }

        throw new IllegalArgumentException("unknown plugin kind '" + value + "'");
    }
}
