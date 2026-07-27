package mchorse.bbs_mod.forms.forms.sound;

/**
 * The cone's geometry, in one place.
 *
 * <p>This is the single source of truth shared by the acoustics, the guide
 * renderer, the pick handles and the drag math. Anything that needs to know
 * where the cone's cap sits or how wide it is at a given angle asks here.</p>
 *
 * <p>That sharing is the point: if the drawn cone and the audible cone were
 * computed separately they would drift, and the visualization would be lying
 * about where the sound actually stops. Change a formula here and every
 * consumer follows.</p>
 *
 * <p>Pure functions only — no Minecraft or BBS imports, so the bare-JVM
 * acoustics test can load it.</p>
 */
public final class SoundConeGeometry
{
    /** Below this the cone is degenerate and would render as a point. */
    public static final float MIN_RANGE = 0.05F;

    private SoundConeGeometry()
    {}

    /**
     * Distance from the apex to the cap plane along the axis.
     *
     * <p>This is the cone's height, and therefore also the distance at which
     * the sound goes silent — the two are the same number by construction.</p>
     */
    public static float capDistance(float range)
    {
        return Math.max(range, MIN_RANGE);
    }

    /**
     * Radius of the cone's cross-section at the cap plane.
     *
     * @param angleDeg full cone angle in degrees, not the half-angle
     */
    public static float coneRadius(float capDistance, float angleDeg)
    {
        float half = SoundAcoustics.clamp(angleDeg, 0F, 179F) * 0.5F;

        return capDistance * (float) Math.tan(Math.toRadians(half));
    }

    /** Inverse of {@link #coneRadius(float, float)} for drag handles. */
    public static float angleForRadius(float capDistance, float radius)
    {
        float cap = Math.max(Math.abs(capDistance), MIN_RANGE);
        float radial = Math.max(radius, 0F);

        return (float) Math.toDegrees(2D * Math.atan2(radial, cap));
    }

    /**
     * Cosine of the half-angle — the dot product threshold a listener direction
     * must exceed to be inside the cone.
     *
     * @param angleDeg full cone angle in degrees
     */
    public static float cosHalfAngle(float angleDeg)
    {
        float half = SoundAcoustics.clamp(angleDeg, 0F, 360F) * 0.5F;

        return (float) Math.cos(Math.toRadians(half));
    }

    /**
     * Clamp the inner cone so it never exceeds the outer one.
     *
     * <p>Applied by the acoustics and by the renderer alike, so a hand-edited
     * save cannot make the drawn inner ring disagree with what is heard.</p>
     */
    public static float clampInnerAngle(float innerDeg, float outerDeg)
    {
        return SoundAcoustics.clamp(innerDeg, 0F, Math.max(outerDeg, 0F));
    }
}
