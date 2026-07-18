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
        return version != null && CURRENT.equals(version.trim());
    }
}
