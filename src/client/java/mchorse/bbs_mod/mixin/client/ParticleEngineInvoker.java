package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Creates vanilla particles without adding them to the world particle queues. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineInvoker
{
    @Invoker("createParticle")
    Particle bbs$createParticle(ParticleOptions options, double x, double y, double z,
                                double velocityX, double velocityY, double velocityZ);
}
