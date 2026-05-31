package mchorse.bbs_mod.particles.components.motion;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.particles.events.ParticleCollisionEvents;
import mchorse.bbs_mod.particles.events.ParticleEventDispatcher;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Collections;

public class ParticleComponentMotionCollision extends ParticleComponentBase implements IComponentParticleUpdate
{
    public MolangExpression enabled = MolangParser.ONE;
    public float collisionDrag = 0;
    public float bounciness = 1;
    public float randomBounciness = 0;
    public float radius = 0.01F;
    public boolean expireOnImpact;
    public MolangExpression expirationDelay = MolangParser.ZERO;

    /* Extended collision options (Blockbuster extension) */
    public boolean realisticCollision;
    public boolean realisticCollisionDrag;
    public boolean entityCollision;
    public boolean momentum;
    public boolean preserveEnergy;
    public float damp;
    public float randomDamp;
    public float rotationCollisionDrag;
    public int splitParticleCount;
    public float splitParticleSpeedThreshold;

    /* Collision events */
    public final ParticleCollisionEvents collisionEvents = new ParticleCollisionEvents();

    /* Runtime options */
    private Vector3d previous = new Vector3d();
    private Vector3d current = new Vector3d();

    @Override
    public BaseType toData()
    {
        MapType object = new MapType();
        BaseType collisionEvents = this.collisionEvents.toData();

        if (MolangExpression.isZero(this.enabled) && collisionEvents == null)
        {
            return object;
        }

        if (!MolangExpression.isOne(this.enabled)) object.put("enabled", this.enabled.toData());
        if (this.realisticCollision) object.putBool("realisticCollision", true);
        if (this.entityCollision) object.putBool("entityCollision", true);
        if (this.momentum) object.putBool("momentum", true);
        if (this.realisticCollisionDrag) object.putBool("realistic_collision_drag", true);
        if (this.collisionDrag != 0) object.putFloat("collision_drag", this.collisionDrag);
        if (this.bounciness != 1) object.putFloat("coefficient_of_restitution", this.bounciness);
        if (this.randomBounciness != 0) object.putFloat("bounciness_randomness", this.randomBounciness);
        if (this.rotationCollisionDrag != 0) object.putFloat("collision_rotation_drag", this.rotationCollisionDrag);
        if (this.preserveEnergy) object.putBool("preserveEnergy", true);
        if (this.damp != 0) object.putFloat("damp", this.damp);
        if (this.randomDamp != 0) object.putFloat("random_damp", this.randomDamp);
        if (this.splitParticleCount != 0) object.putInt("split_particle_count", this.splitParticleCount);
        if (this.splitParticleSpeedThreshold != 0) object.putFloat("split_particle_speedThreshold", this.splitParticleSpeedThreshold);
        if (this.radius != 0.01F) object.putFloat("collision_radius", this.radius);
        if (this.expireOnImpact) object.putBool("expire_on_contact", true);
        if (!MolangExpression.isZero(this.expirationDelay)) object.put("expirationDelay", this.expirationDelay.toData());
        if (collisionEvents != null) object.put("events", collisionEvents);

        return object;
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
        if (map.has("realisticCollision")) this.realisticCollision = map.getBool("realisticCollision");
        if (map.has("entityCollision")) this.entityCollision = map.getBool("entityCollision");
        if (map.has("momentum")) this.momentum = map.getBool("momentum");
        if (map.has("realistic_collision_drag")) this.realisticCollisionDrag = map.getBool("realistic_collision_drag");
        if (map.has("collision_drag")) this.collisionDrag = map.getFloat("collision_drag");
        if (map.has("coefficient_of_restitution")) this.bounciness = map.getFloat("coefficient_of_restitution");
        if (map.has("bounciness_randomness")) this.randomBounciness = map.getFloat("bounciness_randomness");
        if (map.has("collision_rotation_drag")) this.rotationCollisionDrag = map.getFloat("collision_rotation_drag");
        if (map.has("preserveEnergy")) this.preserveEnergy = map.getBool("preserveEnergy");
        if (map.has("damp")) this.damp = map.getFloat("damp");
        if (map.has("random_damp")) this.randomDamp = map.getFloat("random_damp");
        if (map.has("split_particle_count")) this.splitParticleCount = map.getInt("split_particle_count");
        if (map.has("split_particle_speedThreshold")) this.splitParticleSpeedThreshold = map.getFloat("split_particle_speedThreshold");
        if (map.has("collision_radius")) this.radius = map.getFloat("collision_radius");
        if (map.has("expire_on_contact")) this.expireOnImpact = map.getBool("expire_on_contact");
        if (map.has("expirationDelay")) this.expirationDelay = parser.parseDataSilently(map.get("expirationDelay"));
        if (map.has("events")) this.collisionEvents.fromData(map.get("events"));

        return super.fromData(map, parser);
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        particle.realisticCollisionDrag = this.realisticCollisionDrag;

        if (emitter.world == null)
        {
            return;
        }

        if (!particle.manual && !Operation.equals(this.enabled.get(), 0))
        {
            float r = this.radius;

            this.previous.set(particle.getGlobalPosition(emitter, particle.prevPosition));
            this.current.set(particle.getGlobalPosition(emitter));

            Vector3d prev = this.previous;
            Vector3d now = this.current;

            double x = now.x - prev.x;
            double y = now.y - prev.y;
            double z = now.z - prev.z;
            boolean veryBig = Math.abs(x) > 10 || Math.abs(y) > 10 || Math.abs(z) > 10;

            if (veryBig)
            {
                return;
            }

            AABB box = new AABB(prev.x - r, prev.y - r, prev.z - r, prev.x + r, prev.y + r, prev.z + r);
            Vec3 vec = Entity.collideBoundingBox(null, new Vec3(x, y, z), box, emitter.world, Collections.emptyList());

            boolean hadCollision = vec.x != x || vec.y != y || vec.z != z;

            if (hadCollision && !particle.intersected)
            {
                particle.firstIntersection = particle.age;
                particle.intersected = true;
            }

            if (hadCollision)
            {
                this.collision(particle, emitter, prev, particle.speed.length());

                now.set(prev.x + vec.x, prev.y + vec.y, prev.z + vec.z);

                if (vec.y != y)
                {
                    this.collisionHandler(particle, 1, now);
                }
                if (vec.x != x)
                {
                    this.collisionHandler(particle, 0, now);
                }
                if (vec.z != z)
                {
                    this.collisionHandler(particle, 2, now);
                }

                particle.position.set(now);
                this.drag(particle);
            }
            else if (this.realisticCollisionDrag)
            {
                particle.dragFactor = 0;
            }
            else
            {
                particle.rotationCollisionDrag = 0;
            }
        }
    }

