package mchorse.bbs_mod.utils.keyframes;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.factories.AnchorKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;

/**
 * Checks anchor keyframes and resolved transitions without starting Minecraft.
 *
 * <p>Two of these assertions guard regressions that were invisible from the outside and only
 * showed up as "the anchor teleports / the shadow pops" once a shader pass or a second viewport
 * was involved: {@link Matrices#lerp} reading a scaled basis as a normalized rotation, and
 * {@code FilmMatrices} folding an unattached anchor's offset into the caller's own fallback
 * matrix.</p>
 */
public class AnchorInterpolationTest
{
    /* An anchor now addresses its replay by stable id, and the actor map is keyed by that same id.
     * Only presence in the map matters on this path, so the three ids below are arbitrary but
     * valid: eight lowercase hex characters with at least one letter, which is what
     * StableIds.isStableId accepts and what a legacy index can never look like. */
    private static final String TARGET = "aaaaaaaa";
    private static final String OTHER_TARGET = "bbbbbbbb";
    private static final String ABSENT_TARGET = "cccccccc";

    private static int failures;
    private static int checks;

    public static void main(String[] args)
    {
        AnchorKeyframeFactory factory = new AnchorKeyframeFactory();
        testInheritanceFlags(factory);
        Anchor p = anchor("", -4F), a = anchor("", 0F);
        Anchor b = anchor("", 10F), q = anchor("", 12F);


        for (IInterp interp : new IInterp[] {Interpolations.AUTO, Interpolations.AUTO_CLAMPED})
        {
            Transform expected = new Transform();
            expected.autoLerp(p.transform, a.transform, b.transform, q.transform, -2, 0, 20, 23, interp == Interpolations.AUTO_CLAMPED, 0.3F);
            Anchor actual = factory.interpolate(key(p, -2), key(a, 0), key(b, 20), key(q, 23), interp, 0.3F);
            near(actual.transform.translate.x, expected.translate.x, "Auto respects uneven key spacing: " + interp.getKey());
        }

        /* Offsets that belong to a different target live in a different coordinate system, so they
         * must not shape this segment's tangents. */
        Anchor isolatedOther = anchor(OTHER_TARGET, 1000F);
        Anchor boundaryOther = anchor(OTHER_TARGET, -1000F);
        near(
            factory.interpolate(isolatedOther, a, b, boundaryOther, Interpolations.HERMITE, 0.25F).transform.translate.x,
            factory.interpolate(a, a, b, b, Interpolations.HERMITE, 0.25F).transform.translate.x,
            "Foreign target offsets must not shape local tangents"
        );

        Matrix4f ma = new Matrix4f().translation(2, 3, 4).rotateY(0.4F).scale(2, 3, 4);
        Matrix4f mb = new Matrix4f().translation(8, 9, 10).rotateY(0.4F).scale(4, 5, 6);
        matrix(Matrices.lerp(ma, mb, 0.5F), new Matrix4f().translation(5, 6, 7).rotateY(0.4F).scale(3, 4, 5), "Scaled targets retain rotation and interpolate scale");
        matrix(Matrices.lerp(ma, mb, 0), ma, "Exact start matrix");
        matrix(Matrices.lerp(ma, mb, 1), mb, "Exact end matrix");
        ma.scale(-1, 1, 1);
        mb.scale(-1, 1, 1);
        matrix(Matrices.lerp(ma, mb, 0.5F), new Matrix4f().translation(5, 6, 7).rotateY(0.4F).scale(-3, 4, 5), "Mirrored targets retain their signed scale");
        matrix(Matrices.lerp(ma, mb, 0.5F, ma), new Matrix4f().translation(5, 6, 7).rotateY(0.4F).scale(-3, 4, 5), "Destination can alias an input");
        matrix(Matrices.lerp(new Matrix4f().scaling(0), new Matrix4f().scaling(2), 0.5F), new Matrix4f(), "Zero scale stays finite");

        for (IInterp interp : new IInterp[] {Interpolations.LINEAR, Interpolations.CONST, Interpolations.QUAD_IN})
        {
            float expected = interp.interpolate(0F, 10F, 0.3F);
            near(factory.interpolate(a, a, b, b, interp, 0.3F).transform.translate.x, expected, "Same-target curve: " + interp.getKey());
        }

        /* The resolved-transition path runs against the real compiled FilmMatrices, with a standalone
         * IEntity whose only meaningful channel is its X. Both anchors have no target, so the
         * caller's fallback matrix is the base and the anchor's own offset is folded into it. */
        a = anchor(Anchor.NO_ATTACHMENT, 2);
        b = anchor(Anchor.NO_ATTACHMENT, 6);
        for (float t : new float[] {-0.2F, 0, 0.25F, 0.5F, 1, 1.2F})
        {
            Anchor value = factory.interpolate(a, a, b, b, Interpolations.LINEAR, t);
            Matrix4f fallback = new Matrix4f().translation(10, 0, 0);
            Pair<Matrix4f, Float> result = FilmMatrices.getTotalMatrix(new LinkedHashMap<>(), value, fallback, 0D, 0D, 0D, 0F, 0);
            Matrix4f actual = result.a == null ? fallback : result.a;
            near(actual.m30(), 12 + 4 * t, "Independent fallback transforms, including overshoot: " + t);
            near(fallback.m30(), 10, "Fallback matrix is unchanged: " + t);
            near(result.b, 1, "Unattached endpoints retain shadow opacity: " + t);
        }

        IEntity target = entity(20D);
        IEntity other = entity(20D);
        Map<String, IEntity> entities = new LinkedHashMap<>();

        entities.put(TARGET, target);
        entities.put(OTHER_TARGET, other);

        /* Every combination of "resolves to an actor present in the map" and "resolves to nothing
         * at all": an absent id must behave exactly like no target, not like a miss that loses the
         * fallback matrix. */
        for (String[] targets : new String[][] {{Anchor.NO_ATTACHMENT, TARGET}, {TARGET, Anchor.NO_ATTACHMENT}, {TARGET, OTHER_TARGET}, {ABSENT_TARGET, TARGET}})
        {
            a = anchor(targets[0], 2);
            b = anchor(targets[1], 6);

            for (IInterp interp : new IInterp[] {Interpolations.LINEAR, Interpolations.CONST, Interpolations.QUAD_IN, Interpolations.BACK_IN, Interpolations.BACK_OUT})
            {
                for (float t : new float[] {0, 0.25F, 0.5F, 0.75F, 1})
                {
                    Anchor value = factory.interpolate(a, a, b, b, interp, t);
                    boolean fromTarget = entities.containsKey(targets[0]);
                    boolean toTarget = entities.containsKey(targets[1]);
                    float weight = interp.interpolate(0F, 1F, t);
                    float fromX = (fromTarget ? 20 : 10) + 2;
                    float toX = (toTarget ? 20 : 10) + 6;
                    Matrix4f fallback = new Matrix4f().translation(10, 0, 0);
                    Pair<Matrix4f, Float> result = FilmMatrices.getTotalMatrix(entities, value, fallback, 0D, 0D, 0D, 0F, 0);
                    Matrix4f actual = result.a == null ? fallback : result.a;

                    near(actual.m30(), fromX + (toX - fromX) * weight, "Resolved transition: " + interp.getKey() + " / " + t);
                    near(fallback.m30(), 10, "Resolved transition preserves fallback: " + interp.getKey() + " / " + t);

                    float fromOpacity = fromTarget ? 0 : 1;
                    float toOpacity = toTarget ? 0 : 1;

                    near(result.b, Math.max(0, Math.min(1, fromOpacity + (toOpacity - fromOpacity) * weight)), "Shadow fade follows resolved endpoints: " + interp.getKey() + " / " + t);
                }
            }
        }

        System.out.println("Anchor interpolation: " + checks + " checks, " + failures + " failures");

        if (failures > 0)
        {
            throw new AssertionError("Anchor interpolation regressions: " + failures);
        }
    }

