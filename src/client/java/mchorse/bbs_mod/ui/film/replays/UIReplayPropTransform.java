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
        this.callbacks(() ->
        {
            if (this.snapshot == null) this.notifyKeyframes(0);
        }, () ->
        {
            if (this.snapshot == null && this.replay != null) this.replay.keyframes.postNotify();
        }, null).gestureCallbacks(this::beginGesture, this::finishGesture);
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

    /** The hidden picker and axis walk must keep the replay target's exposed frame. */
    @Override
    public TransformSpace resolveGestureSpace(TransformSpace candidate)
    {
        return this.getSpace();
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

    private void beginGesture()
    {
        if (this.replay != null) this.snapshot = this.replay.keyframes.toData().copy();
    }

    private float writeTick()
    {
        int whole = (int) this.tick;
        return this.replay.getTick(whole) + (this.tick - whole);
    }

    private void finishGesture(boolean accept)
    {
        if (this.snapshot == null || this.replay == null) return;

        BaseType before = this.snapshot;
        BaseType after = this.replay.keyframes.toData().copy();

        /* Both viewport cancellation and Escape finish through the gesture host. Restore the
         * channel tree before publishing one accepted edit, including keys inserted by rewind. */
        this.replay.keyframes.fromData(before);

        if (accept && !before.equals(after))
        {
            this.replay.keyframes.preNotify(IValueListener.FLAG_UNMERGEABLE);
            this.replay.keyframes.fromData(after);
            this.replay.keyframes.postNotify();
        }

        this.snapshot = null;
    }

    @Override
    public void setT(Axis axis, double x, double y, double z)
    {
        if (this.replay == null) return;
        this.preCallback();
        this.scratch.translate.set((float) x, (float) y, (float) z);
        float tick = this.writeTick();
        ReplayKeyframes keyframes = this.replay.keyframes;
        keyframes.x.insert(tick, x, this.snapshot == null);
        keyframes.y.insert(tick, y, this.snapshot == null);
        keyframes.z.insert(tick, z, this.snapshot == null);

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
        this.preCallback();
        this.scratch.rotate.set(MathUtils.toRad((float) x), MathUtils.toRad((float) y), 0F);
        float tick = this.writeTick();
        ReplayKeyframes keyframes = this.replay.keyframes;
        double yaw = -y;
        keyframes.yaw.insert(tick, yaw, this.snapshot == null);
        keyframes.headYaw.insert(tick, yaw + this.headYawOffset, this.snapshot == null);
        keyframes.bodyYaw.insert(tick, yaw + this.bodyYawOffset, this.snapshot == null);
        keyframes.pitch.insert(tick, x, this.snapshot == null);

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
