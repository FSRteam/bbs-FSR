package mchorse.bbs_mod.particles.components.appearance;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

/**
 * Collision tinting component — applies a different color tint
 * when a particle has collided with something.
 *
 * Blockbuster extension, stored as "minecraft:particle_collision_tinting"
 * in JSON. Snowstorm ignores unknown component keys (round-trip safe).
 */
public class ParticleComponentCollisionTinting extends ParticleComponentAppearanceTinting
{
    public MolangExpression enabled = MolangParser.ZERO;

    @Override
    protected void toData(MapType data)
    {
        data.put("enabled", this.enabled.toData());

        super.toData(data);
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        if (map.has("enabled")) this.enabled = parser.parseDataSilently(map.get("enabled"));

        return super.fromData(data, parser);
    }

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        if (isCollisionTintingEnabled() && particle.intersected)
        {
            this.renderUI(particle, builder, matrix, transition);
        }
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        super.renderUI(particle, builder, matrix, transition);
    }

    public boolean isCollisionTintingEnabled()
    {
        return MolangExpression.isOne(this.enabled);
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }

    @Override
    public int getSortingIndex()
    {
        return -5;
    }
}
