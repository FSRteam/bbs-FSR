package mchorse.bbs_mod.particles.components.meta;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.IComponentParticleRender;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

/**
 * Particle initialization component (minecraft:particle_initialization)
 *
 * Per-update and per-render MoLang expressions that run on each particle.
 * Different from emitter_initialization which runs on the emitter.
 */
public class ParticleComponentParticleInitialization extends ParticleComponentBase implements IComponentParticleUpdate, IComponentParticleRender
{
    public MolangExpression perUpdate = MolangParser.ZERO;
    public MolangExpression perRender = MolangParser.ZERO;

    @Override
    protected void toData(MapType data)
    {
        if (!MolangExpression.isZero(this.perUpdate)) data.put("per_update_expression", this.perUpdate.toData());
        if (!MolangExpression.isZero(this.perRender)) data.put("per_render_expression", this.perRender.toData());
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        if (map.has("per_update_expression")) this.perUpdate = parser.parseGlobalData(map.get("per_update_expression"));
        if (map.has("per_render_expression")) this.perRender = parser.parseGlobalData(map.get("per_render_expression"));

        return super.fromData(map, parser);
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        this.perUpdate.get();
    }

    @Override
    public void preRender(ParticleEmitter emitter, float transition)
    {}

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        this.perRender.get();
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        this.perRender.get();
    }

    @Override
    public void postRender(ParticleEmitter emitter, float transition)
    {}

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
