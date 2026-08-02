package mchorse.bbs_mod.ui.utils;

/**
 * The bone the animator is currently working on, shared by every editor tab
 * that lists bones (pose, IK, physics, constraints) and kept for the session.
 *
 * <p>Two things depend on it. First, PERSISTENCE: clicking a body part in the
 * viewport rebuilds the whole form editor from scratch (see {@code
 * UIFormEditor.pickFormBone}), so a panel's own field cannot survive — the
 * freshly built panel reads the bone back from here instead of dropping the
 * selection or snapping to the first bone in the list. Second, CONTINUITY
 * ACROSS TABS: posing a hand, then opening IK or physics, lands on that same
 * hand rather than wherever that tab was left.
 */
public final class PickedBone
{
    private static String bone = "";

    private PickedBone()
    {
    }

    public static String get()
    {
        return bone;
    }

    public static void set(String picked)
    {
        bone = picked == null ? "" : picked;
    }
}
