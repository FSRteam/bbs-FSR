package mchorse.bbs_mod.particles.components.expiration;

import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public class ParticleComponentExpireNotInBlocks extends ParticleComponentExpireBlocks implements IComponentParticleUpdate
{
    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        if (particle.isDead() || emitter.world == null)
        {
            return;
        }

        BlockState current = this.getBlock(emitter, particle);
        String currentId = BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString();

        for (int i = 0; i < this.blocks.size(); i++)
        {
            String block = this.blocks.get(i);

            if (currentId.equals(block))
            {
                return;
            }
        }

        particle.setDead();
    }
}