    /* A proxy keeps this runnable in a source set that only has the client classpath: IEntity
     * drags in Minecraft's interaction and pose types, none of which are read on this path. */
    private static void testInheritanceFlags(AnchorKeyframeFactory factory)
    {
        Matrix4f target = new Matrix4f().translation(7F, 8F, 9F).rotateY(0.7F).scale(2F, 3F, 4F);
        Matrix4f fallback = new Matrix4f().translation(1F, 2F, 3F).rotateY(-0.3F).scale(5F, 6F, 7F);

        for (int bits = 0; bits < 8; bits++)
        {
            boolean position = (bits & 1) != 0;
            boolean rotation = (bits & 2) != 0;
            boolean scale = (bits & 4) != 0;
            Anchor anchor = new Anchor(TARGET, "head", position, rotation, scale);
            Matrix4f expected = new Matrix4f()
                .translation(position ? 7F : 1F, position ? 8F : 2F, position ? 9F : 3F)
                .rotateY(rotation ? 0.7F : -0.3F)
                .scale(scale ? 2F : 5F, scale ? 3F : 6F, scale ? 4F : 7F);
            matrix(anchor.filterMatrix(target, fallback), expected, "Anchor inheritance combination " + bits);
            Anchor read = factory.fromData(factory.toData(anchor));
            near(read.equals(anchor) ? 1F : 0F, 1F, "New flags survive serialization " + bits);
            near(anchor.copy().equals(anchor) ? 1F : 0F, 1F, "New flags survive copying " + bits);
            MapType saved = anchor.toData();
            near(saved.has("translate") || saved.has("scale") ? 1F : 0F, 0F,
                "Canonical anchor does not rewrite legacy flags " + bits);
        }

        for (boolean translate : new boolean[] {false, true})
        {
            for (boolean scale : new boolean[] {false, true})
            {
                MapType legacy = new MapType();
                legacy.putString("actor", TARGET);
                legacy.putBool("translate", translate);
                legacy.putBool("scale", scale);
                Anchor migrated = factory.fromData(legacy);
                near(migrated.inheritPosition ? 1F : 0F, 1F, "Legacy always inherited position");
                near(migrated.inheritRotation ? 1F : 0F, translate ? 0F : 1F, "Legacy rotation migration");
                near(migrated.inheritScale ? 1F : 0F, translate || scale ? 0F : 1F, "Legacy scale migration");
            }
        }

        MapType mixed = new MapType();
        mixed.putBool("translate", true);
        mixed.putBool("scale", true);
        mixed.putBool("inheritPosition", false);
        Anchor canonical = factory.fromData(mixed);
        near(!canonical.inheritPosition && canonical.inheritRotation && canonical.inheritScale ? 1F : 0F,
            1F, "New flags take precedence and absent new flags default to true");

        Anchor from = new Anchor(TARGET, "head", true, true, true);
        Anchor to = new Anchor(TARGET, "head", true, false, true);
        Anchor interpolated = factory.interpolate(from, from, to, to, Interpolations.LINEAR, 0.5F);
        near(interpolated.previous != null ? 1F : 0F, 1F,
            "Changing inheritance interpolates resolved frames instead of unrelated local transforms");

        matrix(target, new Matrix4f().translation(7F, 8F, 9F).rotateY(0.7F).scale(2F, 3F, 4F),
            "Filtering preserves the target input");
        matrix(fallback, new Matrix4f().translation(1F, 2F, 3F).rotateY(-0.3F).scale(5F, 6F, 7F),
            "Filtering preserves the fallback input");
    }

    private static IEntity entity(double x)
    {
        return (IEntity) Proxy.newProxyInstance(IEntity.class.getClassLoader(), new Class<?>[] {IEntity.class}, (proxy, method, values) ->
        {
            if (method.getReturnType() == double.class) return x;
            if (method.getReturnType() == float.class) return method.getName().startsWith("getPrev") ? 0F : 0F;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;

            return null;
        });
    }

    private static Anchor anchor(String replay, float x)
    {
        Anchor result = new Anchor(replay, "", false, false);

        result.transform.translate.x = x;

        return result;
    }

    private static Keyframe<Anchor> key(Anchor value, float tick)
    {
        return new Keyframe<>("", new AnchorKeyframeFactory(), tick, value);
    }

    private static void matrix(Matrix4f actual, Matrix4f expected, String label)
    {
        checks++;

        if (!actual.equals(expected, 0.0001F))
        {
            failures++;
            System.err.println("FAIL " + label + "\nactual:\n" + actual + "expected:\n" + expected);
        }
    }

    private static void near(float actual, float expected, String label)
    {
        checks++;

        if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.0001F)
        {
            failures++;
            System.err.println("FAIL " + label + ": " + actual + " != " + expected);
        }
    }
}
