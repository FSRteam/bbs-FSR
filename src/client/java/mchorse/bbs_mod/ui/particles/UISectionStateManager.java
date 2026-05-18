package mchorse.bbs_mod.ui.particles;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the collapsed/expanded state of particle editor sections.
 * States persist within the session (static HashMap).
 * Default state is expanded (collapsed = false).
 */
public class UISectionStateManager
{
    private static final Map<String, Boolean> STATES = new HashMap<>();

    public static void clearAll()
    {
        STATES.clear();
    }

    public static boolean isCollapsed(String id)
    {
        Boolean state = STATES.get(id);

        if (state == null)
        {
            state = false; // default: expanded
            STATES.put(id, state);
        }

        return state;
    }

    public static void setCollapsed(String id, boolean collapsed)
    {
        STATES.put(id, collapsed);
    }

    /**
     * Only adds a state to the map if the id isn't already present.
     * Used to register default states without overriding user choices.
     */
    public static void setDefaultState(String id, boolean collapsed)
    {
        if (!STATES.containsKey(id))
        {
            STATES.put(id, collapsed);
        }
    }
}