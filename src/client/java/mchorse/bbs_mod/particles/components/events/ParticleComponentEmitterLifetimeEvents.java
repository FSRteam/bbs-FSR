package mchorse.bbs_mod.particles.components.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.components.IComponentEmitterUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.particles.events.ParticleEventDispatcher;
import mchorse.bbs_mod.particles.events.ParticleEventTimeline;
import mchorse.bbs_mod.particles.events.ParticleEventTriggerList;
import mchorse.bbs_mod.particles.events.ParticleLoopingDistanceEvents;
import java.util.LinkedHashMap;
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
    public final ParticleEventTriggerList creationEvent = new ParticleEventTriggerList();
    public final ParticleEventTriggerList expirationEvent = new ParticleEventTriggerList();
    public final ParticleEventTimeline timeline = new ParticleEventTimeline();
    public final ParticleEventTimeline travelDistanceEvents = new ParticleEventTimeline();
    public final ParticleLoopingDistanceEvents loopingTravelDistanceEvents = new ParticleLoopingDistanceEvents();

    private final Map<String, BaseType> extra = new LinkedHashMap<>();

    @Override
    protected void toData(MapType data)
    {
        for (Map.Entry<String, BaseType> entry : this.extra.entrySet())
        {
            data.put(entry.getKey(), entry.getValue().copy());
        }

        BaseType creation = this.creationEvent.toData();
        BaseType expiration = this.expirationEvent.toData();
        BaseType timeline = this.timeline.toData();
        BaseType distance = this.travelDistanceEvents.toData();
        BaseType loopingDistance = this.loopingTravelDistanceEvents.toData();

        if (creation != null) data.put("creation_event", creation);
        if (expiration != null) data.put("expiration_event", expiration);
        if (timeline != null) data.put("timeline", timeline);
        if (distance != null) data.put("travel_distance_events", distance);
        if (loopingDistance != null) data.put("looping_travel_distance_events", loopingDistance);
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();
        this.extra.clear();

        for (Map.Entry<String, BaseType> entry : map)
        {
            String key = entry.getKey();

            if (!key.equals("creation_event") && !key.equals("expiration_event") && !key.equals("timeline") && !key.equals("travel_distance_events") && !key.equals("looping_travel_distance_events"))
            {
                this.extra.put(key, entry.getValue().copy());
            }
        }


        if (map.has("creation_event")) this.creationEvent.fromData(map.get("creation_event"));
        if (map.has("expiration_event")) this.expirationEvent.fromData(map.get("expiration_event"));
        if (map.has("timeline")) this.timeline.fromData(map.get("timeline"));
        if (map.has("travel_distance_events")) this.travelDistanceEvents.fromData(map.get("travel_distance_events"));
        if (map.has("looping_travel_distance_events")) this.loopingTravelDistanceEvents.fromData(map.get("looping_travel_distance_events"));

        return super.fromData(map, parser);
    }

    @Override
    public void apply(ParticleEmitter emitter)
    {
        ParticleEventDispatcher.dispatch(emitter, this.creationEvent);
    }

    @Override
    public void update(ParticleEmitter emitter)
    {
        if (!emitter.playing)
        {
            return;
        }

        double previousAge = Math.max(0, (emitter.age - 1) / 20D);
        double currentAge = emitter.getAge(0);

        for (ParticleEventTimeline.Entry entry : this.timeline.sortedEntries())
        {
            double time = entry.getKeyValue();

            if (ParticleEventDispatcher.crossed(previousAge, currentAge, time) && emitter.eventGuards.add("emitter.timeline." + entry.key))
            {
                ParticleEventDispatcher.dispatch(emitter, entry.events);
            }
        }

        double previousDistance = emitter.eventTravelDistance;

        emitter.updateEventTravelDistance();

        double currentDistance = emitter.eventTravelDistance;

        for (ParticleEventTimeline.Entry entry : this.travelDistanceEvents.sortedEntries())
        {
            double distance = entry.getKeyValue();

            if (ParticleEventDispatcher.crossed(previousDistance, currentDistance, distance) && emitter.eventGuards.add("emitter.distance." + entry.key))
            {
                ParticleEventDispatcher.dispatch(emitter, entry.events);
            }
        }

        for (ParticleLoopingDistanceEvents.Entry entry : this.loopingTravelDistanceEvents.entries)
        {
            double distance = entry.getDistance();

            if (distance > 0)
            {
                int previousLoop = (int) Math.floor(previousDistance / distance);
                int currentLoop = (int) Math.floor(currentDistance / distance);

                for (int loop = previousLoop + 1; loop <= currentLoop; loop++)
                {
                    ParticleEventDispatcher.dispatch(emitter, entry.effects);
                }
            }
        }
    }

    @Override
    public boolean canBeEmpty()
    {
        return false;
    }
}
