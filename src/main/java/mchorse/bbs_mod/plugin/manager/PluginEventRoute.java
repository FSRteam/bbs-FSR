package mchorse.bbs_mod.plugin.manager;

import java.util.Objects;

record PluginEventRoute(Class<?> eventType, int ordinal)
{
    PluginEventRoute
    {
        Objects.requireNonNull(eventType, "eventType");

        if (ordinal < 0)
        {
            throw new IllegalArgumentException("event route ordinal cannot be negative");
        }
    }
}
