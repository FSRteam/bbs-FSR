package mchorse.bbs_mod.particles.components.motion;

import mchorse.bbs_mod.particles.ParticleScheme;

/**
 * Coordinates the dynamic and parametric components as independent position and rotation axes.
 */
public class MotionComponents
{
    public static ParticleComponentMotionDynamic dynamic(ParticleScheme scheme)
    {
        return scheme == null ? null : scheme.get(ParticleComponentMotionDynamic.class);
    }

    public static ParticleComponentMotionParametric parametric(ParticleScheme scheme)
    {
        return scheme == null ? null : scheme.get(ParticleComponentMotionParametric.class);
    }

    public static boolean isPositionParametric(ParticleScheme scheme)
    {
        ParticleComponentMotionParametric parametric = parametric(scheme);

        return parametric != null && parametric.drivesPosition;
    }

    public static boolean isRotationParametric(ParticleScheme scheme)
    {
        ParticleComponentMotionParametric parametric = parametric(scheme);

        return parametric != null && parametric.drivesRotation;
    }

    public static void setModes(ParticleScheme scheme, boolean positionParametric, boolean rotationParametric)
    {
        if (scheme == null)
        {
            return;
        }

        boolean needDynamic = !positionParametric || !rotationParametric;
        boolean needParametric = positionParametric || rotationParametric;
        ParticleComponentMotionDynamic dynamic = dynamic(scheme);
        ParticleComponentMotionParametric parametric = parametric(scheme);
        boolean changed = false;

        if (needDynamic && dynamic == null)
        {
            dynamic = scheme.add(ParticleComponentMotionDynamic.class);
            changed = true;
        }
        else if (!needDynamic && dynamic != null)
        {
            scheme.remove(ParticleComponentMotionDynamic.class);
            dynamic = null;
            changed = true;
        }

        if (needParametric && parametric == null)
        {
            parametric = scheme.add(ParticleComponentMotionParametric.class);
            changed = true;
        }
        else if (!needParametric && parametric != null)
        {
            scheme.remove(ParticleComponentMotionParametric.class);
            parametric = null;
            changed = true;
        }

        if (dynamic != null)
        {
            dynamic.drivesPosition = !positionParametric;
            dynamic.drivesRotation = !rotationParametric;
        }

        if (parametric != null)
        {
            parametric.drivesPosition = positionParametric;
            parametric.drivesRotation = rotationParametric;
        }

        /* remove() doesn't rebuild the cached component interface lists. */
        if (changed)
        {
            scheme.setup();
        }
    }
}
