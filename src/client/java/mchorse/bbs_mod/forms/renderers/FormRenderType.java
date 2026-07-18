package mchorse.bbs_mod.forms.renderers;

import net.minecraft.world.item.ItemDisplayContext;

public enum FormRenderType
{
    MODEL_BLOCK, ENTITY, ITEM_FP, ITEM_TP, ITEM_INVENTORY, ITEM, PREVIEW;

    public static FormRenderType fromModelMode(ItemDisplayContext mode)
    {
        if (mode != null && mode.firstPerson())
        {
            return ITEM_FP;
        }
        else if (mode == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || mode == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
        {
            return ITEM_TP;
        }
        else if (mode == ItemDisplayContext.GUI)
        {
            return ITEM_INVENTORY;
        }

        /* Ground, fixed, head and NONE are still item-local renders. Treating
         * them as ENTITY gives procedural simulation a world host it does not
         * have and lets unrelated display modes share world semantics. */
        return ITEM;
    }

    public boolean hasWorldHost()
    {
        return this == ENTITY || this == MODEL_BLOCK;
    }
}
