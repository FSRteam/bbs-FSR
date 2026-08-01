package mchorse.bbs_mod.items;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Runtime safety contract for gun data loaded from an item or received from an
 * editor. The editor deliberately supports free-form numeric input, so the
 * bounds here are wider than normal gameplay values while still preventing
 * unbounded entity work and non-finite motion state.
 */
public final class GunPropertiesPolicy
{
    public static final int MAX_PROJECTILES = 64;
    public static final float MAX_LAUNCH_POWER = 64F;
    public static final float MAX_SCATTER_DEGREES = 3_600F;
    public static final int MAX_LIFE_SPAN_TICKS = 20 * 60 * 10;
    public static final float MAX_PROJECTILE_SPEED = 64F;
    public static final float MAX_FRICTION = 1F;
    public static final float MAX_ABSOLUTE_GRAVITY = 4F;
    public static final int MAX_FADE_TICKS = MAX_LIFE_SPAN_TICKS;
    public static final int MAX_BOUNCES = 64;
    public static final float MAX_BOUNCE_DAMPING = 1F;
    public static final float MAX_DAMAGE = 2_048F;
    public static final float MAX_KNOCKBACK = 64F;
    public static final int MAX_FOV_DURATION_TICKS = 1_000;
    public static final float MIN_FOV = 0.001F;
    public static final float MAX_FOV = 179.999F;
    public static final double MAX_INTERPOLATION_ARGUMENT = 1_000_000D;
    public static final int MAX_TICKING_INTERVAL_TICKS = MAX_LIFE_SPAN_TICKS;
    public static final int MAX_COMMAND_LENGTH = 10_000;
    public static final float MAX_TRANSFORM_TRANSLATION = 1_024F;
    public static final float MAX_TRANSFORM_SCALE = 1_024F;
    public static final float MAX_TRANSFORM_ROTATION = 100_000F;
    private static final float MIN_QUATERNION_LENGTH_SQUARED = 0.999F;
    private static final float MAX_QUATERNION_LENGTH_SQUARED = 1.001F;

    private GunPropertiesPolicy()
    {}

