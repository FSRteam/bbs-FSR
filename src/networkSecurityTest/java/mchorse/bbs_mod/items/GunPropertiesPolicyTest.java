package mchorse.bbs_mod.items;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.pose.Transform;

public final class GunPropertiesPolicyTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("GunPropertiesPolicyTest passed");
    }

    public static void runAll()
    {
        testOrdinaryGunAndBoundaries();
        testUnboundedSpawnAndNonFiniteMotion();
        testFovAndCommandInputs();
        testCommandBearingMutationAuthorization();
        testMapBoundaryParsesBeforeAccepting();
    }

    private static void testOrdinaryGunAndBoundaries()
    {
        GunProperties properties = new GunProperties();

        check(GunPropertiesPolicy.isAllowed(properties), "default gun properties were rejected");

        properties.projectiles = GunPropertiesPolicy.MAX_PROJECTILES;
        properties.lifeSpan = GunPropertiesPolicy.MAX_LIFE_SPAN_TICKS;
        properties.speed = GunPropertiesPolicy.MAX_PROJECTILE_SPEED;
        properties.friction = GunPropertiesPolicy.MAX_FRICTION;
        properties.gravity = -GunPropertiesPolicy.MAX_ABSOLUTE_GRAVITY;
        properties.fadeIn = GunPropertiesPolicy.MAX_FADE_TICKS;
        properties.fadeOut = GunPropertiesPolicy.MAX_FADE_TICKS;
        properties.bounces = GunPropertiesPolicy.MAX_BOUNCES;
        properties.bounceDamping = GunPropertiesPolicy.MAX_BOUNCE_DAMPING;
        properties.damage = GunPropertiesPolicy.MAX_DAMAGE;
        properties.knockback = GunPropertiesPolicy.MAX_KNOCKBACK;
        properties.ticking = GunPropertiesPolicy.MAX_TICKING_INTERVAL_TICKS;

        check(GunPropertiesPolicy.isAllowed(properties), "exact gun runtime boundaries were rejected");
    }

    private static void testUnboundedSpawnAndNonFiniteMotion()
    {
        GunProperties properties = new GunProperties();

        properties.projectiles = Integer.MAX_VALUE;
        check(!GunPropertiesPolicy.isAllowed(properties), "INT_MAX projectile spawn loop was accepted");

        properties = new GunProperties();
        properties.speed = Float.NaN;
        check(!GunPropertiesPolicy.isAllowed(properties), "NaN projectile speed was accepted");

        properties = new GunProperties();
        properties.launchPower = Float.NaN;
        check(!GunPropertiesPolicy.isAllowed(properties), "NaN launch power was accepted");

        properties = new GunProperties();
        properties.friction = Float.POSITIVE_INFINITY;
        check(!GunPropertiesPolicy.isAllowed(properties), "infinite projectile friction was accepted");

        properties = new GunProperties();
        properties.lifeSpan = 0;
        check(!GunPropertiesPolicy.isAllowed(properties), "zero projectile lifespan was accepted");
    }

    private static void testFovAndCommandInputs()
    {
        GunProperties properties = new GunProperties();

        properties.fovDuration = 0;
        check(!GunPropertiesPolicy.isAllowed(properties), "zero FOV duration was accepted");

        properties = new GunProperties();
        properties.fovInterp.setV1(Double.NaN);
        check(!GunPropertiesPolicy.isAllowed(properties), "NaN FOV interpolation argument was accepted");

        properties = new GunProperties();
        properties.fovInterp = null;
        check(!GunPropertiesPolicy.isAllowed(properties), "missing FOV interpolation crashed open");

        properties = new GunProperties();
        properties.projectileTransform.translate.x = Float.NaN;
        check(!GunPropertiesPolicy.isProjectileRuntimeAllowed(properties),
            "c13 projectile subset accepted a non-finite transform");

        properties = new GunProperties();
        properties.getTransformFirstPerson().scale.z = Float.POSITIVE_INFINITY;
        check(!GunPropertiesPolicy.isAllowed(properties), "non-finite held-gun transform was accepted");

        properties = new GunProperties();
        properties.projectileTransform.rotationMode = Transform.RotationMode.QUATERNION;
        properties.projectileTransform.quat.set(Float.NaN, 0F, 0F, 1F);
        check(!GunPropertiesPolicy.isProjectileRuntimeAllowed(properties),
            "non-finite projectile quaternion was accepted");

        properties = new GunProperties();
        properties.projectileTransform.rotationMode = Transform.RotationMode.QUATERNION;
        properties.projectileTransform.quat.set(0F, 0F, 0F, 0F);
        check(!GunPropertiesPolicy.isProjectileRuntimeAllowed(properties),
            "zero-length projectile quaternion was accepted");

        properties = new GunProperties();
        properties.cmdImpact = "say first\nsay second";
        check(!GunPropertiesPolicy.isAllowed(properties), "multi-line gun command was accepted");

        properties = new GunProperties();
        properties.cmdFiring = "x".repeat(GunPropertiesPolicy.MAX_COMMAND_LENGTH + 1);
        check(!GunPropertiesPolicy.isAllowed(properties), "oversized gun command was accepted");
    }

    private static void testCommandBearingMutationAuthorization()
    {
        GunProperties properties = new GunProperties();

        check(GunPropertiesPolicy.isUseAllowed(properties, false),
            "ordinary player could not use safe numeric gun data");
        check(GunPropertiesPolicy.isMutationAllowed(properties, true, false),
            "ordinary bbsEditing user could not save safe numeric gun data");

        properties.cmdImpact = "say impact";

        check(!GunPropertiesPolicy.isUseAllowed(properties, false),
            "ordinary player used command-bearing gun data");
        check(GunPropertiesPolicy.isUseAllowed(properties, true),
            "administrator could not use valid command-bearing gun data");
        check(!GunPropertiesPolicy.isMutationAllowed(properties, true, false),
            "ordinary bbsEditing user saved command-bearing gun data");
        check(GunPropertiesPolicy.isMutationAllowed(properties, true, true),
            "administrator could not save valid command-bearing gun data");
        check(!GunPropertiesPolicy.isMutationAllowed(properties, false, true),
            "admin permission bypassed the disabled panel/editing gate for s2");

        check(!PermissionUtils.isAdminPermissionLevel(0), "ordinary permission level was treated as admin");
        check(PermissionUtils.isAdminPermissionLevel(PermissionUtils.ADMIN_PERMISSION_LEVEL),
            "level-2 /bbs administrator was rejected");
        check(PermissionUtils.isAdminPermissionLevel(4), "single-player owner/operator level was rejected");

        properties.projectiles = GunPropertiesPolicy.MAX_PROJECTILES + 1;

        check(!GunPropertiesPolicy.isUseAllowed(properties, true),
            "administrator bypassed invalid runtime gun data");
    }

    private static void testMapBoundaryParsesBeforeAccepting()
    {
        GunProperties normal = new GunProperties();
        MapType normalData = new MapType();

        normal.toData(normalData);

        check(GunPropertiesPolicy.parseAllowed(normalData) != null, "ordinary serialized GunData was rejected");
        check(GunPropertiesPolicy.parseAllowed(new MapType()) == null,
            "partial GunData silently replaced constructor defaults with unsafe zeroes");

        MapType projectileBomb = (MapType) normalData.copy();
        projectileBomb.putInt("projectiles", Integer.MAX_VALUE);

        check(GunPropertiesPolicy.parseAllowed(projectileBomb) == null,
            "s2 map parsing accepted an INT_MAX projectile count");

        MapType nonFiniteMotion = (MapType) normalData.copy();
        nonFiniteMotion.putFloat("speed", Float.NaN);

        check(GunPropertiesPolicy.parseAllowed(nonFiniteMotion) == null,
            "s2 map parsing accepted NaN projectile speed");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
