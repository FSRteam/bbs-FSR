package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

record BBSRenderSurfaceCapturePlan(
    Set<BBSRenderSurfaceKind> kinds,
    int maxWidth,
    int maxHeight,
    int framesPerSecond,
    int jpegQuality
)
{
    BBSRenderSurfaceCapturePlan
    {
        kinds = Collections.unmodifiableSet(EnumSet.copyOf(kinds));
    }
}
