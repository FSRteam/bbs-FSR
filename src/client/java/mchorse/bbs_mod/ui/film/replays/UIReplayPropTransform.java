package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.EnumSet;

/** Adapts an actor's world placement to loop-aware, undoable replay channels. */
public class UIReplayPropTransform extends UIPropTransform
{
    public static final Gizmo.HandleMask MASK = Gizmo.HandleMask.of(
        EnumSet.of(Gizmo.Op.MOVE, Gizmo.Op.SCREEN, Gizmo.Op.ROTATE),
        EnumSet.of(Axis.X, Axis.Y)
    );

    private final Transform scratch = new Transform();
    private Replay replay;
    private IEntity entity;
    private float tick;
    private double headYawOffset;
    private double bodyYawOffset;
    private BaseType snapshot;

    public UIReplayPropTransform()
    {
        this.callbacks(() -> this.notifyKeyframes(0), () ->
        {
            if (this.replay != null) this.replay.keyframes.postNotify();
        }, () -> this.notifyKeyframes(IValueListener.FLAG_UNMERGEABLE));
        this.setVisible(false);
    }

    private void notifyKeyframes(int flag)
    {
        if (this.replay != null) this.replay.keyframes.preNotify(flag);
    }

    public void syncFromReplay(Replay replay, IEntity entity, float tick)
    {
        if (this.isEditing()) return;

        this.replay = replay;
        this.entity = entity;
        this.tick = tick;
        this.snapshot = null;

        if (replay == null || entity == null)
        {
            this.setTransform(null);
            return;
        }

        double yaw = entity.getYaw();
        this.headYawOffset = entity.getHeadYaw() - yaw;
        this.bodyYawOffset = entity.getBodyYaw() - yaw;
        this.scratch.rotationMode = Transform.RotationMode.EULER;
        this.scratch.translate.set((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
        this.scratch.rotate.set(MathUtils.toRad(entity.getPitch()), MathUtils.toRad((float) -yaw), 0F);
        this.scratch.scale.set(1F);
        this.setTransform(this.scratch);
    }

    @Override
    public TransformSpace getSpace()
    {
        return TransformSpace.WORLD;
    }

    @Override
    public boolean isLocal()
    {
        return false;
    }

    @Override
    public Gizmo.HandleMask getHandleMask()
    {
        return MASK;
    }

    @Override
    public boolean isRotationChannelOnly()
    {
        return true;
    }

    @Override
    public String readoutPrefix()
    {
        return this.replay == null ? null : this.replay.getName() + ": ";
    }

    private void snapshot()
    {
        if (this.snapshot == null) this.snapshot = this.replay.keyframes.toData();
    }

    private float writeTick()
    {
        int whole = (int) this.tick;
        return this.replay.getTick(whole) + (this.tick - whole);
    }

    @Override
    public void acceptChanges()
    {
        super.acceptChanges();
        this.snapshot = null;
    }

    @Override
    public void rejectChanges()
    {
        /* The base rewind writes every transform channel even before the first drag update. */
        BaseType before = this.snapshot == null && this.replay != null ? this.replay.keyframes.toData() : this.snapshot;
        super.rejectChanges();

        if (before != null && this.replay != null)
        {
            this.replay.keyframes.preNotify();
            this.replay.keyframes.fromData(before);
            this.replay.keyframes.postNotify();
            this.endGesture();
        }

        this.snapshot = null;
    }

    @Override
    public void setT(Axis axis, double x, double y, double z)
    {
        if (this.replay == null) return;
        this.snapshot();
        this.preCallback();
        this.scratch.translate.set((float) x, (float) y, (float) z);
        float tick = this.writeTick();
        ReplayKeyframes keyframes = this.replay.keyframes;
        keyframes.x.insert(tick, x);
        keyframes.y.insert(tick, y);
        keyframes.z.insert(tick, z);

        /* Publish derived state at render rate, before the next 20 Hz film tick. */
        if (this.entity != null)
        {
            this.entity.setPosition(x, y, z);
            this.entity.setPrevX(x);
            this.entity.setPrevY(y);
            this.entity.setPrevZ(z);
        }
        this.postCallback();
    }

    @Override
    public void setS(Axis axis, double x, double y, double z)
    {}

    @Override
    public void setR(Axis axis, double x, double y, double z)
    {
        if (this.replay == null) return;
        this.snapshot();
        this.preCallback();
        this.scratch.rotate.set(MathUtils.toRad((float) x), MathUtils.toRad((float) y), 0F);
        float tick = this.writeTick();
        ReplayKeyframes keyframes = this.replay.keyframes;
        double yaw = -y;
        keyframes.yaw.insert(tick, yaw);
        keyframes.headYaw.insert(tick, yaw + this.headYawOffset);
        keyframes.bodyYaw.insert(tick, yaw + this.bodyYawOffset);
        keyframes.pitch.insert(tick, x);

        if (this.entity != null)
        {
            this.entity.setYaw((float) yaw);
            this.entity.setHeadYaw((float) (yaw + this.headYawOffset));
            this.entity.setBodyYaw((float) (yaw + this.bodyYawOffset));
            this.entity.setPitch((float) x);
            this.entity.setPrevYaw((float) yaw);
            this.entity.setPrevHeadYaw((float) (yaw + this.headYawOffset));
            this.entity.setPrevBodyYaw((float) (yaw + this.bodyYawOffset));
            this.entity.setPrevPitch((float) x);
        }
        this.postCallback();
    }
}
