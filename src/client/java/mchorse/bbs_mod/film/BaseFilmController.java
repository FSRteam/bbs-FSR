package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
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
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.mixin.client.ClientPlayerEntityAccessor;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.List;
import java.util.Map;

public abstract class BaseFilmController
{
    public final Film film;

    protected IntObjectMap<IEntity> entities = new IntObjectHashMap<>();

    public boolean paused;
    public int exception = -1;

    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f TEMP_VECTOR = new Vector3f();

    /* Film controller */

    public BaseFilmController(Film film)
    {
        this.film = film;
    }

    public IntObjectMap<IEntity> getEntities()
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

        int i = 0;

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

                this.entities.put(i, entity);
            }

            i += 1;
        }

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
        Level level = Minecraft.getInstance().level;

        if (level == null)
        {
            return;
        }

        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            List<Replay> replays = this.film.replays.getList();
            Replay replay = CollectionUtils.getSafe(replays, i);

            if (replay == null || !replay.enabled.get())
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

        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            List<Replay> replays = this.film.replays.getList();
            Replay replay = CollectionUtils.getSafe(replays, i);

            if (replay == null || !replay.enabled.get())
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
                        boolean sneaking = replay.keyframes.sneaking.interpolate(replayTick) > 0;
                        boolean grounded = replay.keyframes.grounded.interpolate(replayTick) > 0;

                        Vec3 pos = player.position();

                        double dY = y - pos.y - (grounded ? ReplayKeyframes.GRAVITY_PROBE : 0D);

                        player.move(MoverType.SELF, new Vec3(x - pos.x, dY, z - pos.z));
                        player.setPos(x, y, z);

                        player.setShiftKeyDown(sneaking);
                        player.setOnGround(grounded);
                        player.setSprinting(replay.keyframes.sprinting.interpolate(replayTick) > 0);

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
        /* Phase 1: every replay receives this frame's ordinary properties and
         * scalar procedural controls before any target channel samples another
         * replay's bones. This removes replay iteration order from simulation. */
        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            Replay replay = CollectionUtils.getSafe(this.film.replays.getList(), i);
            Entity anEntity = replay == null ? null : this.getReplayActor(replay);

            if (replay != null)
            {
                FilmMatrices.markRelativeReplayEntity(entity, replay.relative.get());
            }

            if (replay == null || !replay.enabled.get())
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
            replay.properties.applyProperties(form1, tick + delta);
            this.applyTargetControls(replay, form1, tick + delta);

            if (anEntity instanceof ActorEntity actor)
            {
                Form form = actor.getForm();

                replay.properties.applyProperties(form, tick + delta);
                this.applyTargetControls(replay, form, tick + delta);
            }
            else if (anEntity instanceof Player player)
            {
                Morph morph = Morph.getMorph(player);

                if (morph != null)
                {
                    Form form = morph.getForm();

                    replay.properties.applyProperties(form, tick + delta);
                    this.applyTargetControls(replay, form, tick + delta);
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
        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            Replay replay = CollectionUtils.getSafe(this.film.replays.getList(), i);

            if (replay == null || !replay.enabled.get() || !this.canUpdate(i, replay, entity, UpdateMode.PROPERTIES))
            {
                continue;
            }

            float delta = this.getTransition(entity, transition);
            int tick = replay.getTick(this.getTick());

            this.applyTargetOverrides(replay, entity.getForm(), tick + delta, delta);

            Entity anEntity = this.getReplayActor(replay);

            if (anEntity instanceof ActorEntity actor)
            {
                this.applyTargetOverrides(replay, actor.getForm(), tick + delta, delta);
            }
            else if (anEntity instanceof Player player)
            {
                Morph morph = Morph.getMorph(player);

                if (morph != null)
                {
                    this.applyTargetOverrides(replay, morph.getForm(), tick + delta, delta);
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

    public void update(Replay replay, Form root, float tick, float transition)
    {
        this.applyTargetControls(replay, root, tick);
        this.applyTargetOverrides(replay, root, tick, transition);
    }

    private void applyTargetControls(Replay replay, Form root, float tick)
    {
        if (replay == null || root == null)
        {
            return;
        }

        this.clearControlOverrides(root);

        if (replay.properties == null || replay.properties.properties == null || replay.properties.properties.isEmpty())
        {
            return;
        }

        for (KeyframeChannel<?> channel : replay.properties.properties.values())
        {
            if (channel == null)
            {
                continue;
            }

            String id = channel.getId();

            if (id == null || id.isEmpty())
            {
                continue;
            }

            if (FormControlKeys.isIKControlChannel(id))
            {
                this.applyIKControls(root, FormControlKeys.parseIKControlFormPath(id), channel, tick);
                continue;
            }

            if (FormControlKeys.isPhysicsControlChannel(id))
            {
                this.applyPhysicsControls(root, FormControlKeys.parsePhysicsControlFormPath(id), channel, tick);
                continue;
            }

            if (FormControlKeys.isWindControlChannel(id))
            {
                this.applyWindControls(root, FormControlKeys.parseWindControlFormPath(id), channel, tick);
            }
        }
    }

    private void applyTargetOverrides(Replay replay, Form root, float tick, float transition)
    {
        if (replay == null || root == null)
        {
            return;
        }

        this.clearSpatialTargetOverrides(root);

        if (replay.properties == null || replay.properties.properties == null || replay.properties.properties.isEmpty())
        {
            return;
        }

        for (KeyframeChannel<?> channel : replay.properties.properties.values())
        {
            if (channel == null)
            {
                continue;
            }

            String id = channel.getId();

            if (id == null || id.isEmpty()
                || FormControlKeys.isIKControlChannel(id)
                || FormControlKeys.isPhysicsControlChannel(id)
                || FormControlKeys.isWindControlChannel(id))
            {
                continue;
            }

            PerLimbService.IKTargetPath ikPath = PerLimbService.parseIKTargetPath(id);

            if (ikPath != null)
            {
                this.applyOverride(root, ikPath.formPath(), ikPath.controller(), channel, tick, transition, TargetKind.IK);
                continue;
            }

            PerLimbService.PoleTargetPath polePath = PerLimbService.parsePoleTargetPath(id);

            if (polePath != null)
            {
                this.applyOverride(root, polePath.formPath(), polePath.controller(), channel, tick, transition, TargetKind.POLE);
                continue;
            }

            PerLimbService.PhysicsTargetPath physicsPath = PerLimbService.parsePhysicsTargetPath(id);

            if (physicsPath != null)
            {
                this.applyPhysicsTarget(root, physicsPath.formPath(), physicsPath.rootBone(), channel, tick, transition);
            }
        }
    }

    private void applyIKControls(Form root, String formPath, KeyframeChannel<?> channel, float tick)
    {
        Form form = formPath == null || formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null)
        {
            return;
        }

        Object value = segment.createInterpolated();

        if (!(value instanceof IKControls controls))
        {
            return;
        }

        for (Map.Entry<String, IKControl> entry : controls.controls.entrySet())
        {
            modelForm.ikControlOverrides.computeIfAbsent(entry.getKey(), (k) -> new IKControl()).copy(entry.getValue());
        }
    }

    private void applyPhysicsControls(Form root, String formPath, KeyframeChannel<?> channel, float tick)
    {
        Form form = formPath == null || formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null)
        {
            return;
        }

        Object value = segment.createInterpolated();

        if (!(value instanceof PhysicsControls controls))
        {
            return;
        }

        for (Map.Entry<String, PhysicsControl> entry : controls.controls.entrySet())
        {
            modelForm.physicsControlOverrides.computeIfAbsent(entry.getKey(), (k) -> new PhysicsControl()).copy(entry.getValue());
        }
    }

    private void applyWindControls(Form root, String formPath, KeyframeChannel<?> channel, float tick)
    {
        Form form = formPath == null || formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null)
        {
            return;
        }

        Object value = segment.createInterpolated();

        if (!(value instanceof WindControl control))
        {
            return;
        }

        if (modelForm.windControlOverride == null)
        {
            modelForm.windControlOverride = new WindControl();
        }

        modelForm.windControlOverride.copy(control);
    }

    private enum TargetKind
    {
        IK, POLE
    }

    private void applyOverride(Form root, String formPath, String targetId, KeyframeChannel<?> channel, float tick, float transition, TargetKind kind)
    {
        Form form = formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null || !(segment.createInterpolated() instanceof Anchor anchor))
        {
            return;
        }

        Map<String, Vector3f> overrides = switch (kind)
        {
            case IK -> modelForm.ikTargetOverrides;
            case POLE -> modelForm.poleTargetOverrides;
        };
        Map<String, Float> weights = switch (kind)
        {
            case IK -> modelForm.ikTargetWeights;
            case POLE -> modelForm.poleTargetWeights;
        };

        /* Resolve the BOUND side at its full position with a 0..1 fade weight, mirroring
         * applyPhysicsTarget: feeding the fading anchor straight to getTotalMatrix would
         * lerp the position from world origin across a "None" key, yanking the pole/target
         * to (0,0,0). The applier eases the override in/out from the config position by the
         * weight instead, so a fade glides from where the bone already is. */
        Anchor resolve;
        float weight;

        if (anchor.previous != null && anchor.isFadeIn())
        {
            resolve = anchor.copy();
            weight = anchor.x;
        }
        else if (anchor.previous != null && anchor.isFadeOut())
        {
            resolve = anchor.previous;
            weight = 1F - anchor.x;
        }
        else
        {
            resolve = anchor;
            weight = 1F;
        }

        IEntity targetEntity = this.entities.get(resolve.replay);

        if (weight <= 0F || resolve.replay == Anchor.NO_ATTACHMENT || targetEntity == null || FilmMatrices.hasRelativeAnchorTarget(this.entities, resolve))
        {
            return;
        }

        Pair<Matrix4f, Float> matrix = FilmMatrices.getTotalMatrix(this.entities, resolve, IDENTITY, 0D, 0D, 0D, transition, 0, true, false);
        Matrix4f resolved = matrix.a != null ? matrix.a : IDENTITY;
        Vector3f position = resolved.getTranslation(TEMP_VECTOR);

        overrides.computeIfAbsent(targetId, (k) -> new Vector3f()).set(position);
        weights.put(targetId, weight);
    }

    /**
     * Physics target override with fade support. Unlike the IK/pole targets this also resolves a fade
     * <em>weight</em>: when the binding crosses a no-target keyframe the shared anchor interpolation lerps the
     * resolved matrix from world origin, which yanks the chain to (0,0,0). Instead we resolve the bound side at
     * its full position and hand the physics solver a 0..1 weight so it can ease the chain in/out from its own
     * tip (see {@link ModelPhysicsRuntime}).
     */
    private void applyPhysicsTarget(Form root, String formPath, String rootBone, KeyframeChannel<?> channel, float tick, float transition)
    {
        Form form = formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null || !(segment.createInterpolated() instanceof Anchor anchor))
        {
            return;
        }

        /* Pick the bound side and how present it is. Fade in/out blends to/from "no target"; a straight switch
         * between two real targets keeps the anchor's own lerp at full weight. */
        Anchor resolve;
        float weight;

        if (anchor.previous != null && anchor.isFadeIn())
        {
            resolve = anchor.copy();
            weight = anchor.x;
        }
        else if (anchor.previous != null && anchor.isFadeOut())
        {
            resolve = anchor.previous;
            weight = 1F - anchor.x;
        }
        else
        {
            resolve = anchor;
            weight = 1F;
        }

        IEntity targetEntity = this.entities.get(resolve.replay);

        if (weight <= 0F || resolve.replay == Anchor.NO_ATTACHMENT || targetEntity == null || FilmMatrices.hasRelativeAnchorTarget(this.entities, resolve))
        {
            return;
        }

        Pair<Matrix4f, Float> matrix = FilmMatrices.getTotalMatrix(this.entities, resolve, IDENTITY, 0D, 0D, 0D, transition, 0, true, false);
        Matrix4f resolved = matrix.a != null ? matrix.a : IDENTITY;
        Vector3f position = resolved.getTranslation(TEMP_VECTOR);

        modelForm.physicsTargetOverrides.computeIfAbsent(rootBone, (k) -> new Vector3f()).set(position);
        modelForm.physicsTargetWeights.put(rootBone, weight);
    }

    private void clearControlOverrides(Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            modelForm.ikControlOverrides.clear();
            modelForm.physicsControlOverrides.clear();
            modelForm.windControlOverride = null;
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                this.clearControlOverrides(child);
            }
        }
    }

    private void clearSpatialTargetOverrides(Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            modelForm.ikTargetOverrides.clear();
            modelForm.poleTargetOverrides.clear();
            modelForm.ikTargetWeights.clear();
            modelForm.poleTargetWeights.clear();
            modelForm.physicsTargetOverrides.clear();
            modelForm.physicsTargetWeights.clear();
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                this.clearSpatialTargetOverrides(child);
            }
        }
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
        RenderSystem.enableDepthTest();

        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            Replay replay = CollectionUtils.getSafe(this.film.replays.getList(), i);

            if (replay == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.RENDER))
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
        this.releaseFormPlayback();
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
