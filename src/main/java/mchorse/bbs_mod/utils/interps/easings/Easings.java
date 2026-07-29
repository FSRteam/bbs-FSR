package mchorse.bbs_mod.utils.interps.easings;

/**
 * Easings formulas. A couple of notes:
 *
 * <ul>
 *   <li>Exponential easing has some value remapping, because by itself naturally, the
 *   function in its pure form has a range of <code>0.001..1</code>, therefore extra
 *   processing is done to make it exactly, or very close to, <code>0..1</code> range.</li>
 *   <li>Elastic easing has domain remapping, because, as well as exponential easing,
 *   its purest form doesn't start at (0, 0), so in order to guarantee (0, 0) the domain
 *   has to be adjusted so that x <code>0..1</code> mapped to <code>0.025..1</code>.</li>
 * </ul>
 */
public class Easings
{
    public static final IEasing CONST = (args, x) -> 0;
    public static final IEasing LINEAR = (args, x) -> x;
    public static final IEasing QUADRATIC = (args, x) -> x * x;
    public static final IEasing CUBIC = (args, x) -> x * x * x;
    public static final IEasing QUARTIC = (args, x) -> x * x * x * x;
    public static final IEasing QUINTIC = (args, x) -> x * x * x * x * x;
    public static final IEasing EXP = (args, x) -> (Math.pow(2D, 10D * (x - 1D)) - 0.001D) / 0.999D;
    public static final IEasing BACK = (args, x) ->
    {
        final double c1 = 1.70158D + args.v1;
        final double c3 = c1 + 1;

        return c3 * x * x * x - c1 * x * x;
    };
    public static final IEasing ELASTIC = (args, x) -> elasticIn((x + 0.025D) * 0.975D, args.v1);
    public static final IEasing BOUNCE = (args, x) -> 1D - bounceIn(1D - x, 4.5D + args.v1, 5D + args.v2);
    public static final IEasing SINE = (args, x) -> 1D - Math.sin(((1D - x) * Math.PI) / 2);
    public static final IEasing CIRCLE = (args, x) -> 1D - Math.sqrt(1D - Math.pow(x, 2));

    /* Helper methods */

    public static double elasticIn(double x, double amp)
    {
        final double c4 = (2 * Math.PI) / 3;

        amp += 10;

        return -Math.pow(2, amp * x - amp) * Math.sin((x * 10 - 10.75) * c4);
    }

    public static double bounceIn(double x, double n, double lambda)
    {
        return 1 - Math.abs(Math.cos(n * Math.PI * x) * Math.exp(-lambda * x));
    }

    /* Easing factories */

    /**
     * Cubic Bezier timing with implicit (0, 0) and (1, 1) endpoints — the same shape CSS'
     * {@code cubic-bezier()} and Core Animation's timing functions describe. The curve is
     * parametric, so x is first solved for the curve parameter, then that parameter's y is
     * returned. Control points are expected inside 0..1, which keeps x monotonic and the
     * solve well behaved.
     */
    public static IEasing cubicBezier(double x1, double y1, double x2, double y2)
    {
        return (args, x) -> bezierAxis(bezierParameter(x, x1, x2), y1, y2);
    }

    private static double bezierAxis(double t, double p1, double p2)
    {
        double inv = 1D - t;

        return 3D * inv * inv * t * p1 + 3D * inv * t * t * p2 + t * t * t;
    }

    private static double bezierSlope(double t, double p1, double p2)
    {
        double inv = 1D - t;

        return 3D * inv * inv * p1 + 6D * inv * t * (p2 - p1) + 3D * t * t * (1D - p2);
    }

    private static double bezierParameter(double x, double x1, double x2)
    {
        if (x <= 0D)
        {
            return 0D;
        }

        if (x >= 1D)
        {
            return 1D;
        }

        double t = x;

        for (int i = 0; i < 8; i++)
        {
            double error = bezierAxis(t, x1, x2) - x;

            if (Math.abs(error) < 1e-6D)
            {
                return t;
            }

            double slope = bezierSlope(t, x1, x2);

            if (Math.abs(slope) < 1e-6D)
            {
                break;
            }

            t -= error / slope;
        }

        /* Newton stalls on nearly flat segments, so finish with a bounded bisection. */
        double low = 0D;
        double high = 1D;

        t = x;

        for (int i = 0; i < 24; i++)
        {
            double value = bezierAxis(t, x1, x2);

            if (Math.abs(value - x) < 1e-6D)
            {
                break;
            }

            if (value < x)
            {
                low = t;
            }
            else
            {
                high = t;
            }

            t = (low + high) / 2D;
        }

        return t;
    }

    public static IEasing out(IEasing easing)
    {
        return (args, x) -> 1D - easing.calculate(args, 1D - x);
    }

    public static IEasing inOut(IEasing easing)
    {
        return (args, x) ->
        {
            if (x < 0.5D)
            {
                return easing.calculate(args, x * 2) / 2D;
            }

            double newX = (x - 0.5D) * 2;
            double newY = 1D - easing.calculate(args, 1D - newX);

            return newY / 2D + 0.5D;
        };
    }
}