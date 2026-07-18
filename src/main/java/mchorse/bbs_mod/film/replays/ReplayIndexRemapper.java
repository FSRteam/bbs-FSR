package mchorse.bbs_mod.film.replays;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the old-index to new-index contract for Replay-like identity lists.
 * This helper deliberately knows nothing about Film, UI or Minecraft
 * registries, so every caller applies the same deletion/reorder semantics.
 */
public final class ReplayIndexRemapper
{
    public static final int NO_TARGET = -1;

    private ReplayIndexRemapper()
    {}

    public static int[] create(List<?> previousOrder, List<?> currentOrder)
    {
        Map<Object, Integer> currentIndices = new IdentityHashMap<>();
        int[] oldToNew = new int[previousOrder.size()];

        Arrays.fill(oldToNew, NO_TARGET);

        for (int i = 0; i < currentOrder.size(); i++)
        {
            currentIndices.putIfAbsent(currentOrder.get(i), i);
        }

        for (int i = 0; i < previousOrder.size(); i++)
        {
            Integer index = currentIndices.get(previousOrder.get(i));

            if (index != null)
            {
                oldToNew[i] = index;
            }
        }

        return oldToNew;
    }

    public static int remap(int oldIndex, int[] oldToNew)
    {
        return oldIndex >= 0 && oldIndex < oldToNew.length
            ? oldToNew[oldIndex]
            : NO_TARGET;
    }

    public static String remap(String oldIndex, int[] oldToNew)
    {
        if (oldIndex == null || oldIndex.isEmpty())
        {
            return "";
        }

        try
        {
            int remapped = remap(Integer.parseInt(oldIndex), oldToNew);

            return remapped == NO_TARGET ? "" : String.valueOf(remapped);
        }
        catch (NumberFormatException e)
        {
            return "";
        }
    }
}