    private void collision(Particle particle, ParticleEmitter emitter, Vector3d prev, double speed)
    {
        ParticleEventDispatcher.dispatch(emitter, particle, this.collisionEvents, speed);

        if (this.expireOnImpact)
        {
            double expDelay = this.expirationDelay.get();

            if (expDelay != 0 && !particle.collided)
            {
                particle.setExpirationDelay(expDelay);
            }
            else if (expDelay == 0 && !particle.collided)
            {
                particle.setDead();
                return;
            }
        }

        if (particle.relativePosition)
        {
            particle.relativePosition = false;
            particle.prevPosition.set(prev);
        }

        particle.rotationCollisionDrag = this.rotationCollisionDrag;
        particle.collided = true;
    }

    private void collisionHandler(Particle particle, int axis, Vector3d now)
    {
        float speed = getComponent(particle.speed, axis);

        if (this.realisticCollision)
        {
            if (this.bounciness != 0)
            {
                setComponent(particle.speed, axis, -speed * this.bounciness);
            }
            else
            {
                setComponent(particle.speed, axis, 0);
            }
        }
        else
        {
            float factor = getComponent(particle.accelerationFactor, axis);
            setComponent(particle.accelerationFactor, axis, factor * -this.bounciness);
        }

        /* Random bounciness */
        if (this.randomBounciness != 0 && speed != 0)
        {
            float randomness = this.randomBounciness * 0.1F;
            float random1 = (float) Math.random() * randomness;
            int perpAxis1 = (axis + 1) % 3;
            int perpAxis2 = (axis + 2) % 3;
            float random2 = (float) (randomness * 0.25F * (Math.random() * 2 - 1));
            float random3 = (float) (randomness * 0.25F * (Math.random() * 2 - 1));

            addComponent(particle.speed, perpAxis1, random2);
            addComponent(particle.speed, perpAxis2, random3);

            if (this.bounciness != 0)
            {
                float curSpeed = getComponent(particle.speed, axis);
                setComponent(particle.speed, axis, curSpeed + (curSpeed < 0 ? -random1 : random1));
            }
        }

        /* Damping */
        if (this.damp != 0)
        {
            float random = (float) (this.randomDamp * (Math.random() * 2 - 1));
            float clampedValue = MathUtils.clamp((1 - this.damp) + random, 0, 1);
            particle.speed.mul(clampedValue);
        }

        particle.bounces++;
    }

    private void drag(Particle particle)
    {
        if (!((this.randomBounciness != 0 || this.realisticCollision) &&
            Math.round(particle.speed.x * 10000) == 0 &&
            Math.round(particle.speed.y * 10000) == 0 &&
            Math.round(particle.speed.z * 10000) == 0))
        {
            particle.dragFactor = this.collisionDrag;
        }
    }

    private static float getComponent(org.joml.Vector3f v, int axis)
    {
        return switch (axis) { case 0 -> v.x; case 1 -> v.y; default -> v.z; };
    }

    private static void setComponent(org.joml.Vector3f v, int axis, float value)
    {
        switch (axis) { case 0 -> v.x = value; case 1 -> v.y = value; default -> v.z = value; }
    }

    private static void addComponent(org.joml.Vector3f v, int axis, float value)
    {
        switch (axis) { case 0 -> v.x += value; case 1 -> v.y += value; default -> v.z += value; }
    }

    @Override
    public int getSortingIndex()
    {
        return 50;
    }
}
