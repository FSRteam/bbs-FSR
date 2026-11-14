package mchorse.bbs_mod.api.client.editor;

import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import org.joml.Matrix4f;

/** Implement on an additional form panel to edit its own object through BBS's gizmo.
 * Return null when there is no editable selection. Matrices are in the preview's model space.
 * State editing takes precedence; gestures and undo remain owned by UIPropTransform. */
public interface FormEditorTool
{
    UIPropTransform getGizmoTransform();
    Matrix4f getGizmoOrigin(float transition, TransformSpace space);
}
