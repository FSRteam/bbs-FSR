package mchorse.bbs_mod.camera.clips;

import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.camera.clips.misc.SubtitleClip;
import mchorse.bbs_mod.camera.clips.modifiers.AngleClip;
import mchorse.bbs_mod.camera.clips.modifiers.DollyZoomClip;
import mchorse.bbs_mod.camera.clips.modifiers.DragClip;
import mchorse.bbs_mod.camera.clips.modifiers.LookClip;
import mchorse.bbs_mod.camera.clips.modifiers.MathClip;
import mchorse.bbs_mod.camera.clips.modifiers.OrbitClip;
import mchorse.bbs_mod.camera.clips.modifiers.RemapperClip;
import mchorse.bbs_mod.camera.clips.modifiers.ShakeClip;
import mchorse.bbs_mod.camera.clips.modifiers.TrackerClip;
import mchorse.bbs_mod.camera.clips.modifiers.TranslateClip;
import mchorse.bbs_mod.camera.clips.overwrite.DollyClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.clips.overwrite.PathClip;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.Set;

/** Exact built-in types are trusted; subclasses must declare the pose contract. */
public final class CameraPosePolicy
{
    private static final Set<Class<?>> BUILT_INS = Set.of(
        IdleClip.class, DollyClip.class, PathClip.class, KeyframeClip.class,
        AngleClip.class, DollyZoomClip.class, DragClip.class, LookClip.class,
        MathClip.class, OrbitClip.class, RemapperClip.class, ShakeClip.class,
        TrackerClip.class, TranslateClip.class
    );

    private CameraPosePolicy()
    {}

    public static boolean allows(Clip clip)
    {
        return clip instanceof CameraClip
            && !(clip instanceof AudioClip || clip instanceof CurveClip || clip instanceof SubtitleClip)
            && (BUILT_INS.contains(clip.getClass()) || clip instanceof CameraPoseClip);
    }
}
