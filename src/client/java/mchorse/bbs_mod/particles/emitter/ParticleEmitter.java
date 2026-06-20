package mchorse.bbs_mod.particles.emitter;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.math.IExpression;
import mchorse.bbs_mod.math.Variable;
import mchorse.bbs_mod.particles.ParticleMaterial;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.components.IComponentEmitterUpdate;
import mchorse.bbs_mod.particles.components.IComponentParticleInitialize;
import mchorse.bbs_mod.particles.components.IComponentParticleRender;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionAppearance;
import mchorse.bbs_mod.particles.components.events.ParticleComponentEmitterLifetimeEvents;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateManual;
import mchorse.bbs_mod.particles.events.ParticleEventDispatcher;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class ParticleEmitter
{
    public static final int PERSPECTIVE_FIRST_PERSON = 0;
    public static final int PERSPECTIVE_THIRD_PERSON_BACK = 1;
    public static final int PERSPECTIVE_THIRD_PERSON_FRONT = 2;
    public static final int PERSPECTIVE_GUI = 100;

    public ParticleScheme scheme;
    public List<Particle> particles = new ArrayList<>();
    public Map<String, IExpression> variables;

    public Link texture;
    public LivingEntity target;
    public Level world;
    public boolean lit;

    public boolean running = true;
    private Particle uiParticle;

    /* Intermediate values */
    public Vector3d lastGlobal = new Vector3d();
    public Matrix3f rotation = new Matrix3f();

    /* Runtime properties */
    public float spawnRemainder;
    public int index;
    public int age;
    public int lifetime;
    public boolean playing = true;
    public boolean paused;
    public boolean eventParticleOnly;

    public Set<String> eventGuards = new HashSet<>();
    public double eventTravelDistance;

    public float random1 = (float) Math.random();
    public float random2 = (float) Math.random();
    public float random3 = (float) Math.random();
    public float random4 = (float) Math.random();

    /* Camera properties */
    public int perspective;
    public float cYaw;
    public float cPitch;
    public Matrix3f cameraRotation = new Matrix3f();

    public double cX;
    public double cY;
    public double cZ;

    public float user1;
    public float user2;
    public float user3;
    public float user4;
    public float user5;
    public float user6;

    /* Cached variable references to avoid hash look-ups */
    private Variable varIndex;
    private Variable varAge;
    private Variable varLifetime;
    private Variable varRandom1;
    private Variable varRandom2;
    private Variable varRandom3;
    private Variable varRandom4;
    private Variable varPositionX;
    private Variable varPositionY;
    private Variable varPositionZ;

    private Variable varEmitterAge;
    private Variable varEmitterLifetime;
    private Variable varEmitterRandom1;
    private Variable varEmitterRandom2;
    private Variable varEmitterRandom3;
    private Variable varEmitterRandom4;
    private Variable varEmitterUser1;
    private Variable varEmitterUser2;
    private Variable varEmitterUser3;
    private Variable varEmitterUser4;
    private Variable varEmitterUser5;
    private Variable varEmitterUser6;

    /* Speed/Position/Bounces cached variables */
    private Variable varSpeedABS;
    private Variable varSpeedX;
    private Variable varSpeedY;
    private Variable varSpeedZ;
    private Variable varPosX;
    private Variable varPosY;
    private Variable varPosZ;
    private Variable varPosDistance;
    private Variable varBounces;

    private final List<ParticleEmitter> childEmitters = new ArrayList<>();
    private final Vector3d eventLastGlobal = new Vector3d();
    private final Vector3f particlePosition = new Vector3f();
    private final Matrix4f minecraftCameraRotation = new Matrix4f();
    private boolean eventLastGlobalSet;
    private ParticleEmitter boundParent;
    private Particle boundParticle;
    private boolean boundToEmitter;

    public double getAge()
    {
        return this.getAge(0);
    }

    public double getAge(float transition)
    {
        return !this.paused ? (this.age + transition) / 20.0 : this.age / 20.0;
    }

    public void setTarget(LivingEntity target)
    {
        this.target = target;
        this.world = target == null ? null : target.level();
    }

    public void setWorld(Level world)
    {
        this.world = world;
    }

    public void setScheme(ParticleScheme scheme)
    {
        this.scheme = scheme;

        if (this.scheme == null)
        {
            return;
        }

        this.scheme.setup();

        this.lit = true;
        this.stop();
        this.start();

        this.setupVariables();
        this.setEmitterVariables(0);

        List<IComponentEmitterInitialize> emitterInitializes = this.scheme.emitterInitializes;

        for (int i = 0; i < emitterInitializes.size(); i++)
        {
            emitterInitializes.get(i).apply(this);
        }
    }

    public void setUserVariables(float a, float b, float c, float d, float e, float f)
    {
        this.user1 = a;
        this.user2 = b;
        this.user3 = c;
        this.user4 = d;
        this.user5 = e;
        this.user6 = f;
    }

    /* Variable related code */

    public void setupVariables()
    {
        if (this.scheme == null)
        {
            return;
        }

        this.varIndex = this.scheme.parser.variables.get("variable.particle_index");
        this.varAge = this.scheme.parser.variables.get("variable.particle_age");
        this.varLifetime = this.scheme.parser.variables.get("variable.particle_lifetime");
        this.varRandom1 = this.scheme.parser.variables.get("variable.particle_random_1");
        this.varRandom2 = this.scheme.parser.variables.get("variable.particle_random_2");
        this.varRandom3 = this.scheme.parser.variables.get("variable.particle_random_3");
        this.varRandom4 = this.scheme.parser.variables.get("variable.particle_random_4");
        this.varPositionX = this.scheme.parser.variables.get("variable.particle_x");
        this.varPositionY = this.scheme.parser.variables.get("variable.particle_y");
        this.varPositionZ = this.scheme.parser.variables.get("variable.particle_z");

        this.varEmitterAge = this.scheme.parser.variables.get("variable.emitter_age");
        this.varEmitterLifetime = this.scheme.parser.variables.get("variable.emitter_lifetime");
        this.varEmitterRandom1 = this.scheme.parser.variables.get("variable.emitter_random_1");
        this.varEmitterRandom2 = this.scheme.parser.variables.get("variable.emitter_random_2");
        this.varEmitterRandom3 = this.scheme.parser.variables.get("variable.emitter_random_3");
        this.varEmitterRandom4 = this.scheme.parser.variables.get("variable.emitter_random_4");
        this.varEmitterUser1 = this.scheme.parser.variables.get("variable.emitter_user_1");
        this.varEmitterUser2 = this.scheme.parser.variables.get("variable.emitter_user_2");
        this.varEmitterUser3 = this.scheme.parser.variables.get("variable.emitter_user_3");
        this.varEmitterUser4 = this.scheme.parser.variables.get("variable.emitter_user_4");
        this.varEmitterUser5 = this.scheme.parser.variables.get("variable.emitter_user_5");
        this.varEmitterUser6 = this.scheme.parser.variables.get("variable.emitter_user_6");

        this.varSpeedABS = this.scheme.parser.variables.get("variable.particle_speed.length");
        this.varSpeedX = this.scheme.parser.variables.get("variable.particle_speed.x");
        this.varSpeedY = this.scheme.parser.variables.get("variable.particle_speed.y");
        this.varSpeedZ = this.scheme.parser.variables.get("variable.particle_speed.z");
        this.varPosX = this.scheme.parser.variables.get("variable.particle_pos.x");
        this.varPosY = this.scheme.parser.variables.get("variable.particle_pos.y");
        this.varPosZ = this.scheme.parser.variables.get("variable.particle_pos.z");
        this.varPosDistance = this.scheme.parser.variables.get("variable.particle_pos.distance");
        this.varBounces = this.scheme.parser.variables.get("variable.particle_bounces");
    }

    public void setParticleVariables(Particle particle, float transition)
    {
        this.scheme.particle = particle;

        if (this.varIndex != null) this.varIndex.set(particle.index);
        if (this.varAge != null) this.varAge.set(particle.getAge(transition));
        if (this.varLifetime != null) this.varLifetime.set(particle.lifetime / 20.0);
        if (this.varRandom1 != null) this.varRandom1.set(particle.random1);
        if (this.varRandom2 != null) this.varRandom2.set(particle.random2);
        if (this.varRandom3 != null) this.varRandom3.set(particle.random3);
        if (this.varRandom4 != null) this.varRandom4.set(particle.random4);
        if (this.varPositionX != null) this.varPositionX.set(Lerps.lerp(particle.prevPosition.x, particle.position.x, transition));
        if (this.varPositionY != null) this.varPositionY.set(Lerps.lerp(particle.prevPosition.y, particle.position.y, transition));
        if (this.varPositionZ != null) this.varPositionZ.set(Lerps.lerp(particle.prevPosition.z, particle.position.z, transition));

        /* Speed/Position/Bounces variables */
        Vector3d relPos = particle.getGlobalPosition(this);
        relPos.sub(this.lastGlobal);

        if (this.varPosDistance != null) this.varPosDistance.set(relPos.length());
        if (this.varPosX != null) this.varPosX.set(relPos.x);
        if (this.varPosY != null) this.varPosY.set(relPos.y);
        if (this.varPosZ != null) this.varPosZ.set(relPos.z);
        if (this.varSpeedABS != null) this.varSpeedABS.set(particle.speed.length());
        if (this.varSpeedX != null) this.varSpeedX.set(particle.speed.x);
        if (this.varSpeedY != null) this.varSpeedY.set(particle.speed.y);
        if (this.varSpeedZ != null) this.varSpeedZ.set(particle.speed.z);
        if (this.varBounces != null) this.varBounces.set(particle.bounces);

        this.scheme.updateCurves();

        if (this.scheme.initialization != null)
        {
            this.scheme.initialization.particleUpdate.get();
        }
    }

    public void setEmitterVariables(float transition)
    {
        this.scheme.emitter = this;

        if (this.varEmitterAge != null) this.varEmitterAge.set(this.getAge(transition));
        if (this.varEmitterLifetime != null) this.varEmitterLifetime.set(this.lifetime / 20.0);
        if (this.varEmitterRandom1 != null) this.varEmitterRandom1.set(this.random1);
        if (this.varEmitterRandom2 != null) this.varEmitterRandom2.set(this.random2);
        if (this.varEmitterRandom3 != null) this.varEmitterRandom3.set(this.random3);
        if (this.varEmitterRandom4 != null) this.varEmitterRandom4.set(this.random4);
        if (this.varEmitterUser1 != null) this.varEmitterUser1.set(this.user1);
        if (this.varEmitterUser2 != null) this.varEmitterUser2.set(this.user2);
        if (this.varEmitterUser3 != null) this.varEmitterUser3.set(this.user3);
        if (this.varEmitterUser4 != null) this.varEmitterUser4.set(this.user4);
        if (this.varEmitterUser5 != null) this.varEmitterUser5.set(this.user5);
        if (this.varEmitterUser6 != null) this.varEmitterUser6.set(this.user6);

        this.scheme.updateCurves();
    }

    public void parseVariables(Map<String, String> variables)
    {
        this.variables = new HashMap<>();

        for (Map.Entry<String, String> entry : variables.entrySet())
        {
            this.parseVariable(entry.getKey(), entry.getValue());
        }
    }

    public void parseVariable(String name, String expression)
    {
        try
        {
            this.variables.put(name, this.scheme.parser.parse(expression));
        }
        catch (Exception e)
        {}
    }

    public void replaceVariables()
    {
        if (this.variables == null)
        {
            return;
        }

        for (Map.Entry<String, IExpression> entry : this.variables.entrySet())
        {
            Variable var = this.scheme.parser.variables.get(entry.getKey());

            if (var != null)
            {
                var.set(entry.getValue().get().doubleValue());
            }
        }
    }

    public void start()
    {
        if (this.playing)
        {
            return;
        }

        this.spawnRemainder = 0F;
        this.index = 0;
        this.age = 0;
        this.playing = true;
        this.eventGuards.clear();
        this.eventTravelDistance = 0;
        this.eventLastGlobal.set(this.lastGlobal);
        this.eventLastGlobalSet = true;
    }

    public void stop()
    {
        if (!this.playing)
        {
            return;
        }

        if (this.age > 0)
        {
            ParticleComponentEmitterLifetimeEvents events = this.scheme == null ? null : this.scheme.emitterLifetimeEvents;

            if (events != null)
            {
                ParticleEventDispatcher.dispatch(this, events.expirationEvent);
            }
        }

        this.playing = false;

        this.random1 = (float) Math.random();
        this.random2 = (float) Math.random();
        this.random3 = (float) Math.random();
        this.random4 = (float) Math.random();
    }

    public void addChildEmitter(ParticleEmitter emitter, boolean boundToEmitter, Particle boundParticle)
    {
        if (emitter == null)
        {
            return;
        }

        emitter.boundParent = this;
        emitter.boundToEmitter = boundToEmitter;
        emitter.boundParticle = boundParticle;

        this.childEmitters.add(emitter);
    }

    public void updateEventTravelDistance()
    {
        if (!this.eventLastGlobalSet)
        {
            this.eventLastGlobal.set(this.lastGlobal);
            this.eventLastGlobalSet = true;

            return;
        }

        this.eventTravelDistance += this.lastGlobal.distance(this.eventLastGlobal);
        this.eventLastGlobal.set(this.lastGlobal);
    }

    /**
     * Update this current emitter
     */
    public void update()
    {
        if (this.scheme == null)
        {
            return;
        }

        if (this.paused)
        {
            return;
        }

        this.setEmitterVariables(0);

        List<IComponentEmitterUpdate> emitterUpdates = this.scheme.emitterUpdates;

        for (int i = 0; i < emitterUpdates.size(); i++)
        {
            emitterUpdates.get(i).update(this);
        }

        this.setEmitterVariables(0);
        this.updateParticles();
        this.updateChildEmitters();

        this.age += 1;
    }

    private void updateChildEmitters()
    {
        Iterator<ParticleEmitter> it = this.childEmitters.iterator();

        while (it.hasNext())
        {
            ParticleEmitter child = it.next();

            child.updateEventBinding();
            child.update();

            if ((child.eventParticleOnly && child.particles.isEmpty() && child.age > 1) || (!child.playing && child.particles.isEmpty() && child.childEmitters.isEmpty()))
            {
                it.remove();
            }
        }
    }

    private void updateEventBinding()
    {
        if (this.boundParent == null)
        {
            return;
        }

        if (this.boundToEmitter)
        {
            this.lastGlobal.set(this.boundParent.lastGlobal);
            this.rotation.set(this.boundParent.rotation);
        }
        else if (this.boundParticle != null && !this.boundParticle.isDead())
        {
            this.lastGlobal.set(this.boundParticle.getGlobalPosition(this.boundParent));
            this.rotation.set(this.boundParent.rotation);
        }
    }

    /**
     * Update all particles
     */
    private void updateParticles()
    {
        Iterator<Particle> it = this.particles.iterator();

        while (it.hasNext())
        {
            Particle particle = it.next();

            this.updateParticle(particle);

            if (particle.isDead())
            {
                it.remove();
            }
        }
    }

    /**
     * Update a single particle
     */
    private void updateParticle(Particle particle)
    {
        particle.update(this);

        this.setParticleVariables(particle, 0);

        List<IComponentParticleUpdate> particleUpdates = this.scheme.particleUpdates;

        for (int i = 0; i < particleUpdates.size(); i++)
        {
            particleUpdates.get(i).update(this, particle);
        }
    }

    public Particle getParticleByIndex(int index)
    {
        for (int i = 0; i < this.particles.size(); i++)
        {
            Particle particle = this.particles.get(i);

            if (particle.index == index)
            {
                return particle;
            }
        }

        return null;
    }

    /**
     * Spawn a particle
     */
    public void spawnParticle(float offset)
    {
        if (!this.running)
        {
            return;
        }

        ParticleComponentRateManual manualRate = this.scheme == null ? null : this.scheme.get(ParticleComponentRateManual.class);

        if (manualRate != null && this.particles.size() >= (int) manualRate.particles.get())
        {
            return;
        }

        this.particles.add(this.createParticle(offset));
    }

    /**
     * Create a new particle
     */
    private Particle createParticle(float offset)
    {
        Particle particle = new Particle(this.index, offset);

        this.index += 1;

        this.setParticleVariables(particle, offset);
        particle.setupMatrix(this);

        List<IComponentParticleInitialize> particleInitializes = this.scheme.particleInitializes;

        for (int i = 0; i < particleInitializes.size(); i++)
        {
            particleInitializes.get(i).apply(this, particle);
        }

        if (!particle.relativeRotation)
        {
            Vector3f vec = this.particlePosition.set(particle.position);

            particle.matrix.transform(vec);
            particle.position.x = vec.x;
            particle.position.y = vec.y;
            particle.position.z = vec.z;
        }

        if (!(particle.relativePosition && particle.relativeRotation))
        {
            particle.position.add(this.lastGlobal);
            particle.initialPosition.add(this.lastGlobal);
        }

        particle.prevPosition.set(particle.position);
        particle.rotation = particle.initialRotation;
        particle.prevRotation = particle.rotation;

        return particle;
    }

    /**
     * Render the particle on screen
     */
    public void renderUI(PoseStack stack, float transition)
    {
        if (this.scheme == null)
        {
            return;
        }

        List<IComponentParticleRender> list = this.scheme.particleRender;

        if (!list.isEmpty())
        {
            this.bindTexture();

            if (this.uiParticle == null || this.uiParticle.isDead())
            {
                this.uiParticle = this.createParticle(0F);
            }

            this.rotation.identity();
            this.uiParticle.update(this);
            this.setEmitterVariables(transition);
            this.setParticleVariables(this.uiParticle, transition);

            Matrix4f matrix = stack.last().pose();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);

            for (int i = 0; i < list.size(); i++)
            {
                list.get(i).renderUI(this.uiParticle, builder, matrix, transition);
            }

            MeshData meshData = builder.build();

            if (meshData != null)
            {
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                RenderSystem.disableCull();
                BufferUploader.drawWithShader(meshData);
                RenderSystem.enableCull();
            }
        }
    }

    /**
     * Render all the particles in this particle emitter
     */
    public void render(VertexFormat format, Supplier<ShaderInstance> program, PoseStack stack, int overlay, float transition)
    {
        if (this.scheme == null)
        {
            return;
        }

        List<IComponentParticleRender> renders = this.scheme.particleRender;

        for (int i = 0; i < renders.size(); i++)
        {
            renders.get(i).preRender(this, transition);
        }

        if (!this.particles.isEmpty())
        {
            Matrix4f matrix = stack.last().pose();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, format);
            ParticleComponentCollisionAppearance collisionAppearance = this.scheme.collisionAppearance;
            boolean useCollisionTexture = collisionAppearance != null;

            this.bindTexture();

            for (int i = 0; i < this.particles.size(); i++)
            {
                Particle particle = this.particles.get(i);

                this.setEmitterVariables(transition);
                this.setParticleVariables(particle, transition);

                /* Check if particle should use collision appearance instead of base billboard */
                boolean hasCollisionTexture = useCollisionTexture && particle.intersected && collisionAppearance.isCollisionTextureEnabled();

                for (int j = 0; j < renders.size(); j++)
                {
                    IComponentParticleRender component = renders.get(j);

                    /* Skip base billboard when collision appearance handles rendering */
                    if (hasCollisionTexture && component.getClass() == ParticleComponentAppearanceBillboard.class)
                    {
                        continue;
                    }

                    if (hasCollisionTexture && component == collisionAppearance)
                    {
                        continue;
                    }

                    component.render(this, format, particle, builder, matrix, overlay, transition);
                }
            }

            this.drawParticleBatch(builder.build(), program, this.scheme.material);

            if (useCollisionTexture)
            {
                this.renderCollisionAppearanceBatch(collisionAppearance, format, program, matrix, overlay, transition);
            }
        }

        for (int i = 0; i < renders.size(); i++)
        {
            renders.get(i).postRender(this, transition);
        }

        for (int i = 0; i < this.childEmitters.size(); i++)
        {
            ParticleEmitter child = this.childEmitters.get(i);

            this.copyCameraProperties(child);
            child.render(format, program, stack, overlay, transition);
        }
    }

    private void renderCollisionAppearanceBatch(ParticleComponentCollisionAppearance collisionAppearance, VertexFormat format, Supplier<ShaderInstance> program, Matrix4f matrix, int overlay, float transition)
    {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, format);

        this.bindTexture(collisionAppearance.texture);

        for (int i = 0; i < this.particles.size(); i++)
        {
            Particle particle = this.particles.get(i);

            if (!particle.intersected)
            {
                continue;
            }

            this.setEmitterVariables(transition);
            this.setParticleVariables(particle, transition);
            collisionAppearance.render(this, format, particle, builder, matrix, overlay, transition);
        }

        this.drawParticleBatch(builder.build(), program, collisionAppearance.material);
    }

    private void drawParticleBatch(MeshData meshData, Supplier<ShaderInstance> program, ParticleMaterial material)
    {
        if (meshData == null)
        {
            return;
        }

        RenderSystem.setShader(program);

        if (material == ParticleMaterial.ADD)
        {
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
        }
        else
        {
            RenderSystem.disableBlend();
        }

        RenderSystem.disableCull();
        BufferUploader.drawWithShader(meshData);
        RenderSystem.enableCull();

        if (material == ParticleMaterial.ADD)
        {
            RenderSystem.defaultBlendFunc();
        }

        RenderSystem.enableBlend();
    }

    private void copyCameraProperties(ParticleEmitter emitter)
    {
        emitter.perspective = this.perspective;
        emitter.cYaw = this.cYaw;
        emitter.cPitch = this.cPitch;
        emitter.cameraRotation.set(this.cameraRotation);
        emitter.cX = this.cX;
        emitter.cY = this.cY;
        emitter.cZ = this.cZ;
    }

    private void bindTexture()
    {
        this.bindTexture(this.texture == null ? this.scheme.texture : this.texture);
    }

    private void bindTexture(Link link)
    {
        Texture texture = BBSModClient.getTextures().getTexture(link == null ? ParticleScheme.DEFAULT_TEXTURE : link);

        BBSModClient.getTextures().bindTexture(texture);
    }

    public void setupCameraProperties(Camera camera)
    {
        this.perspective = PERSPECTIVE_FIRST_PERSON;
        this.cYaw = 180F - MathUtils.toDeg(camera.rotation.y);
        this.cPitch = MathUtils.toDeg(camera.rotation.x);
        this.cameraRotation.set(camera.view).invert();
        this.cX = camera.position.x;
        this.cY = camera.position.y;
        this.cZ = camera.position.z;
    }

    public void setupGuiCameraProperties(Camera camera)
    {
        this.perspective = PERSPECTIVE_GUI;
        this.cYaw = MathUtils.toDeg(camera.rotation.y);
        this.cPitch = MathUtils.toDeg(camera.rotation.x);
        this.cameraRotation.set(camera.view).invert();
        this.cX = camera.position.x;
        this.cY = camera.position.y;
        this.cZ = camera.position.z;
    }

    public void setupCameraProperties(net.minecraft.client.Camera camera)
    {
        CameraType cameraType = Minecraft.getInstance().options.getCameraType();

        this.perspective = cameraType == CameraType.THIRD_PERSON_FRONT
            ? PERSPECTIVE_THIRD_PERSON_FRONT
            : cameraType == CameraType.THIRD_PERSON_BACK ? PERSPECTIVE_THIRD_PERSON_BACK : PERSPECTIVE_FIRST_PERSON;
        this.cYaw = 180F - camera.getYRot();
        this.cPitch = -camera.getXRot();
        this.cameraRotation.set(this.minecraftCameraRotation.identity().rotate(camera.rotation()));
        this.cX = camera.getPosition().x;
        this.cY = camera.getPosition().y;
        this.cZ = camera.getPosition().z;
    }
}
