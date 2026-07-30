package bbssmokefixture.v1;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentParticleRender;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import org.joml.Matrix4f;

/**
 * 1.0 particle appearance override: forces every particle carrying the
 * {@code bbssmokefixture:bbssmokefixture_tint} component to render solid
 * red, distinct from the 2.0 variant's green. Add
 * {@code "bbssmokefixture:bbssmokefixture_tint": {}} to a particle effect's
 * {@code components} object to see this take effect (see task report for a
 * minimal example JSON).
 */
public final class SmokeParticleComponent extends ParticleComponentBase implements IComponentParticleRender
{
    @Override
    public boolean canBeEmpty()
    {
        return true;
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        return this;
    }

    @Override
    public void preRender(ParticleEmitter emitter, float transition)
    {}

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        this.renderUI(particle, builder, matrix, transition);
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        particle.r = 1F;
        particle.g = 0.2F;
        particle.b = 0.2F;
        particle.a = 1F;
    }

    @Override
    public void postRender(ParticleEmitter emitter, float transition)
    {}
}
