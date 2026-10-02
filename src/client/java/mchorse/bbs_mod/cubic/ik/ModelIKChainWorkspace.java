package mchorse.bbs_mod.cubic.ik;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-chain scratch for {@link ModelIKDlsApplier}, the production IK applier.
 *
 * <p>It holds ONE chain's calibration baseline across frames: the target's and the
 * pole's rest-relative bind positions, and the deltas derived from them. The bind
 * baseline is what makes a film-driven controller move the chain by its DELTA
 * rather than teleport it onto the controller — see
 * {@link ModelIKDlsApplier#apply}. {@code bindingValid} and {@code bindPoleValid}
 * are the lifecycle flags: they open on the first solve, drop when the chain goes
 * off (disabled, weightless, its target frame missing, or its pole toggled), and
 * re-open on the next live solve, so a chain that is turned back on rebases
 * instead of snapping to a stale calibration.
 *
 * <p>Extracted from the deleted {@code ModelIKApplier} (the dead direct-solver
 * applier, removed in B8·R5-0) because this is the only part of it the production
 * path still uses. Only the members the applier actually reads live here; the
 * deleted class's own solver scratch — its positions, frames, bend-hint state,
 * solver workspace and limit arrays — went with it.
 */
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
