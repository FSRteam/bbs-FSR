package mchorse.bbs_mod.api;

/**
 * API 2.0 version constants and compatibility helpers.
 */
public final class BBSApiVersion
{
    public static final int MAJOR = 2;
    public static final int MINOR = 0;
    public static final String CURRENT = MAJOR + "." + MINOR;

    private BBSApiVersion() {}

    public static boolean isSupported(String version)
    {
        if (version == null || version.isBlank())
        {
            return false;
        }

        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);

        try
        {
            return Integer.parseInt(major) == MAJOR;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
