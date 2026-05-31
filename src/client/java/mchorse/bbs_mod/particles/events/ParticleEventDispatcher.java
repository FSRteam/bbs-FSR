package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.joml.Vector3d;

public class ParticleEventDispatcher
{
    private static final int MAX_DEPTH = 16;
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void dispatch(ParticleEmitter emitter, ParticleEventTriggerList triggers)
    {
        dispatch(emitter, null, triggers);
    }

    public static void dispatch(ParticleEmitter emitter, Particle particle, ParticleEventTriggerList triggers)
    {
        if (triggers == null || triggers.events.isEmpty())
        {
            return;
        }

        for (String event : triggers.events)
        {
            dispatch(emitter, particle, event);
        }
    }

    public static void dispatch(ParticleEmitter emitter, Particle particle, ParticleCollisionEvents events, double speed)
    {
        if (events == null || events.entries.isEmpty())
        {
            return;
        }

        for (int i = 0; i < events.entries.size(); i++)
        {
            ParticleCollisionEvents.Entry entry = events.entries.get(i);

            if (entry.event == null || entry.event.isEmpty() || speed < entry.minSpeed)
            {
                continue;
            }

            String guard = "particle.collision." + i + "." + entry.event + "." + entry.minSpeed;

            if (particle == null || particle.eventGuards.add(guard))
            {
                dispatch(emitter, particle, entry.event);
            }
        }
    }

    public static void dispatch(ParticleEmitter emitter, Particle particle, String event)
    {
        if (emitter == null || emitter.scheme == null || event == null || event.isEmpty())
        {
            return;
        }

        int callDepth = CALL_DEPTH.get();

        if (callDepth >= MAX_DEPTH)
        {
            return;
        }

        ParticleEventNode node = emitter.scheme.events.get(event);

        if (node == null)
        {
            return;
        }

        CALL_DEPTH.set(callDepth + 1);

        try
        {
            execute(emitter, particle, node, 0);
        }
        finally
        {
            CALL_DEPTH.set(callDepth);
        }
    }

    private static void execute(ParticleEmitter emitter, Particle particle, ParticleEventNode node, int depth)
    {
        if (node == null || depth >= MAX_DEPTH)
        {
            return;
        }

        if (node.expression != null)
        {
            setVariables(emitter, particle);
            node.expression.get();
        }

        if (node.log != null && !node.log.isEmpty())
        {
            System.out.println("[BBS particle event] " + node.log);
        }

        if (node.soundEvent != null && !node.soundEvent.isEmpty())
        {
            playSound(emitter, particle, node.soundEvent);
        }

        if (node.particleEffect != null)
        {
            spawnParticleEffect(emitter, particle, node.particleEffect);
        }

        for (ParticleEventNode child : node.sequence)
        {
            execute(emitter, particle, child, depth + 1);
        }

        if (!node.randomize.isEmpty())
        {
            ParticleEventNode child = pickRandom(node);

            execute(emitter, particle, child, depth + 1);
        }
    }

    private static ParticleEventNode pickRandom(ParticleEventNode node)
    {
        float total = 0F;

        for (ParticleEventNode child : node.randomize)
        {
            total += Math.max(0F, child.weight);
        }

        if (total <= 0F)
        {
            return node.randomize.get((int) (Math.random() * node.randomize.size()));
        }

        float value = (float) (Math.random() * total);

        for (ParticleEventNode child : node.randomize)
        {
            value -= Math.max(0F, child.weight);

            if (value <= 0F)
            {
                return child;
            }
        }

        return node.randomize.get(node.randomize.size() - 1);
    }

    private static void setVariables(ParticleEmitter emitter, Particle particle)
    {
        emitter.setEmitterVariables(0);

        if (particle != null)
        {
            emitter.setParticleVariables(particle, 0);
        }
    }

    private static void spawnParticleEffect(ParticleEmitter emitter, Particle particle, ParticleEventEffect effect)
    {
        if (effect.effect == null || effect.effect.isEmpty())
        {
            return;
        }

        if (effect.preEffectExpression != null)
        {
            setVariables(emitter, particle);
            effect.preEffectExpression.get();
        }

        ParticleScheme scheme = BBSModClient.getParticles().load(effect.effect);

        if (scheme == null)
        {
            return;
        }

        ParticleEmitter child = new ParticleEmitter();
        String type = effect.type == null || effect.type.isEmpty() ? "emitter" : effect.type;
        boolean particleOnly = type.equals("particle") || type.equals("particle_with_velocity");
        boolean boundToEmitter = type.equals("emitter_bound");
        Vector3d position = resolvePosition(emitter, particle);

        child.setTarget(emitter.target);
        child.setWorld(emitter.world);
        child.lastGlobal.set(position);
        child.rotation.set(emitter.rotation);
        child.running = emitter.running;
        child.eventParticleOnly = particleOnly;
        child.setScheme(scheme);

        if (particleOnly)
        {
            child.spawnParticle(0F);

            if (type.equals("particle_with_velocity") && particle != null && !child.particles.isEmpty())
            {
                Particle spawned = child.particles.get(child.particles.size() - 1);

                spawned.speed.add(particle.speed);
            }
        }

        emitter.addChildEmitter(child, boundToEmitter, particleOnly && particle != null ? particle : null);
    }

    private static Vector3d resolvePosition(ParticleEmitter emitter, Particle particle)
    {
        if (particle != null)
        {
            return new Vector3d(particle.getGlobalPosition(emitter));
        }

        return new Vector3d(emitter.lastGlobal);
    }

    private static void playSound(ParticleEmitter emitter, Particle particle, String event)
    {
        ResourceLocation location = parseSound(event);

        if (location == null)
        {
            return;
        }

        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(location);

        if (sound == null)
        {
            return;
        }

        Vector3d position = resolvePosition(emitter, particle);

        if (emitter.world != null)
        {
            emitter.world.playLocalSound(position.x, position.y, position.z, sound, SoundSource.PLAYERS, 1F, 1F, false);
        }
        else if (Minecraft.getInstance() != null)
        {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1F));
        }
    }

    private static ResourceLocation parseSound(String event)
    {
        try
        {
            String id = event.indexOf(':') >= 0 ? event : "minecraft:" + event;

            return ResourceLocation.parse(id);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static boolean crossed(double previous, double current, double threshold)
    {
        return threshold >= 0 && previous < threshold && (current > threshold || Operation.equals(current, threshold));
    }
}
