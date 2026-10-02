package mchorse.bbs_mod.utils.keyframes;

import java.lang.reflect.Proxy;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
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
    private static int failures;
    private static int checks;

    public static void main(String[] args)
    {
        AnchorKeyframeFactory factory = new AnchorKeyframeFactory();
        Anchor p = anchor(0, -4F), a = anchor(0, 0F);
        Anchor b = anchor(0, 10F), q = anchor(0, 12F);

        for (IInterp interp : new IInterp[] {Interpolations.AUTO, Interpolations.AUTO_CLAMPED})
        {
            Transform expected = new Transform();
            expected.autoLerp(p.transform, a.transform, b.transform, q.transform, -2, 0, 20, 23, interp == Interpolations.AUTO_CLAMPED, 0.3F);
            Anchor actual = factory.interpolate(key(p, -2), key(a, 0), key(b, 20), key(q, 23), interp, 0.3F);
            near(actual.transform.translate.x, expected.translate.x, "Auto respects uneven key spacing: " + interp.getKey());
        }

        /* Offsets that belong to a different target live in a different coordinate system, so they
         * must not shape this segment's tangents. */
        Anchor isolatedOther = anchor(1, 1000F);
        Anchor boundaryOther = anchor(1, -1000F);
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
         * IEntity whose only meaningful channel is its X. */
        a = anchor(Anchor.NO_ATTACHMENT, 2);
        b = anchor(Anchor.NO_ATTACHMENT, 6);
        for (float t : new float[] {-0.2F, 0, 0.25F, 0.5F, 1, 1.2F})
        {
            Anchor value = factory.interpolate(a, a, b, b, Interpolations.LINEAR, t);
            Matrix4f fallback = new Matrix4f().translation(10, 0, 0);
            Pair<Matrix4f, Float> result = FilmMatrices.getTotalMatrix(new IntObjectHashMap<>(), value, fallback, 0D, 0D, 0D, 0F, 0);
            Matrix4f actual = result.a == null ? fallback : result.a;
            near(actual.m30(), 12 + 4 * t, "Independent fallback transforms, including overshoot: " + t);
            near(fallback.m30(), 10, "Fallback matrix is unchanged: " + t);
            near(result.b, 1, "Unattached endpoints retain shadow opacity: " + t);
        }

        IEntity target = entity(20D);
        IEntity other = entity(20D);
        IntObjectMap<IEntity> entities = new IntObjectHashMap<>();

        entities.put(0, target);
        entities.put(1, other);

        for (int[] targets : new int[][] {{Anchor.NO_ATTACHMENT, 0}, {0, Anchor.NO_ATTACHMENT}, {0, 1}, {7, 0}})
        {
            a = anchor(targets[0], 2);
            b = anchor(targets[1], 6);

            for (IInterp interp : new IInterp[] {Interpolations.LINEAR, Interpolations.CONST, Interpolations.QUAD_IN, Interpolations.BACK_IN, Interpolations.BACK_OUT})
            {
                for (float t : new float[] {0, 0.25F, 0.5F, 0.75F, 1})
                {
                    Anchor value = factory.interpolate(a, a, b, b, interp, t);
                    boolean fromTarget = targets[0] == 0 || targets[0] == 1;
                    boolean toTarget = targets[1] == 0 || targets[1] == 1;
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

    private static Anchor anchor(int replay, float x)
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
