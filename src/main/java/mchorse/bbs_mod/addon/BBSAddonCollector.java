package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory collector for addon registrations posted through {@link BBSAddonRegisterEvent}.
 *
 * Rules (protocol v1):
 * - Reject blank addonId or null addon and log a warning.
 * - First registration wins for the same addonId; later ones are rejected and warned once.
 * - External constructor-time registrations are accepted only before the internal bridge.
 * - Never throw to avoid breaking core startup.
 */
public final class BBSAddonCollector
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonCollector.class);

    private final Map<String, BBSAddonMod> addons = new LinkedHashMap<>();
    private final Map<String, Boolean> warnOnce = new LinkedHashMap<>();
    private boolean registrationOpen = true;
    private boolean externalRegistrationOpen = true;

    /**
     * Registers an addon if the id is unique. Returns true when accepted.
     */
    public synchronized boolean register(String addonId, BBSAddonMod addon)
    {
        return this.register(addonId, addon, false, "event");
    }

    /**
     * Registers an addon that came from the public external-addon API.
     *
     * <p>External NeoForge mods can be constructed after BBS' own construct
     * event closes the event-only window, but only before common setup bridges
     * collected addons into the internal BBS event bus. After that bridge, late
     * v1 registrations are rejected instead of being half-attached to future
     * events without receiving startup registration events.</p>
     */
    public synchronized boolean registerExternal(String addonId, BBSAddonMod addon)
    {
        return this.register(addonId, addon, true, "external");
    }

    private boolean register(String addonId, BBSAddonMod addon, boolean allowClosedWindow, String source)
    {
        if (addonId == null || addonId.isBlank())
        {
            LOGGER.warn("[bbs-addon] rejected {} registration: addonId is blank", source);
            return false;
        }

        if (addon == null)
        {
            LOGGER.warn("[bbs-addon] rejected {} registration: addon instance is null for '{}'", source, addonId);
            return false;
        }

        String key = addonId.trim();

        if (allowClosedWindow)
        {
            if (!this.externalRegistrationOpen)
            {
                LOGGER.warn("[bbs-addon] rejected {} registration for '{}' because the external bridge window is closed", source, key);
                return false;
            }
        }
        else if (!this.registrationOpen)
        {
            LOGGER.warn("[bbs-addon] rejected {} registration for '{}' because the event window is closed", source, key);
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
        LOGGER.info("[bbs-addon] accepted {} registration for '{}' ({}) while eventWindowOpen={} externalWindowOpen={}",
            source,
            key,
            addon.getClass().getName(),
            this.registrationOpen,
            this.externalRegistrationOpen);

        return true;
    }

    /**
     * Bridges all collected addons into the internal BBS EventBus.
     */
    public synchronized void bridgeTo(EventBus bus)
    {
        if (bus == null)
        {
            return;
        }

        for (Map.Entry<String, BBSAddonMod> entry : this.addons.entrySet())
        {
            try
            {
                BBSAddonMod addon = entry.getValue();

                LOGGER.info("[bbs-addon] bridging '{}' ({}) into internal BBS event bus",
                    entry.getKey(),
                    addon == null ? "<null>" : addon.getClass().getName());
                bus.register(addon);
            }
            catch (Exception e)
            {
                LOGGER.error("[bbs-addon] failed to register addon '{}' into internal bus", entry.getKey(), e);
            }
        }
    }

    public synchronized Collection<BBSAddonMod> getAddons()
    {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(this.addons).values());
    }

    public synchronized Map<String, BBSAddonMod> getAddonMap()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.addons));
    }

    public synchronized Set<String> getAddonIds()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.addons.keySet()));
    }

    public synchronized int size()
    {
        return this.addons.size();
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

    public synchronized void closeExternalRegistrationWindow()
    {
        if (!this.externalRegistrationOpen)
        {
            return;
        }

        this.externalRegistrationOpen = false;
        LOGGER.info("[bbs-addon] external registration window closed after bridging {} addon(s)", this.addons.size());
    }

    private void warnOnce(String key, Runnable action)
    {
        if (this.warnOnce.putIfAbsent(key, Boolean.TRUE) == null)
        {
            action.run();
        }
    }
}
