package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

public class Recorder extends WorldFilmController
{
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    public ReplayKeyframes keyframes = new ReplayKeyframes("keyframes");
    public FormProperties properties = new FormProperties("properties");
    public final List<RecordedMob> mobs = new ArrayList<>();
    public float hp;
    public float hunger;
    public int xpLevel;
    public float xpProgress;

    private static Matrix4f perspective = new Matrix4f();
    /**
     * Set when this recorder survives a death-respawn player clone. The new client player
     * is a different instance, so {@link #isInCurrentLevel()} must not reject it; genuine
     * disconnect or dimension change still terminates the recording.
     */
    private boolean cloneSurvived;

    public Form lastForm;
    public Vector3d lastPosition;
    public Vector4f lastRotation;

    public int countdown;
    public final int initialTick;

    /**
     * Where the take is meant to begin, when starting the recording put the player on
     * the replay's own mark &mdash; see {@link #awaitMark(Vector3d)}.
     */
    private Vector3d mark;
    private int markWait;
    private boolean delayedServerStart;

    /** How close to the mark counts as standing on it, in blocks. */
    private static final double MARK_REACHED = 1D;

    /** How many ticks the take may be held waiting for the teleport to land. */
    private static final int MARK_TIMEOUT = 20;

    private final ClientLevel initialLevel;
    private final LocalPlayer initialPlayer;
    /* The network tuple is immutable even if the editor later renames the
     * Film or another controller mutates the inherited playback fields. */
    private final String recordingFilmId;
    private final int recordingReplayId;
    private final int recordingTick;

    public static void renderCameraPreview(Position position, Camera camera, PoseStack stack)
    {
        if (!BBSSettings.recordingOverlays.get())
        {
            return;
        }

        Vector4f vector = Vectors.TEMP_4F;
        Matrix4f matrix = Matrices.TEMP_4F;
        float x = (float) (position.point.x - camera.getPosition().x);
        float y = (float) (position.point.y - camera.getPosition().y);
        float z = (float) (position.point.z - camera.getPosition().z);
        float fov = MathUtils.toRad(position.angle.fov);
        float aspect = BBSRendering.getVideoWidth() / (float) BBSRendering.getVideoHeight();
        float thickness = 0.025F;

        perspective.identity().perspective(fov, aspect, 0.001F, 100F).invert();

        matrix.identity()
            .rotateY(MathUtils.toRad(position.angle.yaw + 180))
            .rotateX(MathUtils.toRad(-position.angle.pitch));

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        transformFrustum(vector, matrix, 1F, 1F);
        Draw.fillBoxTo(builder, stack, x, y, z, x + vector.x, y + vector.y, z + vector.z, thickness, 1F, 1F, 1F, 1F);

        transformFrustum(vector, matrix, -1F, 1F);
        Draw.fillBoxTo(builder, stack, x, y, z, x + vector.x, y + vector.y, z + vector.z, thickness, 1F, 1F, 1F, 1F);

        transformFrustum(vector, matrix, 1F, -1F);
        Draw.fillBoxTo(builder, stack, x, y, z, x + vector.x, y + vector.y, z + vector.z, thickness, 1F, 1F, 1F, 1F);

        transformFrustum(vector, matrix, -1F, -1F);
        Draw.fillBoxTo(builder, stack, x, y, z, x + vector.x, y + vector.y, z + vector.z, thickness, 1F, 1F, 1F, 1F);

        transformFrustum(vector, matrix, 0F, 0F);
        Draw.fillBoxTo(builder, stack, x, y, z, x + vector.x, y + vector.y, z + vector.z, thickness, 0F, 0.5F, 1F, 1F);

        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.disableDepthTest();
    }

    private static void transformFrustum(Vector4f vector, Matrix4f matrix, float x, float y)
    {
        vector.set(x, y, 0F, 1F);
        vector.mul(perspective);
        vector.w = 1F;
        vector.normalize().mul(100F);
        vector.w = 1F;
        vector.mul(matrix);
    }

    public Recorder(Film film, Form form, int replayId, int tick)
    {
        super(film);

        this.lastForm = FormUtils.copy(form);
        this.exception = replayId;
        this.tick = tick;
        this.countdown = TimeUtils.toTick(BBSSettings.recordingCountdown.get());
        this.initialTick = tick;
        this.initialLevel = Minecraft.getInstance().level;
        this.initialPlayer = Minecraft.getInstance().player;
        this.recordingFilmId = film == null ? null : film.getId();
        this.recordingReplayId = replayId;
        this.recordingTick = tick;
    }

    public String getRecordingFilmId()
    {
        return this.recordingFilmId;
    }

    public int getRecordingReplayId()
    {
        return this.recordingReplayId;
    }

    public int getRecordingTick()
    {
        return this.recordingTick;
    }

    public boolean matchesRecording(String filmId, int replayId, int tick)
    {
        return this.recordingReplayId == replayId
            && this.recordingTick == tick
            && (this.recordingFilmId == null ? filmId == null : this.recordingFilmId.equals(filmId));
    }

    ClientLevel getInitialLevel()
    {
        return this.initialLevel;
    }

    public boolean isInCurrentLevel()
    {
        Minecraft client = Minecraft.getInstance();

        return this.initialLevel != null
            && this.initialPlayer != null
            && client.level == this.initialLevel
            /* A death-respawn replaces the client player instance; after surviving that
             * clone the new player is an acceptable continuation of the same recording. */
            && (client.player == this.initialPlayer || this.cloneSurvived);
    }

