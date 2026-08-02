package mchorse.bbs_mod.ui.utils.pose;

import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;

import java.util.List;
import java.util.function.Consumer;

/**
 * Bone list for {@link UIPoseEditor}: the hierarchy-aware bone tree with multi-selection
 * on top (Shift = range selection, Ctrl = toggle). The pose editor feeds the hierarchy
 * via {@link UIBoneTreeList#setHierarchy} and keeps refilling the plain string list
 * itself (see {@link UIBoneList#filter}).
 */
public class UIPoseBoneStringList extends UIBoneTreeList
{
    public UIPoseBoneStringList(Consumer<List<String>> callback)
    {
        super(callback);

        this.multi();
    }
}
