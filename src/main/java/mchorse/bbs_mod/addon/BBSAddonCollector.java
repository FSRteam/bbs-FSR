package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory collector for addon registrations posted through {@link BBSAddonRegisterEvent}.
 *
 * Rules (protocol v1):
 * - Reject blank addonId or null addon and log a warning.
 * - First registration wins for the same addonId; later ones are rejected and warned once.
 * - Never throw to avoid breaking core startup.
 */
public final class BBSAddonCollector
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonCollector.class);

    private final Map<String, BBSAddonMod> addons = new LinkedHashMap<>();
    private final Map<String, Boolean> warnOnce = new LinkedHashMap<>();
    private boolean registrationOpen = true;

    /**
     * Registers an addon if the id is unique. Returns true when accepted.
     */
    public synchronized boolean register(String addonId, BBSAddonMod addon)
    {
        if (addonId == null || addonId.isBlank())
        {
            LOGGER.warn("[bbs-addon] rejected registration: addonId is blank");
            return false;
        }

        if (addon == null)
        {
            LOGGER.warn("[bbs-addon] rejected registration: addon instance is null for '{}'", addonId);
            return false;
        }

        String key = addonId.trim();

        if (!this.registrationOpen)
        {
            LOGGER.warn("[bbs-addon] rejected registration for '{}' because the window is closed", key);
            return false;
        }

        if (this.addons.containsKey(key))
        {
            // Reject later registrations and keep the first one.
            BBSAddonMod existing = this.addons.get(key);
            String existingSource = existing == null ? "<unknown>" : existing.getClass().getName();
            String incomingSource = addon.getClass().getName();

            warnOnce(key, () -> LOGGER.warn(
                "[bbs-addon] duplicate addonId '{}', keeping first and rejecting later one (kept='{}', rejected='{}')",
                key,
                existingSource,
                incomingSource
            ));
            return false;
        }

        this.addons.put(key, addon);
        return true;
    }

    /**
     * Bridges all collected addons into the internal BBS EventBus.
     */
    public void bridgeTo(EventBus bus)
    {
        if (bus == null)
        {
            return;
        }

        for (BBSAddonMod addon : this.addons.values())
        {
            try
            {
                bus.register(addon);
            }
            catch (Exception e)
            {
                LOGGER.error("[bbs-addon] failed to register addon into internal bus", e);
            }
        }
    }

    public Collection<BBSAddonMod> getAddons()
    {
        return Collections.unmodifiableCollection(this.addons.values());
    }

    public synchronized void closeRegistrationWindow()
    {
        if (!this.registrationOpen)
        {
            return;
        }

        this.registrationOpen = false;
        LOGGER.info("[bbs-addon] registration window closed after collecting {} addon(s)", this.addons.size());
    }

    private void warnOnce(String key, Runnable action)
    {
        if (this.warnOnce.putIfAbsent(key, Boolean.TRUE) == null)
        {
            action.run();
        }
    }
}