    /** Marks this recording as having survived a death-respawn player clone. */
    public void surviveRespawn()
    {
        this.cloneSurvived = true;
    }

    public boolean hasNotStarted()
    {
        return this.countdown > 0 || this.mark != null;
    }

    /**
     * Hold the take until the player is actually standing on {@code mark}. The teleport
     * that puts them there goes through the server and comes back a couple of ticks
     * later, while the countdown ticks down on its own clock - with a short (or zero)
     * countdown the first recorded ticks would catch the player still at the old spot,
     * and the take would open with a visible jump.
     */
    public void awaitMark(Vector3d mark)
    {
        this.mark = mark;
        this.delayedServerStart = true;
    }

    public boolean isAwaitingMark()
    {
        return this.mark != null;
    }

    public boolean takeDelayedServerStart()
    {
        if (this.delayedServerStart && this.mark == null)
        {
            this.delayedServerStart = false;

            return true;
        }

        return false;
    }

    public boolean hasRecordedFrame()
    {
        return this.lastPosition != null;
    }

    public void update()
    {
        /* The teleport may arrive while the countdown is still running. Release the
         * mark at that moment so client pose frames and server action clips keep the
         * same timeline when the player walks away before recording starts. */
        this.landMark();

        if (this.countdown > 0)
        {
            this.countdown -= 1;

            return;
        }

        if (this.mark != null && !this.giveUpOnMark())
        {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (this.lastPosition == null)
        {
            this.lastPosition = new Vector3d(player.getX(), player.getY(), player.getZ());
            this.lastRotation = new Vector4f(player.getYRot(), player.getXRot(), player.getYHeadRot(), player.yBodyRot);

            this.hp = player.getHealth();
            this.hunger = player.getFoodData().getFoodLevel();
            this.xpLevel = player.experienceLevel;
            this.xpProgress = player.experienceProgress;

            this.captureMobs(player);
        }

        if (this.tick >= 0)
        {
            Morph morph = Morph.getMorph(player);

            this.keyframes.record(this.tick, morph.entity, null);
            this.recordMobs();
        }

        super.update();
    }

    /** Release the mark as soon as the teleported player reaches it. */
    private void landMark()
    {
        if (this.mark != null && this.distanceToMark() <= MARK_REACHED)
        {
            this.mark = null;
        }
    }

    /**
     * Whether to begin without the teleport ever having landed. Give up after
     * {@link #MARK_TIMEOUT} ticks so an unreachable mark cannot hold a recording forever.
     */
    private boolean giveUpOnMark()
    {
        this.markWait += 1;

        if (this.markWait < MARK_TIMEOUT)
        {
            return false;
        }

        LOGGER.warn("[BBS film] Recording is starting {} blocks off the replay's mark - the teleport never landed.",
            String.format("%.2f", this.distanceToMark()));

        this.mark = null;

        return true;
    }

    private double distanceToMark()
    {
        LocalPlayer player = Minecraft.getInstance().player;

        return player == null ? Double.MAX_VALUE : this.mark.distance(player.getX(), player.getY(), player.getZ());
    }

    private void captureMobs(LocalPlayer player)
    {
        float radius = this.film.mobRecordingRadius.get();

        if (radius <= 0F)
        {
            return;
        }

        AABB box = player.getBoundingBox().inflate(radius);
        double radiusSq = radius * radius;

        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, box, (e) -> e != player && e.isAlive() && e.distanceToSqr(player) <= radiusSq))
        {
            MobForm form = Morph.createMobForm(entity);

            if (form != null)
            {
                this.mobs.add(new RecordedMob(form, entity));
            }
        }
    }

    private void recordMobs()
    {
        for (RecordedMob mob : this.mobs)
        {
            if (mob.entity.getMcEntity().isAlive())
            {
                mob.keyframes.record(this.tick, mob.entity, null);
            }
        }
    }

    public static class RecordedMob
    {
        public final MobForm form;
        public final MCEntity entity;
        public final ReplayKeyframes keyframes = new ReplayKeyframes("keyframes");

        public RecordedMob(MobForm form, Entity mcEntity)
        {
            this.form = form;
            this.entity = new MCEntity(mcEntity);
        }
    }

    public void render(IBbsWorldRenderContext context)
    {
        super.render(context);

        renderCameraPreview(this.position, context.camera(), context.matrixStack());
    }

    @Override
    public void shutdown()
    {
        this.shutdown(true);
    }

    void shutdown(boolean restorePlayer)
    {
        Vector3d pos = this.lastPosition;
        Throwable failure = null;

        if (restorePlayer && this.isInCurrentLevel() && pos != null)
        {
            Vector4f rot = this.lastRotation;

            try
            {
                PlayerUtils.teleport(pos.x, pos.y, pos.z, rot.z, rot.y);
            }
            catch (RuntimeException | Error exception)
            {
                failure = exception;
            }

            try
            {
                ClientNetwork.sendPlayerForm(this.lastForm);
            }
            catch (RuntimeException | Error exception)
            {
                if (failure == null)
                {
                    failure = exception;
                }
                else if (failure != exception)
                {
                    failure.addSuppressed(exception);
                }
            }
        }

        try
        {
            super.shutdown();
        }
        catch (RuntimeException | Error exception)
        {
            if (failure == null)
            {
                failure = exception;
            }
            else if (failure != exception)
            {
                failure.addSuppressed(exception);
            }
        }

        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        else if (failure instanceof Error error)
        {
            throw error;
        }
    }
}
