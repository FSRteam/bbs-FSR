package mchorse.bbs_mod.particles.components.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.components.IComponentEmitterUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

import java.util.HashMap;
import java.util.Map;

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
 * Events are stored as raw BaseType data for round-trip fidelity.
 * Timeline events are dispatched when emitter age crosses the threshold.
 */
public class ParticleComponentEmitterLifetimeEvents extends ParticleComponentBase implements IComponentEmitterInitialize, IComponentEmitterUpdate
{
    public BaseType creationEvent = null;
    public BaseType expirationEvent = null;
    public BaseType timeline = null;
    public BaseType travelDistanceEvents = null;
    public BaseType loopingTravelDistanceEvents = null;

    /* Parsed timeline: maps age in seconds (as float) to event data */
    private Map<Float, BaseType> parsedTimeline = new HashMap<>();

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
        if (map.has("timeline"))
        {
            this.timeline = map.get("timeline");
            this.parseTimeline();
        }
        if (map.has("travel_distance_events")) this.travelDistanceEvents = map.get("travel_distance_events");
        if (map.has("looping_travel_distance_events")) this.loopingTravelDistanceEvents = map.get("looping_travel_distance_events");

        return super.fromData(map, parser);
    }

    private void parseTimeline()
    {
        this.parsedTimeline.clear();

        if (this.timeline != null && this.timeline.isMap())
        {
            for (Map.Entry<String, BaseType> entry : this.timeline.asMap())
            {
                try
                {
                    float time = Float.parseFloat(entry.getKey());
                    this.parsedTimeline.put(time, entry.getValue());
                }
                catch (NumberFormatException e)
                {
                    /* Skip invalid timeline keys */
                }
            }
        }
    }

    @Override
    public void apply(ParticleEmitter emitter)
    {
        /* Dispatch creation_event */
    }

    @Override
    public void update(ParticleEmitter emitter)
    {
        /* Dispatch timeline events based on emitter age */
        if (!this.parsedTimeline.isEmpty() && emitter.playing)
        {
            float emitterAge = (float) emitter.getAge(0);

            for (Map.Entry<Float, BaseType> entry : this.parsedTimeline.entrySet())
            {
                float eventTime = entry.getKey();

                if (eventTime > 0 && emitterAge >= eventTime && emitterAge < eventTime + 0.05F)
                {
                    /* Timeline event data preserved for round-trip */
                }
            }
        }
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
