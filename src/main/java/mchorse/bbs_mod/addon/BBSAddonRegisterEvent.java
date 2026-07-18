package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.BBSAddonMod;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Mod Bus event published once during core init to allow addons to register.
 */
public final class BBSAddonRegisterEvent extends Event implements IModBusEvent
{
    private final BBSAddonCollector collector;

    public BBSAddonRegisterEvent(BBSAddonCollector collector)
    {
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    /**
     * Register an addon instance with its addonId. First wins, later duplicates are rejected.
     */
    public void register(String addonId, BBSAddonMod addon)
    {
        this.collector.register(addonId, addon);
    }

    /**
     * Lazy supplier overload to defer addon instantiation by the caller.
     */
    public void register(String addonId, Supplier<? extends BBSAddonMod> supplier)
    {
        this.collector.register(addonId, supplier);
    }
}
