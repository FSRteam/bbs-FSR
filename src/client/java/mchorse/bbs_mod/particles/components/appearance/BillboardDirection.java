package mchorse.bbs_mod.particles.components.appearance;

/** Source of the vector used by the direction_x/y/z billboard modes. */
public enum BillboardDirection
{
    DERIVE_FROM_VELOCITY("derive_from_velocity"), CUSTOM_DIRECTION("custom_direction");

    public final String id;

    public static BillboardDirection fromString(String string)
    {
        /* Existing FSR particle files used the shorter custom spelling. */
        if ("custom".equals(string))
        {
            return CUSTOM_DIRECTION;
        }

        for (BillboardDirection direction : values())
        {
            if (direction.id.equals(string))
            {
                return direction;
            }
        }

        return DERIVE_FROM_VELOCITY;
    }

    private BillboardDirection(String id)
    {
        this.id = id;
    }
}
