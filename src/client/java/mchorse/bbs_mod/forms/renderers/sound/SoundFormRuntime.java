package mchorse.bbs_mod.forms.renderers.sound;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundPlaybackTimeline;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Shared runtime owner for the sphere and cone sound renderers. */
final class SoundFormRuntime
{
    private final SoundFormPlayback playback = new SoundFormPlayback();
    private final SoundSurfaceProbe probe = new SoundSurfaceProbe();
    private final Vector3f position = new Vector3f();

    private Object owner = new Object();
    private boolean reconciled;

    private int elapsed;
    private boolean locallyActive;
    private boolean timelineDriven;
    private boolean timelineActive;
    private float timelineTick = Float.NaN;
    private float startOffset = Float.NaN;

    public void tick(AbstractSoundForm form, IEntity entity)
    {
        if (this.timelineDriven)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer == null ? null : mc.gameRenderer.getMainCamera();

        if (camera == null)
        {
            return;
        }

        boolean active = form.playing.get();

        if (!active)
        {
            this.elapsed = 0;
        }
        else if (this.locallyActive)
        {
            this.elapsed += 1;
        }

        this.locallyActive = active;

        Vec3 listener = camera.getPosition();
        float seconds = form.startOffset.get() + this.elapsed / 20F;

        this.update(form, entity,
            (float) entity.getX(), (float) entity.getY(), (float) entity.getZ(),
            (float) listener.x, (float) listener.y, (float) listener.z,
            seconds, active, active && this.elapsed == 0);
    }

    public void updateTimeline(AbstractSoundForm form, mchorse.bbs_mod.forms.renderers.FormRenderingContext context)
    {
        if (context.timelineProperties == null || context.isPicking() || context.modelRenderer || context.ui)
        {
            return;
        }

        this.timelineDriven = true;

        float tick = context.timelineTick;
        boolean active = form.playing.get();
        float activationTick = this.findActivationTick(context.timelineProperties, form, tick, active);
        float offset = form.startOffset.get();
        float seconds = SoundPlaybackTimeline.clipSeconds(tick, activationTick, offset);
        boolean seek = SoundPlaybackTimeline.shouldSeek(
            this.timelineTick, tick, this.timelineActive, active, context.timelinePlaying)
            || active && Float.isFinite(this.startOffset) && Math.abs(offset - this.startOffset) > 1e-4F;

        context.world.last().pose().getTranslation(this.position);

        this.update(form, context.entity,
            this.position.x, this.position.y, this.position.z,
            (float) context.camera.position.x, (float) context.camera.position.y, (float) context.camera.position.z,
            seconds, context.timelinePlaying, seek);

        this.timelineTick = tick;
        this.timelineActive = active;
        this.startOffset = offset;
    }

    @SuppressWarnings("unchecked")
    private float findActivationTick(FormProperties properties, AbstractSoundForm form, float tick, boolean active)
    {
        BaseValue grouped = properties.get(SoundKeyframeValue.channelId(form, SoundKeyframeValue.Group.SOUND));
        String path = FormUtils.getPropertyPath(form.playing);
        BaseValue value = properties.get(path);
        KeyframeChannel<SoundKeyframeValue> groupedChannel = grouped instanceof KeyframeChannel<?> channel
            ? (KeyframeChannel<SoundKeyframeValue>) channel : null;
        KeyframeChannel<Boolean> legacyChannel = value instanceof KeyframeChannel<?> channel
            ? (KeyframeChannel<Boolean>) channel : null;

        return SoundPlaybackTimeline.findSoundActivationTick(groupedChannel, legacyChannel, tick, active);
    }

    private void update(AbstractSoundForm form, IEntity entity,
        float formX, float formY, float formZ,
        float listenerX, float listenerY, float listenerZ,
        float seconds, boolean transportPlaying, boolean seek)
    {
        this.reconciled = true;

        Level world = entity == null ? null : entity.getWorld();
        boolean active = form.playing.get() && world != null;
        boolean testBlocks = active && !form.passThroughBlocks.get();
        boolean testEntities = active && !form.passThroughEntities.get();
        boolean collectBlocks = active && form.getReflectionOrder() > 0
            && form.blockReflections.get() && !form.passThroughBlocks.get();
        boolean collectEntities = active && form.getReflectionOrder() > 0
            && form.entityReflections.get() && !form.passThroughEntities.get();
        Entity ownerEntity = entity instanceof MCEntity mcEntity ? mcEntity.getMcEntity() : null;
        Entity listenerEntity = Minecraft.getInstance().getCameraEntity();
        int surfaces = active
            ? this.probe.probe(world, ownerEntity, listenerEntity,
                formX, formY, formZ, listenerX, listenerY, listenerZ, form.getMaxDistance(),
                testBlocks, collectBlocks, testEntities, collectEntities)
            : 0;

        this.playback.update(
            BBSModClient.getSounds(), this.owner,
            form,
            formX, formY, formZ,
            listenerX, listenerY, listenerZ,
            this.probe.getSurfaces(), surfaces,
            testBlocks && this.probe.isBlockOccluded(),
            testEntities && this.probe.isEntityOccluded(),
            seconds, transportPlaying, seek);
    }

    public void release()
    {
        if (this.reconciled)
        {
            this.playback.release(BBSModClient.getSounds(), this.owner);
            this.owner = new Object();
        }

        this.reconciled = false;
        this.elapsed = 0;
        this.locallyActive = false;
        /* A Film actor can tick once more while its removal packet is in
         * flight. Keep local ticking suppressed after timeline teardown so
         * that tick cannot resurrect a source under the fresh owner. */
        this.timelineDriven = true;
        this.timelineActive = false;
        this.timelineTick = Float.NaN;
        this.startOffset = Float.NaN;
    }
}
