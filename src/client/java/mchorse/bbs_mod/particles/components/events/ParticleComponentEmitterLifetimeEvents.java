package mchorse.bbs_mod.particles.components.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.components.IComponentEmitterUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

/**
 * Emitter lifetime events component (minecraft:emitter_lifetime_events)
 *
 * Stores event triggers for the emitter lifecycle:
 * - creation_event: fired when emitter is created
 * - expiration_event: fired when emitter expires
 * - timeline: map of time (seconds) to events
 * - travel_distance_events: map of distance to events
 * - looping_travel_distance_events: list of distance-event pairs
 *
 * For now, stores raw MapType data for round-trip fidelity.
 * Runtime event dispatch will be added in Phase 2.
 */
public class ParticleComponentEmitterLifetimeEvents extends ParticleComponentBase implements IComponentEmitterInitialize, IComponentEmitterUpdate
{
    public BaseType creationEvent = null;
    public BaseType expirationEvent = null;
    public BaseType timeline = null;
    public BaseType travelDistanceEvents = null;
    public BaseType loopingTravelDistanceEvents = null;

    @Override
    protected void toData(MapType data)
    {
        if (this.creationEvent != null) data.put("creation_event", this.creationEvent);
        if (this.expirationEvent != null) data.put("expiration_event", this.expirationEvent);
        if (this.timeline != null) data.put("timeline", this.timeline);
        if (this.travelDistanceEvents != null) data.put("travel_distance_events", this.travelDistanceEvents);
        if (this.loopingTravelDistanceEvents != null) data.put("looping_travel_distance_events", this.loopingTravelDistanceEvents);
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
        if (map.has("travel_distance_events")) this.travelDistanceEvents = map.get("travel_distance_events");
        if (map.has("looping_travel_distance_events")) this.loopingTravelDistanceEvents = map.get("looping_travel_distance_events");

        return super.fromData(map, parser);
    }

    @Override
    public void apply(ParticleEmitter emitter)
    {
        /* TODO: Phase 2 - dispatch creation_event */
    }

    @Override
    public void update(ParticleEmitter emitter)
    {
        /* TODO: Phase 2 - dispatch timeline, travel distance, expiration events */
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
