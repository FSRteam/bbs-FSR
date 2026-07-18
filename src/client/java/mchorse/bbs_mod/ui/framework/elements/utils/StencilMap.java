package mchorse.bbs_mod.ui.framework.elements.utils;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.Pair;

import java.util.HashMap;
import java.util.Map;

public class StencilMap
{
    public int objectIndex;
    public Map<Integer, Pair<Form, String>> indexMap = new HashMap<>();
    public boolean increment = true;

    public void setIncrement(boolean increment)
    {
        this.increment = increment;
    }

    public void setup()
    {
        /* Gizmo now owns move, scale and rotate handles in the complete
         * [STENCIL_X, STENCIL_MAX] range. Form and bone ids must start after
         * that range; starting at the old value 7 makes the first bones look
         * like scale/rotate handles and lets GizmoInteraction steal clicks. */
        this.objectIndex = Gizmo.STENCIL_MAX + 1;

        /* Seed every reserved handle id as a non-form entry. This keeps the
         * CPU map identical to the ids rendered by Gizmo#renderStencil. */
        this.indexMap.clear();

        for (Gizmo.Handle handle : Gizmo.Handle.values())
        {
            this.indexMap.put(handle.index, new Pair<>(null, handle.name().toLowerCase()));
        }
    }

    public void addPicking(Form form)
    {
        this.addPicking(form, "");
    }

    public void addPicking(Form form, String bone)
    {
        if (this.increment)
        {
            this.indexMap.put(this.objectIndex, new Pair<>(form, bone));

            this.objectIndex += 1;
        }
        else
        {
            this.indexMap.put(this.objectIndex, new Pair<>(form, ""));
        }
    }

}