    /** Parse once at an untrusted map boundary and reject the whole payload. */
    public static GunProperties parseAllowed(MapType data)
    {
        if (data == null)
        {
            return null;
        }

        try
        {
            GunProperties properties = new GunProperties();

            properties.fromData(data);

            return isAllowed(properties) ? properties : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    public static boolean isAllowed(GunProperties properties)
    {
        return properties != null
            && isFiniteInRange(properties.launchPower, -MAX_LAUNCH_POWER, MAX_LAUNCH_POWER)
            && isFiniteInRange(properties.scatterX, -MAX_SCATTER_DEGREES, MAX_SCATTER_DEGREES)
            && isFiniteInRange(properties.scatterY, -MAX_SCATTER_DEGREES, MAX_SCATTER_DEGREES)
            /* Zero is the legacy encoding for one projectile in GunItem. */
            && isIntInRange(properties.projectiles, 0, MAX_PROJECTILES)
            && isProjectileRuntimeAllowed(properties)
            && isTransformAllowed(properties.getTransform())
            && isTransformAllowed(properties.getTransformThirdPerson())
            && isTransformAllowed(properties.getTransformInventory())
            && isTransformAllowed(properties.getTransformFirstPerson())
            && isTransformAllowed(properties.zoomTransform)
            && isIntInRange(properties.fovDuration, 1, MAX_FOV_DURATION_TICKS)
            && isFiniteInRange(properties.fovTarget, MIN_FOV, MAX_FOV)
            && properties.fovInterp != null
            && isFiniteInRange(properties.fovInterp.getV1(), -MAX_INTERPOLATION_ARGUMENT, MAX_INTERPOLATION_ARGUMENT)
            && isFiniteInRange(properties.fovInterp.getV2(), -MAX_INTERPOLATION_ARGUMENT, MAX_INTERPOLATION_ARGUMENT)
            && isFiniteInRange(properties.fovInterp.getV3(), -MAX_INTERPOLATION_ARGUMENT, MAX_INTERPOLATION_ARGUMENT)
            && isFiniteInRange(properties.fovInterp.getV4(), -MAX_INTERPOLATION_ARGUMENT, MAX_INTERPOLATION_ARGUMENT)
            && isIntInRange(properties.ticking, 0, MAX_TICKING_INTERVAL_TICKS)
            && isCommandAllowed(properties.cmdZoomOn)
            && isCommandAllowed(properties.cmdZoomOff)
            && isCommandAllowed(properties.cmdFiring)
            && isCommandAllowed(properties.cmdImpact)
            && isCommandAllowed(properties.cmdVanish)
            && isCommandAllowed(properties.cmdTicking);
    }

    /** Fields carried by c13 and consumed by an already-spawned projectile. */
    public static boolean isProjectileRuntimeAllowed(GunProperties properties)
    {
        return properties != null
            && isIntInRange(properties.lifeSpan, 1, MAX_LIFE_SPAN_TICKS)
            && isFiniteInRange(properties.speed, 0F, MAX_PROJECTILE_SPEED)
            && isFiniteInRange(properties.friction, 0F, MAX_FRICTION)
            && isFiniteInRange(properties.gravity, -MAX_ABSOLUTE_GRAVITY, MAX_ABSOLUTE_GRAVITY)
            && isIntInRange(properties.fadeIn, 0, MAX_FADE_TICKS)
            && isIntInRange(properties.fadeOut, 0, MAX_FADE_TICKS)
            && isIntInRange(properties.bounces, 0, MAX_BOUNCES)
            && isFiniteInRange(properties.bounceDamping, 0F, MAX_BOUNCE_DAMPING)
            && isFiniteInRange(properties.damage, 0F, MAX_DAMAGE)
            && isFiniteInRange(properties.knockback, 0F, MAX_KNOCKBACK)
            && isTransformAllowed(properties.projectileTransform);
    }

    /**
     * bbsEditing authorizes ordinary numeric gun authoring. A payload which
     * embeds any executable command additionally requires the same level-2
     * permission as an administrative /bbs command.
     */
    public static boolean isMutationAllowed(
        GunProperties properties,
        boolean panelsAllowed,
        boolean hasAdminPermission
    )
    {
        return panelsAllowed
            && isAllowed(properties)
            && (!hasAnyCommand(properties) || hasAdminPermission);
    }

    /** Command-bearing guns require administrator authority at the final use boundary. */
    public static boolean isUseAllowed(GunProperties properties, boolean hasAdminPermission)
    {
        return isAllowed(properties)
            && (!hasAnyCommand(properties) || hasAdminPermission);
    }

    public static boolean hasAnyCommand(GunProperties properties)
    {
        return properties != null
            && (hasText(properties.cmdZoomOn)
                || hasText(properties.cmdZoomOff)
                || hasText(properties.cmdFiring)
                || hasText(properties.cmdImpact)
                || hasText(properties.cmdVanish)
                || hasText(properties.cmdTicking));
    }

    public static boolean isCommandAllowed(String command)
    {
        if (command == null || command.length() > MAX_COMMAND_LENGTH)
        {
            return false;
        }

        for (int i = 0; i < command.length(); i++)
        {
            char character = command.charAt(i);

            if (character == '\0' || character == '\r' || character == '\n')
            {
                return false;
            }
        }

        return true;
    }

    public static boolean isTransformAllowed(Transform transform)
    {
        return transform != null
            && isVectorAllowed(transform.translate, MAX_TRANSFORM_TRANSLATION)
            && isVectorAllowed(transform.scale, MAX_TRANSFORM_SCALE)
            && isRotationAllowed(transform);
    }

    private static boolean isRotationAllowed(Transform transform)
    {
        if (transform.rotationMode == Transform.RotationMode.EULER)
        {
            return isVectorAllowed(transform.rotate, MAX_TRANSFORM_ROTATION);
        }

        if (transform.rotationMode == Transform.RotationMode.QUATERNION)
        {
            Quaternionf quaternion = transform.quat;

            if (quaternion == null
                || !Float.isFinite(quaternion.x)
                || !Float.isFinite(quaternion.y)
                || !Float.isFinite(quaternion.z)
                || !Float.isFinite(quaternion.w))
            {
                return false;
            }

            float lengthSquared = quaternion.lengthSquared();

            return lengthSquared >= MIN_QUATERNION_LENGTH_SQUARED
                && lengthSquared <= MAX_QUATERNION_LENGTH_SQUARED;
        }

        return false;
    }

    private static boolean hasText(String command)
    {
        return command != null && !command.isEmpty();
    }

    private static boolean isFiniteInRange(float value, float minimum, float maximum)
    {
        return Float.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static boolean isFiniteInRange(double value, double minimum, double maximum)
    {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static boolean isVectorAllowed(Vector3f vector, float maximumAbsoluteValue)
    {
        return vector != null
            && isFiniteInRange(vector.x, -maximumAbsoluteValue, maximumAbsoluteValue)
            && isFiniteInRange(vector.y, -maximumAbsoluteValue, maximumAbsoluteValue)
            && isFiniteInRange(vector.z, -maximumAbsoluteValue, maximumAbsoluteValue);
    }

    private static boolean isIntInRange(int value, int minimum, int maximum)
    {
        return value >= minimum && value <= maximum;
    }
}
