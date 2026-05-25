package mchorse.bbs_mod.particles.components.shape;

import mchorse.bbs_mod.particles.components.shape.directions.ShapeDirectionVector;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

/**
 * Custom shape component (minecraft:emitter_shape_custom)
 *
 * Uses only the base offset and direction fields — the simplest shape.
 * Particles spawn at the offset position with the configured direction.
 */
public class ParticleComponentShapeCustom extends ParticleComponentShapeBase
{
    @Override
    public void apply(ParticleEmitter emitter, Particle particle)
    {
        particle.position.x = (float) this.offset[0].get();
        particle.position.y = (float) this.offset[1].get();
        particle.position.z = (float) this.offset[2].get();

        if (this.direction instanceof ShapeDirectionVector)
        {
            this.direction.applyDirection(particle, particle.position.x, particle.position.y, particle.position.z);
        }
    }
}
