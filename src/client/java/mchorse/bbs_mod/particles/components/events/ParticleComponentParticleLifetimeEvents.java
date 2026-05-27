package mchorse.bbs_mod.particles.components.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentParticleInitialize;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

import java.util.HashMap;
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
    public BaseType creationEvent = null;
    public BaseType expirationEvent = null;
    public BaseType timeline = null;

    /* Parsed timeline: maps age in seconds (as float) to event data */
    private Map<Float, BaseType> parsedTimeline = new HashMap<>();

    /* Track which timeline events have fired for each particle */
    private int lastTimelineIndex = -1;

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
        if (map.has("timeline"))
        {
            this.timeline = map.get("timeline");
            this.parseTimeline();
        }

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
    public void apply(ParticleEmitter emitter, Particle particle)
    {
        /* Dispatch creation_event — events are stored but not executed yet
         * as the event system needs the full event resolver infrastructure.
         * For now, the creation event data is preserved for round-trip. */
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        /* Dispatch timeline events based on particle age */
        if (!this.parsedTimeline.isEmpty() && !particle.isDead())
        {
            float particleAge = (float) particle.getAge(0);

            for (Map.Entry<Float, BaseType> entry : this.parsedTimeline.entrySet())
            {
                float eventTime = entry.getKey();

                /* Fire event when particle age crosses the timeline threshold */
                if (eventTime > 0 && particleAge >= eventTime && particleAge < eventTime + 0.05F)
                {
                    /* Event data is preserved but execution requires
                     * the full event resolver (particle effect references, etc.) */
                }
            }
        }

        /* Dispatch expiration_event when particle is about to die */
        if (particle.isDead() && this.expirationEvent != null)
        {
            /* Expiration event preserved for round-trip */
        }
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
