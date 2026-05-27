package mchorse.bbs_mod.particles.components.appearance;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleMaterial;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.resources.Link;

/**
 * Collision appearance component — renders a different billboard appearance
 * when a particle has collided with something.
 *
 * Blockbuster extension, stored as "minecraft:particle_collision_appearance"
 * in JSON. Snowstorm ignores unknown component keys (round-trip safe).
 */
public class ParticleComponentCollisionAppearance extends ParticleComponentAppearanceBillboard
{
    public MolangExpression enabled = MolangParser.ZERO;
    public boolean lit;
    public ParticleMaterial material = ParticleMaterial.OPAQUE;
    public Link texture = ParticleScheme.DEFAULT_TEXTURE;

    @Override
    protected void toData(MapType data)
    {
        data.put("enabled", this.enabled.toData());
        data.putBool("lit", this.lit);
        data.putString("material", this.material.id);

        if (this.texture != null && !this.texture.equals(ParticleScheme.DEFAULT_TEXTURE))
        {
            data.putString("texture", this.texture.toString());
        }

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
        if (map.has("lit")) this.lit = map.getBool("lit");
        if (map.has("material")) this.material = ParticleMaterial.fromString(map.getString("material"));
        if (map.has("texture")) this.texture = Link.create(map.getString("texture"));

        return super.fromData(data, parser);
    }

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        if (!particle.intersected)
        {
            return;
        }

        /* When collision texture is enabled, use this component's UV/appearance.
         * When collision texture is NOT enabled but collision tinting IS enabled,
         * use the base billboard with collision tinting's lit setting. */
        if (!isCollisionTextureEnabled(emitter))
        {
            return;
        }

        /* Temporarily override lit for collision appearance */
        boolean tmpLit = emitter.lit;
        emitter.lit = this.lit;

        this.calculateUVs(particle, emitter, transition);

        emitter.lit = tmpLit;

        /* Render using the parent billboard rendering logic */
        super.render(emitter, format, particle, builder, matrix, overlay, transition);
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        /* No collision in UI preview */
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }

    @Override
    public int getSortingIndex()
    {
        return 200;
    }

    public boolean isCollisionTextureEnabled(ParticleEmitter emitter)
    {
        return MolangExpression.isOne(this.enabled);
    }
}
