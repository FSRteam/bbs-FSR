package mchorse.bbs_mod.forms.renderers;

/**
 * Describes what coordinate space {@link FormRenderingContext#stack} carries.
 * Renderers which also keep world-space runtime state must use this value
 * instead of inferring the space from a global render pass flag.
 */
public enum FormRenderSpace
{
    /** A world stack whose translation is relative to the active camera. */
    CAMERA_RELATIVE_WORLD,
    /** A vanilla entity/model-block local stack with an absolute semantic world stack. */
    ENTITY_LOCAL,
    /** A preview, inventory, held-item, or other view-local stack. */
    UI_LOCAL;

    public static FormRenderSpace forType(FormRenderType type)
    {
        return type != null && type.hasWorldHost()
            ? ENTITY_LOCAL
            : UI_LOCAL;
    }

    public boolean isWorld()
    {
        return this != UI_LOCAL;
    }
}
