package mchorse.bbs_mod.particles.components.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentParticleInitialize;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

/**
 * Particle lifetime events component (minecraft:particle_lifetime_events)
 *
 * Stores event triggers for the particle lifecycle:
 * - creation_event: fired when particle is created
 * - expiration_event: fired when particle expires
 * - timeline: map of age (seconds) to events
 *
 * For now, stores raw MapType data for round-trip fidelity.
 * Runtime event dispatch will be added in Phase 2.
 */
public class ParticleComponentParticleLifetimeEvents extends ParticleComponentBase implements IComponentParticleInitialize, IComponentParticleUpdate
{
    public BaseType creationEvent = null;
    public BaseType expirationEvent = null;
    public BaseType timeline = null;

    @Override
    protected void toData(MapType data)
    {
        if (this.creationEvent != null) data.put("creation_event", this.creationEvent);
        if (this.expirationEvent != null) data.put("expiration_event", this.expirationEvent);
        if (this.timeline != null) data.put("timeline", this.timeline);
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        if (map.has("creation_event")) this.creationEvent = map.get("creation_event");
        if (map.has("expiration_event")) this.expirationEvent = map.get("expiration_event");
        if (map.has("timeline")) this.timeline = map.get("timeline");

        return super.fromData(map, parser);
    }

    @Override
    public void apply(ParticleEmitter emitter, Particle particle)
    {
        /* TODO: Phase 2 - dispatch creation_event */
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        /* TODO: Phase 2 - dispatch timeline, expiration events */
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
