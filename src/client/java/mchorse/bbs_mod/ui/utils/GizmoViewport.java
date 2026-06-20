package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.UIContext;
import org.joml.Matrix4f;

/**
 * Shared contract for viewports that host the transform gizmo.
 */
public interface GizmoViewport
{
    StencilFormFramebuffer getGizmoStencil();

    Matrix4f getGizmoProjection();

    Area getGizmoArea();

    boolean startGizmo(UIContext context, int stencilIndex);

    void pickGizmoForm(UIContext context, Form form, String bone);
}
