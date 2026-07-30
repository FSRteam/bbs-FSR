package mchorse.bbs_mod.cubic.model;

import net.minecraft.world.entity.EquipmentSlot;

public enum ArmorType
{
    HELMET(EquipmentSlot.HEAD, "", 0F, 0F),
    CHEST(EquipmentSlot.CHEST, "torso_lower", 4F, 8F),
    LEGGINGS(EquipmentSlot.LEGS, "torso_lower", 4F, 8F),
    LEFT_ARM(EquipmentSlot.CHEST, "left_elbow", 2F, 6F),
    RIGHT_ARM(EquipmentSlot.CHEST, "right_elbow", 2F, 6F),
    LEFT_LEG(EquipmentSlot.LEGS, "left_knee", 4F, 8F),
    RIGHT_LEG(EquipmentSlot.LEGS, "right_knee", 4F, 8F),
    LEFT_BOOT(EquipmentSlot.FEET, "left_knee", 4F, 8F),
    RIGHT_BOOT(EquipmentSlot.FEET, "right_knee", 4F, 8F);

    public final EquipmentSlot slot;

    /**
     * Bone this slot's armor bends towards when the model has it, used when the model's
     * config doesn't name one explicitly. Bending joints sit as siblings of the armor
     * attachment bones rather than ancestors, so armor stays rigid on the upper bone
     * unless it's skinned towards the joint below. Empty means the slot never bends.
     */
    public final String defaultLowerGroup;

    /**
     * Height range (in model pixels) over which this slot's armor transitions to the lower
     * bone, centered on where the joint sits in vanilla's armor geometry. Arms span y
     * -2..10 with the elbow near 4, while legs and the torso span y 0..12 with their joint
     * near 6, hence the differing defaults. Models can override this in their config.
     */
    public final float defaultBendStart;
    public final float defaultBendEnd;

    ArmorType(EquipmentSlot slot, String defaultLowerGroup, float defaultBendStart, float defaultBendEnd)
    {
        this.slot = slot;
        this.defaultLowerGroup = defaultLowerGroup;
        this.defaultBendStart = defaultBendStart;
        this.defaultBendEnd = defaultBendEnd;
    }
}
