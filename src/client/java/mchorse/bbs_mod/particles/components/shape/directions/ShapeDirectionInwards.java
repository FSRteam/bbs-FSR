package mchorse.bbs_mod.particles.components.shape.directions;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.particles.emitter.Particle;

public class ShapeDirectionInwards extends ShapeDirection
{
    public static final ShapeDirection INWARDS = new ShapeDirectionInwards(-1);
    public static final ShapeDirection OUTWARDS = new ShapeDirectionInwards(1);

    private float factor;

    public ShapeDirectionInwards(float factor)
    {
        this.factor = factor;
    }

    public static ShapeDirection fromString(String value)
    {
        if (value.equals("inwards"))
        {
            return INWARDS;
        }

        return OUTWARDS;
    }

    @Override
    public void applyDirection(Particle particle, double x, double y, double z)
    {
        double dx = particle.position.x - x;
        double dy = particle.position.y - y;
        double dz = particle.position.z - z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length <= 0)
        {
            particle.speed.set(0, 0, 0);
        }
        else
        {
            double scale = this.factor / length;

            particle.speed.set((float) (dx * scale), (float) (dy * scale), (float) (dz * scale));
        }
    }

    @Override
    public BaseType toData()
    {
        return new StringType(this.factor < 0 ? "inwards" : "outwards");
    }
}
