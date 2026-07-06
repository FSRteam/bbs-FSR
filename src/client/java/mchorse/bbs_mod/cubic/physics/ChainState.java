package mchorse.bbs_mod.cubic.physics;

import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    public Vector3f[] settled;
    public Vector3f[] settledPrev;
    public Vector3f[] render;

    /** The animated pose the chain springs toward, stored relative to the live anchor frame. */
    public Vector3f[] poseLocal;
}
