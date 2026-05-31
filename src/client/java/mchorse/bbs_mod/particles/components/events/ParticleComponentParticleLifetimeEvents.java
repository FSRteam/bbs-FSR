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
import mchorse.bbs_mod.particles.events.ParticleEventDispatcher;
import mchorse.bbs_mod.particles.events.ParticleEventTimeline;
import mchorse.bbs_mod.particles.events.ParticleEventTriggerList;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Particle lifetime events component (minecraft:particle_lifetime_events)
 *
 * Stores event triggers for the particle lifecycle:
 * - creation_event: fired when particle is created
 * - expiration_event: fired when particle expires
 * - timeline: map of age (seconds) to events
 *
 * Events are stored as raw BaseType data for round-trip fidelity.
 * Timeline events are dispatched when particle age crosses the threshold.
 */
public class ParticleComponentParticleLifetimeEvents extends ParticleComponentBase implements IComponentParticleInitialize, IComponentParticleUpdate
{
    public final ParticleEventTriggerList creationEvent = new ParticleEventTriggerList();
    public final ParticleEventTriggerList expirationEvent = new ParticleEventTriggerList();
    public final ParticleEventTimeline timeline = new ParticleEventTimeline();

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

        if (creation != null) data.put("creation_event", creation);
        if (expiration != null) data.put("expiration_event", expiration);
        if (timeline != null) data.put("timeline", timeline);
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

            if (!key.equals("creation_event") && !key.equals("expiration_event") && !key.equals("timeline"))
            {
                this.extra.put(key, entry.getValue().copy());
            }
        }


        if (map.has("creation_event")) this.creationEvent.fromData(map.get("creation_event"));
        if (map.has("expiration_event")) this.expirationEvent.fromData(map.get("expiration_event"));
        if (map.has("timeline")) this.timeline.fromData(map.get("timeline"));

        return super.fromData(map, parser);
    }

    @Override
    public void apply(ParticleEmitter emitter, Particle particle)
    {
        ParticleEventDispatcher.dispatch(emitter, particle, this.creationEvent);
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        if (!particle.isDead())
        {
            double previousAge = Math.max(0, (particle.age - 1) / 20D);
            double currentAge = particle.getAge(0);

            for (ParticleEventTimeline.Entry entry : this.timeline.sortedEntries())
            {
                double time = entry.getKeyValue();

                if (ParticleEventDispatcher.crossed(previousAge, currentAge, time) && particle.eventGuards.add("particle.timeline." + entry.key))
                {
                    ParticleEventDispatcher.dispatch(emitter, particle, entry.events);
                }
            }
        }

        if (particle.isDead() && particle.eventGuards.add("particle.expiration"))
        {
            ParticleEventDispatcher.dispatch(emitter, particle, this.expirationEvent);
        }
    }

    @Override
    public boolean canBeEmpty()
    {
        return false;
    }

    @Override
    public int getSortingIndex()
    {
        return 100;
    }
}
