package mchorse.bbs_mod.particles.components.rate;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.particles.components.IComponentEmitterUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

/**
 * Manual emitter rate - particles are spawned manually via events,
 * only the max_particles limit applies.
 */
public class ParticleComponentRateManual extends ParticleComponentRate implements IComponentEmitterUpdate
{
    public static final MolangExpression DEFAULT_PARTICLES = new MolangValue(null, new Constant(50));

    public ParticleComponentRateManual()
    {
        this.particles = DEFAULT_PARTICLES;
    }

    @Override
    protected void toData(MapType data)
    {
        if (!MolangExpression.isConstant(this.particles, 50))
        {
            data.put("max_particles", this.particles.toData());
        }
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        if (map.has("max_particles"))
        {
            this.particles = parser.parseDataSilently(map.get("max_particles"), MolangParser.ONE);
        }

        return super.fromData(map, parser);
    }

    @Override
    public void update(ParticleEmitter emitter)
    {
        /* Manual rate does not auto-spawn particles - they are spawned by events */
    }
}
