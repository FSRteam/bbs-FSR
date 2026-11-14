package mchorse.bbs_mod.film;

import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import mchorse.bbs_mod.api.client.events.FilmEvents;
import mchorse.bbs_mod.client.compat.BBSWorldRenderContext;
import mchorse.bbs_mod.film.replays.tracks.TrackBehaviours;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.renderer.ItemUseEffects;
import mchorse.bbs_mod.client.renderer.LivePlayerItemUse;
import mchorse.bbs_mod.client.renderer.ThirdPersonItemUse;
import mchorse.bbs_mod.cubic.animation.ItemUsePose;
import mchorse.bbs_mod.film.replays.ReplayItemUse;
import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.cubic.ik.IKControls;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.PhysicsControls;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.replays.FormControlKeys;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.mixin.EntityInvoker;
import mchorse.bbs_mod.mixin.LivingEntityRollAccessor;
import mchorse.bbs_mod.forms.entities.EntityState;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.mixin.client.ClientPlayerEntityAccessor;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseFilmController
{
    public final Film film;

    /** Keyed by each replay's stable id (never by index), in replay-list order. */
    protected Map<String, IEntity> entities = new LinkedHashMap<>();

    public boolean paused;
    public int exception = -1;

    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f TEMP_VECTOR = new Vector3f();

    /* Film controller */

    public BaseFilmController(Film film)
    {
        this.film = film;
    }

    public Map<String, IEntity> getEntities()
    {
        return this.entities;
    }

    public void togglePause()
    {
        this.paused = !this.paused;
    }

    public void createEntities()
    {
        this.releaseFormPlayback();
        this.entities.clear();

        if (this.film == null)
        {
            return;
        }

        for (Replay replay : this.film.replays.getList())
        {
            if (replay.enabled.get())
            {
                Level world = Minecraft.getInstance().level;
                IEntity entity = new StubEntity(world);
                int ticks = replay.getTick(this.getTick());

                if (entity instanceof StubEntity stub)
                {
                    stub.setEntityOverride(this.resolvePreviewLivingEntity());
                }

                entity.setForm(FormUtils.copy(replay.form.get()));
                replay.keyframes.apply(ticks, entity);
                entity.setPrevX(entity.getX());
                entity.setPrevY(entity.getY());
                entity.setPrevZ(entity.getZ());

                entity.setPrevYaw(entity.getYaw());
                entity.setPrevHeadYaw(entity.getHeadYaw());
                entity.setPrevPitch(entity.getPitch());
                entity.setPrevBodyYaw(entity.getBodyYaw());

                /* Keyed by the replay's stable id. Disabled replays simply have no entry, so every
                 * walk over this map must go through the replay list and tolerate a miss. */
                this.entities.put(replay.getId(), entity);
            }
        }

        FilmEvents.CREATED.invoker().onFilmCreated(this);
    }

    /**
     * Returns the backing {@link LivingEntity} used by preview entities so modded armor can
     * resolve its custom model through {@code IClientItemExtensions}. Defaults to
     * {@code null} (world playback keeps the vanilla model path); the Film editor overrides
     * this to return the local player.
     */
    protected LivingEntity resolvePreviewLivingEntity()
    {
        return null;
    }

    public abstract Map<String, Integer> getActors();

    public abstract int getTick();

    public boolean hasFinished()
    {
        return false;
    }

    public void update()
    {
        this.updateEntities(this.getTick());
    }

    protected void updateEntities(int ticks)
    {
        FilmEvents.TICK_BEFORE.invoker().onFilmTick(this, ticks);
        this.updateScene(ticks);
        FilmEvents.TICK_AFTER.invoker().onFilmTick(this, ticks);
    }

    protected void updateScene(int ticks)
    {
        Level level = Minecraft.getInstance().level;

        if (level == null)
        {
            return;
        }

        /* See startRenderFrame: an unloaded film is a normal state here, and the walk no longer
         * skips it for free by starting from an empty entity map. */
        if (this.film == null)
        {
            return;
        }

        List<Replay> replays = this.film.replays.getList();

        for (int i = 0; i < replays.size(); i++)
        {
            Replay replay = replays.get(i);
            IEntity entity = this.entities.get(replay.getId());

            /* This used to walk the entity map, so a disabled replay was skipped for free. The walk
             * now goes over the list (the map's ids are not positions), and a disabled replay has no
             * entity — the miss is the skip. */
            if (entity == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.UPDATE))
            {
                continue;
            }

            /* Every replay maps the controller tick independently. Reusing the
             * previous replay's mapped value makes looping depend on list order. */
            int replayTick = replay.getTick(ticks);

            this.updateEntityAndForm(entity, replayTick);
            this.applyReplay(replay, replayTick, entity);

            Map<String, Integer> actors = this.getActors();

            if (actors != null)
            {
                Integer entityId = actors.get(replay.getId());

                if (entityId != null)
                {
                    Entity anEntity = level.getEntity(entityId);

                    if (anEntity instanceof ActorEntity actor)
                    {
                        this.applyActorReplay(replay, replayTick, actor, entity);
                    }
                    else if (anEntity instanceof Player player)
                    {
                        double x = replay.keyframes.x.interpolate(replayTick);
                        double y = replay.keyframes.y.interpolate(replayTick);
                        double z = replay.keyframes.z.interpolate(replayTick);
                        double prevX = replay.keyframes.x.interpolate(replayTick - 1);
                        double prevY = replay.keyframes.y.interpolate(replayTick - 1);
                        double prevZ = replay.keyframes.z.interpolate(replayTick - 1);

                        player.setDeltaMovement(x - prevX, y - prevY, z - prevZ);
                    }
                }
            }
        }
    }

    public void updateEndWorld()
    {
        int ticks = this.getTick();
        Level level = Minecraft.getInstance().level;

        if (level == null)
        {
            return;
        }

        /* See startRenderFrame. */
        if (this.film == null)
        {
            return;
        }

        List<Replay> replays = this.film.replays.getList();

        for (int i = 0; i < replays.size(); i++)
        {
            Replay replay = replays.get(i);
            IEntity entity = this.entities.get(replay.getId());

            if (entity == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.UPDATE))
            {
                continue;
            }

            int replayTick = replay.getTick(ticks);

            Map<String, Integer> actors = this.getActors();

            if (actors != null)
            {
                Integer entityId = actors.get(replay.getId());

                if (entityId != null)
                {
                    Entity anEntity = level.getEntity(entityId);

                    if (anEntity instanceof Player player)
                    {
                        double x = replay.keyframes.x.interpolate(replayTick);
                        double y = replay.keyframes.y.interpolate(replayTick);
                        double z = replay.keyframes.z.interpolate(replayTick);
                        boolean sneaking = EntityState.isOn(replay.keyframes.sneaking.interpolate(replayTick));
                        boolean grounded = EntityState.isOn(replay.keyframes.grounded.interpolate(replayTick));
                        boolean swimming = EntityState.isOn(replay.keyframes.swimming.interpolate(replayTick));
                        boolean gliding = EntityState.isOn(replay.keyframes.gliding.interpolate(replayTick));

                        Vec3 pos = player.position();

                        double dY = y - pos.y - (grounded ? ReplayKeyframes.GRAVITY_PROBE : 0D);

                        player.move(MoverType.SELF, new Vec3(x - pos.x, dY, z - pos.z));
                        player.setPos(x, y, z);

                        player.setShiftKeyDown(sneaking);
                        player.setSwimming(swimming);
                        ((EntityInvoker) player).bbs$setFlag(EntityState.FALL_FLYING_FLAG, gliding);
                        player.setPose(EntityState.pose(gliding, swimming, sneaking));
                        ((LivingEntityRollAccessor) player).bbs$setRoll(replay.keyframes.roll.interpolate(replayTick).intValue());
                        player.setOnGround(grounded);
                        player.setSprinting(EntityState.isOn(replay.keyframes.sprinting.interpolate(replayTick)));

                        /* World first-person playback is driven through the real
                         * client player in this end-of-world phase. Publish the
                         * replay's held-item use here as well; the StubEntity
                         * update path has no backing LivingEntity in world mode. */
                        ItemUsePose.Use use = ReplayItemUse.compute(replay, replayTick, true, player);
                        ItemUsePose.Use offUse = ReplayItemUse.compute(replay, replayTick, false, player);
                        LivePlayerItemUse.apply(player, use, offUse);

                        /* The use clips are answered back to vanilla only during the
                         * render pass (LivingEntityFilmUseMixin is render-gated), so
                         * the player's own tick never runs shouldSpawnConsumptionEffects
                         * on a held item they are not actually using. Emit the eating/
                         * drinking crumbs, sound and final burp from the clip here, the
                         * same way applyActorReplay does for an ActorEntity actor. The
                         * MCEntity wrapper gives ItemUseEffects the real ClientLevel and
                         * coordinates the crumbs spawn from. */
                        ItemUseEffects.tick(replay, new MCEntity(player), replayTick);

                        /* First person teleports the player from keyframes instead of walking it, so vanilla's
                         * bob amplitude (the view-bobbing stride) is computed from a zero velocity and stays
                         * flat. Re-derive it from the actual per-tick displacement (the same source as the limb
                         * animation) with vanilla's own easing. oBob already holds last tick's value
                         * (snapshotted by the player tick), so only the current one is advanced — keeping the bob
                         * smooth between frames. */
                        float dx = (float) (player.getX() - player.xo);
                        float dz = (float) (player.getZ() - player.zo);
                        float stride = grounded ? Math.min(0.1F, (float) Math.sqrt(dx * dx + dz * dz)) : 0F;

                        player.bob = player.oBob + (stride - player.oBob) * 0.4F;

                        if (player instanceof ClientPlayerEntityAccessor accessor)
                        {
                            accessor.bbs$setIsSneakingPose(sneaking);
                        }

                        if (player instanceof LocalPlayer playerEntity)
                        {
                            playerEntity.input.shiftKeyDown = sneaking;
                        }

                        player.fallDistance = replay.keyframes.fall.interpolate(replayTick).floatValue();
                    }
                }
            }
        }
    }

    protected void updateEntityAndForm(IEntity entity, int tick)
    {
        entity.update();

        if (entity.getForm() != null)
        {
            entity.getForm().update(entity);
        }
    }

    protected void applyReplay(Replay replay, int ticks, IEntity entity)
    {
        replay.keyframes.apply(ticks, entity);
        replay.applyClientActions(ticks, entity, this.film);

        this.applyReplayItemUse(replay, ticks, entity);
    }

    /** Publish all replay-side item effects after a controller applies keyframes/actions. */
    protected void applyReplayItemUse(Replay replay, int ticks, IEntity entity)
    {
        if (replay.actor.get())
        {
            return;
        }

        LivingEntity user = ItemUsePose.livingOf(entity);
        ItemUsePose.Use use = ReplayItemUse.compute(replay, ticks, true, user);
        ItemUsePose.Use offUse = ReplayItemUse.compute(replay, ticks, false, user);
        ThirdPersonItemUse.set(ThirdPersonItemUse.keyOf(entity), use, offUse);
        ItemUseEffects.tick(replay, entity, ticks);

        if (user != null)
        {
            LivePlayerItemUse.apply(user, use, offUse);
        }
    }

    private void applyActorReplay(Replay replay, int ticks, ActorEntity actor, IEntity editorEntity)
    {
        MCEntity actorEntity = actor.getEntity();
        int hurtTimer = actorEntity.getHurtTimer();

        actorEntity.update();
        replay.keyframes.apply(ticks, actorEntity, List.of(ReplayKeyframes.GROUP_POSITION));
        actorEntity.setHurtTimer(Math.max(hurtTimer, actorEntity.getHurtTimer()));

        /* ActorEntity is teleported to keyframes, so vanilla never sees a floor
         * collision and never runs its step/sprint effects. Probe the floor with
         * the recorded delta, then snap back to the exact keyframe position. */
        double x = replay.keyframes.x.interpolate(ticks);
        double y = replay.keyframes.y.interpolate(ticks);
        double z = replay.keyframes.z.interpolate(ticks);
        boolean grounded = replay.keyframes.grounded.interpolate(ticks) > 0;
        Vec3 actorPos = actor.position();
        double dY = y - actorPos.y - (grounded ? ReplayKeyframes.GRAVITY_PROBE : 0D);

        actor.move(MoverType.SELF, new Vec3(x - actorPos.x, dY, z - actorPos.z));
        actor.setPos(x, y, z);
        actor.setOnGround(grounded);
        actor.setSprinting(replay.keyframes.sprinting.interpolate(ticks) > 0);

        /* The death channel drives the actor's topple rotation and red overlay. ActorEntity
         * is a real LivingEntity, so writing deathTime here lets ActorEntityRenderer apply
         * the same fall-over it already uses for vanilla knockback deaths. */
        replay.keyframes.applyDeath(ticks, actorEntity);

        if (this.getTransition(editorEntity, 1F) == 0F)
        {
            actorEntity.setPrevX(actorEntity.getX());
            actorEntity.setPrevY(actorEntity.getY());
            actorEntity.setPrevZ(actorEntity.getZ());
            actorEntity.setPrevYaw(actorEntity.getYaw());
            actorEntity.setPrevHeadYaw(actorEntity.getHeadYaw());
            actorEntity.setPrevBodyYaw(actorEntity.getBodyYaw());
            actorEntity.setPrevPitch(actorEntity.getPitch());
        }

        replay.applyClientActions(ticks, actorEntity, this.film);
        ItemUsePose.Use use = ReplayItemUse.compute(replay, ticks, true, actorEntity.getMcEntity() instanceof LivingEntity living ? living : null);
        ItemUsePose.Use offUse = ReplayItemUse.compute(replay, ticks, false, actorEntity.getMcEntity() instanceof LivingEntity living ? living : null);
        ThirdPersonItemUse.set(ThirdPersonItemUse.keyOf(actorEntity), use, offUse);
        ItemUseEffects.tick(replay, actorEntity, ticks);
    }

    public void startRenderFrame(float transition)
    {
        /* No film, no replays. This controller is built before a film is loaded and is driven
         * anyway, so the empty case is normal. The walk used to run off the entity map, which is
         * empty in that state, and the list was read inside the loop body where it was never
         * reached; now the replays are read first, so the film has to be checked here or this
         * throws on the dashboard with nothing open. */
        if (this.film == null)
        {
            return;
        }

        /* Phase 1: every replay receives this frame's ordinary properties and
         * scalar procedural controls before any target channel samples another
         * replay's bones. This removes replay iteration order from simulation. */
        List<Replay> replays = this.film.replays.getList();

        for (int i = 0; i < replays.size(); i++)
        {
            Replay replay = replays.get(i);
            IEntity entity = this.entities.get(replay.getId());

            if (entity == null)
            {
                continue;
            }

            Entity anEntity = this.getReplayActor(replay);

            FilmMatrices.markRelativeReplayEntity(entity, replay.relative.get());

            if (!replay.enabled.get())
            {
                FormUtilsClient.release(entity.getForm());
                this.clearActorTimeline(anEntity);

                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.PROPERTIES))
            {
                FormUtilsClient.release(entity.getForm());
                this.clearActorTimeline(anEntity);

                continue;
            }

            float delta = this.getTransition(entity, transition);
            int tick = replay.getTick(this.getTick());

            /* Apply property */
            Form form1 = entity.getForm();
            applyReplayChannels(replay, form1, tick + delta);

            if (anEntity instanceof ActorEntity actor)
            {
                Form form = actor.getForm();

                applyReplayChannels(replay, form, tick + delta);
            }
            else if (anEntity instanceof Player player)
            {
                Morph morph = Morph.getMorph(player);

                if (morph != null)
                {
                    Form form = morph.getForm();

                    applyReplayChannels(replay, form, tick + delta);
                }

                float yawHead = replay.keyframes.headYaw.interpolate(tick + delta).floatValue();
                float yawBody = replay.keyframes.bodyYaw.interpolate(tick + delta).floatValue();
                float pitch = replay.keyframes.pitch.interpolate(tick + delta).floatValue();

                player.setYRot(yawHead);
                player.setYHeadRot(yawHead);
                player.setXRot(pitch);
                player.setYBodyRot(yawBody);
                player.yRotO = yawHead;
                player.yHeadRotO = yawHead;
                player.xRotO = pitch;
                player.yBodyRotO = yawBody;
            }

            if (replay.actor.get())
            {
                FilmActorTimeline.update(this, anEntity, replay.properties, tick + delta, this.isTimelinePlaying());
            }
            else
            {
                this.clearActorTimeline(anEntity);
            }
        }

        /* Phase 2: target maps are now assembled without advancing current-age
         * physics. Placement-aware sampling happens only after this phase. */
        List<Replay> phaseTwoReplays = this.film.replays.getList();

        for (int i = 0; i < phaseTwoReplays.size(); i++)
        {
            Replay replay = phaseTwoReplays.get(i);
            IEntity entity = this.entities.get(replay.getId());

            if (entity == null || !replay.enabled.get() || !this.canUpdate(i, replay, entity, UpdateMode.PROPERTIES))
            {
                continue;
            }

            float delta = this.getTransition(entity, transition);
            int tick = replay.getTick(this.getTick());

            applyReplayTargets(replay, entity.getForm(), tick + delta, delta, this.entities);

            Entity anEntity = this.getReplayActor(replay);

            if (anEntity instanceof ActorEntity actor)
            {
                applyReplayTargets(replay, actor.getForm(), tick + delta, delta, this.entities);
            }
            else if (anEntity instanceof Player player)
            {
                Morph morph = Morph.getMorph(player);

                if (morph != null)
                {
                    applyReplayTargets(replay, morph.getForm(), tick + delta, delta, this.entities);
                }
            }
        }

    }

    private Entity getReplayActor(Replay replay)
    {
        Map<String, Integer> actors = this.getActors();
        Integer entityId = actors == null ? null : actors.get(replay.getId());
        Level level = Minecraft.getInstance().level;

        return entityId == null || level == null ? null : level.getEntity(entityId);
    }

    /** Values and scalar controls are evaluated for every actor before any spatial anchor. */
    public static void applyReplayChannels(Replay replay, Form root, float tick)
    {
        if (replay == null || root == null) return;
        replay.properties.applyProperties(root, tick);
        applyTargetControls(replay, root, tick);
    }

    /** Spatial anchors read the complete actor frame, preserving multiview and IK bake ordering. */
    public static void applyReplayTargets(Replay replay, Form root, float tick, float transition, Map<String, IEntity> entities)
    {
        if (replay == null || root == null) return;
        TrackBehaviours.clearOverrides(root, kind -> kind == TrackKind.IK_TARGET || kind == TrackKind.POLE_TARGET
            || kind == TrackKind.PHYSICS_TARGET);
        TrackContext context = TrackContext.frame(root, transition, (anchor, fraction) ->
        {
            if (!anchor.hasTarget() || !entities.containsKey(anchor.replay)
                || FilmMatrices.hasRelativeAnchorTarget(entities, anchor)) return null;
            Pair<Matrix4f, Float> result = FilmMatrices.getTotalMatrix(entities, anchor, IDENTITY,
                0D, 0D, 0D, fraction, 0, true, false);
            return (result.a == null ? IDENTITY : result.a).getTranslation(new Vector3f());
        });
        replay.properties.apply(context, tick, 1F, track -> track.kind() == TrackKind.IK_TARGET
            || track.kind() == TrackKind.POLE_TARGET || track.kind() == TrackKind.PHYSICS_TARGET);
    }

    public void update(Replay replay, Form root, float tick, float transition)
    {
        applyTargetControls(replay, root, tick);
        applyReplayTargets(replay, root, tick, transition, this.entities);
    }

    private static void applyTargetControls(Replay replay, Form root, float tick)
    {
        if (replay == null || root == null) return;
        TrackBehaviours.clearOverrides(root, kind -> kind == TrackKind.IK_CONTROLS
            || kind == TrackKind.PHYSICS_CONTROLS || kind == TrackKind.WIND_CONTROLS);
        replay.properties.apply(TrackContext.frame(root, 0F, null), tick, 1F, track -> track.kind() == TrackKind.IK_CONTROLS
            || track.kind() == TrackKind.PHYSICS_CONTROLS || track.kind() == TrackKind.WIND_CONTROLS);
    }

    protected float getTransition(IEntity entity, float transition)
    {
        return this.paused ? 0F : transition;
    }

    protected boolean isTimelinePlaying()
    {
        return !this.paused;
    }

    protected boolean canUpdate(int i, Replay replay, IEntity entity, UpdateMode updateMode)
    {
        if (this.paused && (updateMode == UpdateMode.UPDATE))
        {
            return false;
        }

        return i != this.exception;
    }

    public void render(IBbsWorldRenderContext context)
    {
        BBSProfiler.begin(BBSProfiler.Timer.WORLD_FORMS);

        try
        {
            this.renderScene(context);
        }
        finally
        {
            BBSProfiler.end(BBSProfiler.Timer.WORLD_FORMS);
        }

        if (FilmEvents.RENDER_AFTER.hasListeners())
        {
            FilmEvents.RENDER_AFTER.invoker().onFilmRender(this, BBSWorldRenderContext.bridge(context));
        }
    }

    protected void renderScene(IBbsWorldRenderContext context)
    {
        RenderSystem.enableDepthTest();

        /* Read per pass, never cached in a field: each viewport's world render rebuilds the
         * culling frustum from its own camera and projection, so this one belongs to this pass.
         * A cached frustum would follow the primary view into the side viewports and cull what
         * they can see. */
        Frustum frustum = context.frustum();

        /* Kept after the depth-test call on purpose: that call is part of this pass's setup and ran
         * whether or not there was anything to draw. See startRenderFrame for why the film is
         * checked at all. */
        if (this.film == null)
        {
            return;
        }

        List<Replay> renderReplays = this.film.replays.getList();

        for (int i = 0; i < renderReplays.size(); i++)
        {
            Replay replay = renderReplays.get(i);
            IEntity entity = this.entities.get(replay.getId());

            if (entity == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.RENDER))
            {
                continue;
            }

            /* Skips only this pass's draw. Nothing in the replay, the entity or our maps is
             * touched, so a replay anchored onto this one is unaffected — anchors read matrices
             * through the pose pipeline, not through the draw. */
            boolean culled = FilmFrustumCulling.isCulled(frustum, entity);

            FilmFrustumCulling.probeReplay(frustum, entity, culled);

            if (culled)
            {
                continue;
            }

            this.renderEntity(context, replay, entity);
        }
    }

    protected void renderEntity(IBbsWorldRenderContext context, Replay replay, IEntity entity)
    {
        FilmControllerContext filmContext = getFilmControllerContext(context, replay, entity);
        float transition = getTransition(entity, context.tickDelta());

        filmContext.transition = transition;
        filmContext.timeline(replay.properties, replay.getTick(this.getTick()) + transition, this.isTimelinePlaying());

        if (replay.actor.get())
        {
            /* Actor replays render the world ActorEntity instead of the editor
             * entity. The editor entity is still the gizmo's placement source,
             * so keep capturing this frame's transform/pose/anchor matrices
             * (Gizmo#captureVisual) without drawing the editor form on top of
             * the actor — otherwise the gizmo sits on a stale matrix and stops
             * following the replayed form's anchor. */
            filmContext.gizmoOnly(true);
        }

        FilmEntityRenderer.renderEntity(filmContext);

        if (replay.actor.get())
        {
            Map<String, Integer> actors = this.getActors();
            Integer actorId = actors == null ? null : actors.get(replay.getId());
            Level level = Minecraft.getInstance().level;
            Entity actor = actorId == null || level == null ? null : level.getEntity(actorId);

            /* The world entity owns the body; the replay still owns its label. */
            FilmEntityRenderer.renderActorNameTag(filmContext, actor, context.tickDelta());
        }
    }

    protected FilmControllerContext getFilmControllerContext(IBbsWorldRenderContext context, Replay replay, IEntity entity)
    {
        return FilmControllerContext.instance
            .setup(
                this.entities,
                entity,
                replay,
                context.camera(),
                context.matrixStack(),
                context.consumers(),
                context.tickDelta()
            )
            .shadow(replay.shadow.get(), replay.shadowSize.get())
            .nameTag(replay.nameTag.get())
            .relative(replay.relative.get());
    }

    public void shutdown()
    {
        try
        {
            FilmEvents.SHUTDOWN.invoker().onFilmShutdown(this);
        }
        finally
        {
            this.releaseFormPlayback();
        }
    }

    private void releaseFormPlayback()
    {
        for (IEntity entity : this.entities.values())
        {
            FormUtilsClient.release(entity.getForm());
        }

        FilmActorTimeline.clearOwner(this, this::releaseActorForm);
        ThirdPersonItemUse.clear();
        ItemUseEffects.clear();
        LivePlayerItemUse.clear();
    }

    private void clearActorTimeline(Entity entity)
    {
        if (FilmActorTimeline.clear(this, entity))
        {
            this.releaseActorForm(entity);
        }
    }

    private void releaseActorForm(Entity entity)
    {
        if (entity instanceof ActorEntity actor)
        {
            FormUtilsClient.release(actor.getForm());
        }
        else if (entity instanceof Player player)
        {
            Morph morph = Morph.getMorph(player);

            if (morph != null)
            {
                FormUtilsClient.release(morph.getForm());
            }
        }
    }

    public static enum UpdateMode
    {
        UPDATE, RENDER, PROPERTIES;
    }
}
