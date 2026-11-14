package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.cubic.render.CubicRenderer.PivotFrame;
import mchorse.bbs_mod.cubic.render.ModelRotationBlender;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-chain simulation state: the Verlet particle arrays plus the bookkeeping the {@link ChainSolver}
 * carries between ticks. Owned by an {@link ModelPhysicsRuntime.InstanceState}, keyed by chain id.
 */
class ChainState
{
    public int lastAge = Integer.MIN_VALUE;
    public Vector3f anchor = new Vector3f();
    public Quaternionf anchorRotation = new Quaternionf();
    public float renderAlpha;
    public Vector3f[] pos;
    public Vector3f[] prev;

    /** Settled shapes of the two latest simulation ticks, each stored in its own tick's anchor frame. */
    public Vector3f[] settledLocal;
    public Vector3f[] settledPrevLocal;

    public Vector3f[] render;

    /** The animated pose the chain springs toward, stored relative to the live anchor frame. */
    public Vector3f[] poseLocal;

    /** Per-chain containers and math scratch are retained after warm-up; none is shared across owners. */
    public final List<PivotFrame> chainFrames = new ArrayList<>();
    public final ModelRotationBlender.Workspace rotationWorkspace = new ModelRotationBlender.Workspace();
    public final Vector3f pinTarget = new Vector3f();
    public final Quaternionf inverseAnchor = new Quaternionf();
    public final Vector3f localScratch = new Vector3f();
    public final Vector3f renderPreviousDirection = new Vector3f();
    public final Vector3f renderCurrentDirection = new Vector3f();
    public final Quaternionf renderSegmentRotation = new Quaternionf();
    public final Quaternionf renderFractionRotation = new Quaternionf();
    public final Vector3f gravityDirection = new Vector3f();
    public final Vector3f windDirection = new Vector3f();
    public final Vector3f windForce = new Vector3f();
    public final Vector3f startAnchor = new Vector3f();
    public final Quaternionf startAnchorRotation = new Quaternionf();
    public final Vector3f stepAnchor = new Vector3f();
    public final Quaternionf stepAnchorRotation = new Quaternionf();
    public final Vector3f velocity = new Vector3f();
    public final Vector3f poseDirection = new Vector3f();
    public final Vector3f currentDirection = new Vector3f();
    public final Vector3f tipDirection = new Vector3f();
    public final Vector3f lengthDirection = new Vector3f();
    public final Quaternionf constraintParentRotation = new Quaternionf();
    public final Quaternionf constraintInverseParent = new Quaternionf();
    public final Vector3f constraintDesiredWorld = new Vector3f();
    public final Vector3f constraintDesiredLocal = new Vector3f();
    public final Vector3f constraintDirection = new Vector3f();
    public final BlockPos.MutableBlockPos collisionBlock = new BlockPos.MutableBlockPos();
    public float[] stiffnessSteps = new float[0];
    public float stiffnessBase = Float.NaN;

    /** Captures persistent simulation history; solver scratch remains local to the new state. */
    public ChainState copy()
    {
        ChainState copy = new ChainState();

        copy.lastAge = this.lastAge;
        copy.anchor.set(this.anchor);
        copy.anchorRotation.set(this.anchorRotation);
        copy.renderAlpha = this.renderAlpha;
        copy.pos = copyPoints(this.pos);
        copy.prev = copyPoints(this.prev);
        copy.settledLocal = copyPoints(this.settledLocal);
        copy.settledPrevLocal = copyPoints(this.settledPrevLocal);
        copy.render = copyPoints(this.render);
        copy.poseLocal = copyPoints(this.poseLocal);
        copy.stiffnessSteps = this.stiffnessSteps.clone();
        copy.stiffnessBase = this.stiffnessBase;

        return copy;
    }

    private static Vector3f[] copyPoints(Vector3f[] points)
    {
        if (points == null)
        {
            return null;
        }

        Vector3f[] copy = new Vector3f[points.length];

        for (int i = 0; i < points.length; i++)
        {
            copy[i] = new Vector3f(points[i]);
        }

        return copy;
    }
}
