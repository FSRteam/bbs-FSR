package mchorse.bbs_mod.cubic.ik;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Historical calibration scratch for the compatibility overload; never retained by the runtime. */
@Deprecated
final class ModelIKChainWorkspace
{
    /* Target-rotation bind state: the target's orientation expressed in the root's
     * parent frame at bind time, and the per-frame local/world deltas off it. */
    final Quaternionf bindTargetRotationLocal = new Quaternionf();
    final Quaternionf currentTargetRotationLocal = new Quaternionf();
    final Quaternionf targetRotationDeltaLocal = new Quaternionf();
    final Quaternionf targetRotationDeltaWorld = new Quaternionf();

    /* Position bind state, all in the root's parent frame: where the target and the
     * pole were when the baseline was taken, where they are now, and the world-space
     * delta the chain rides. */
    final Vector3f bindTargetLocal = new Vector3f();
    final Vector3f bindPoleLocal = new Vector3f();
    final Vector3f currentTargetLocal = new Vector3f();
    final Vector3f currentPoleLocal = new Vector3f();
    final Vector3f controllerDeltaLocal = new Vector3f();
    final Vector3f controllerDeltaWorld = new Vector3f();

    /** Whether the baseline above is live; false whenever the chain is off. */
    boolean bindingValid;

    /** Whether a pole baseline was established (a pole bone existed at bind time). */
    boolean bindPoleValid;

    /** Whether the pole was enabled when the baseline was taken, to detect the toggle. */
    boolean boundPoleEnabled;
}
