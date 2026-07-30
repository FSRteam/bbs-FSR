package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.addon.BBSAddonProtocol;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

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

    private final BBSAddonIdentityRegistry identities;
    private final Map<String, BBSAddonMod> addons = new LinkedHashMap<>();
    private final Map<String, Boolean> warnOnce = new LinkedHashMap<>();
    private final List<RegistrationDiagnostic> registrationDiagnostics = new ArrayList<>();
    private boolean registrationOpen = true;
    private boolean externalRegistrationOpen = true;

    public BBSAddonCollector()
    {
        this(new BBSAddonIdentityRegistry());
    }

    public BBSAddonCollector(BBSAddonIdentityRegistry identities)
    {
        this.identities = identities == null ? new BBSAddonIdentityRegistry() : identities;
    }

    /**
     * Registers an addon if the id is unique. Returns true when accepted.
     */
    public synchronized boolean register(String addonId, BBSAddonMod addon)
    {
        return this.registerInstance(addonId, addon, false, "event");
    }

    /**
     * Lazy event-window registration. Window and duplicate checks happen
     * before invoking third-party code.
     */
    public synchronized boolean register(String addonId, Supplier<? extends BBSAddonMod> supplier)
    {
        return this.registerSupplier(addonId, supplier, false, "event-supplier");
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
        return this.registerInstance(addonId, addon, true, "external");
    }

    synchronized boolean registerExternal(String addonId, BBSAddonMod addon, String source)
    {
        return this.registerInstance(addonId, addon, true, source == null || source.isBlank() ? "external" : source);
    }

    /**
     * Lazy public registration. In addition to isolating supplier failures,
     * this keeps a rejected duplicate from constructing or running at all.
     */
    public synchronized boolean registerExternal(String addonId, Supplier<? extends BBSAddonMod> supplier)
    {
        return this.registerSupplier(addonId, supplier, true, "external-supplier");
    }

    private boolean registerSupplier(String addonId, Supplier<? extends BBSAddonMod> supplier, boolean external, String source)
    {
        String key = this.validateWindowAndId(addonId, external, source);

        if (key == null)
        {
            return false;
        }

        if (supplier == null)
        {
            String message = "addon supplier is null";

            this.warn(key, source, message);
            LOGGER.warn("[bbs-addon] rejected {} registration for '{}': {}", source, key, message);

            return false;
        }

        String supplierSource = source + ":" + supplier.getClass().getName();
        BBSAddonIdentityRegistry.Owner owner = this.identities.owner(key);

        if (owner != null)
        {
            return this.rejectDuplicate(key, supplierSource, owner);
        }

        BBSAddonMod addon;

        try
        {
            addon = supplier.get();
        }
        catch (Exception | LinkageError e)
        {
            String message = "addon supplier failed during " + BBSAddonPhase.DISCOVER;

            this.error(key, supplierSource, message, e);
            LOGGER.error("[bbs-addon] failed to construct {} addon '{}' during {}", supplierSource, key, BBSAddonPhase.DISCOVER, e);

            return false;
        }

        return this.acceptInstance(key, addon, source);
    }

    private boolean registerInstance(String addonId, BBSAddonMod addon, boolean external, String source)
    {
        String key = this.validateWindowAndId(addonId, external, source);

        if (key == null)
        {
            return false;
        }

        return this.acceptInstance(key, addon, source);
    }

    private boolean acceptInstance(String key, BBSAddonMod addon, String source)
    {
        if (addon == null)
        {
            this.warn(key, source, "addon instance is null");
            LOGGER.warn("[bbs-addon] rejected {} registration: addon instance is null for '{}'", source, key);
            return false;
        }

        String incomingSource = source + ":" + addon.getClass().getName();
        BBSAddonIdentityRegistry.Owner owner = this.identities.owner(key);

        if (owner != null)
        {
            return this.rejectDuplicate(key, incomingSource, owner);
        }

        BBSAddonIdentityRegistry.Owner racedOwner = this.identities.claim(key, BBSAddonProtocol.API1_REGISTERED, incomingSource);

        if (racedOwner != null)
        {
            return this.rejectDuplicate(key, incomingSource, racedOwner);
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

    private String validateWindowAndId(String addonId, boolean external, String source)
    {
        if (addonId == null || addonId.isBlank())
        {
            this.warn("<unknown>", source, "addonId is blank");
            LOGGER.warn("[bbs-addon] rejected {} registration: addonId is blank", source);

            return null;
        }

        String key = addonId.trim();

        if (external)
        {
            if (!this.externalRegistrationOpen)
            {
                this.warn(key, source, "external bridge window is closed");
                LOGGER.warn("[bbs-addon] rejected {} registration for '{}' because the external bridge window is closed", source, key);

                return null;
            }
        }
        else if (!this.registrationOpen)
        {
            this.warn(key, source, "event window is closed");
            LOGGER.warn("[bbs-addon] rejected {} registration for '{}' because the event window is closed", source, key);

            return null;
        }

        return key;
    }

    private boolean rejectDuplicate(String key, String incomingSource, BBSAddonIdentityRegistry.Owner owner)
    {
        String message = "duplicate addonId; kept=" + owner + ", rejected=" + incomingSource;

        this.warn(key, incomingSource, message);
        warnOnce(key, () -> LOGGER.warn(
            "[bbs-addon] duplicate addonId '{}', keeping first and rejecting later one (kept='{}', rejected='{}')",
            key,
            owner,
            incomingSource
        ));

        return false;
    }

    /**
     * Bridges all collected addons into the internal BBS EventBus.
     */
    public synchronized void bridgeTo(EventBus bus)
    {
        this.bridgeAndCloseExternalRegistrationWindow(bus);
    }

    /**
     * Closes the external window and takes the bridge snapshot under the same
     * monitor. No external registration can be accepted after the snapshot
     * and miss startup events.
     */
    public synchronized void bridgeAndCloseExternalRegistrationWindow(EventBus bus)
    {
        this.closeExternalRegistrationWindow();

        if (bus == null)
        {
            this.warn("<bridge>", BBSAddonPhase.REGISTER_COMMON, "bridge", "internal event bus is null");
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
            catch (Exception | LinkageError e)
            {
                String addonSource = entry.getValue() == null ? "<null>" : entry.getValue().getClass().getName();

                this.error(entry.getKey(), BBSAddonPhase.REGISTER_COMMON, "bridge:" + addonSource, "failed during internal event-bus bridge", e);
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

    public synchronized List<RegistrationDiagnostic> getRegistrationDiagnostics()
    {
        return Collections.unmodifiableList(new ArrayList<>(this.registrationDiagnostics));
    }

    synchronized void recordExternalDiscoveryFailure(String addonId, String source, Throwable error)
    {
        String key = addonId == null || addonId.isBlank() ? "<unknown>" : addonId.trim();
        String origin = source == null || source.isBlank() ? "external-discovery" : source;

        this.error(key, BBSAddonPhase.DISCOVER, origin, "external addon discovery failed", error);
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
        LOGGER.info("[bbs-addon] external registration window closed for atomic bridge of {} addon(s)", this.addons.size());
    }

    private void warnOnce(String key, Runnable action)
    {
        if (this.warnOnce.putIfAbsent(key, Boolean.TRUE) == null)
        {
            action.run();
        }
    }

    private void warn(String addonId, String source, String message)
    {
        this.warn(addonId, BBSAddonPhase.DISCOVER, source, message);
    }

    private void warn(String addonId, BBSAddonPhase phase, String source, String message)
    {
        this.registrationDiagnostics.add(new RegistrationDiagnostic(
            addonId,
            phase,
            source,
            message,
            null
        ));
    }

    private void error(String addonId, String source, String message, Throwable error)
    {
        this.error(addonId, BBSAddonPhase.DISCOVER, source, message, error);
    }

    private void error(String addonId, BBSAddonPhase phase, String source, String message, Throwable error)
    {
        this.registrationDiagnostics.add(new RegistrationDiagnostic(
            addonId,
            phase,
            source,
            message,
            error == null ? null : error.getClass().getName()
        ));
    }

    public static final class RegistrationDiagnostic
    {
        private final String addonId;
        private final BBSAddonPhase phase;
        private final String source;
        private final String message;
        private final String errorClass;

        private RegistrationDiagnostic(String addonId, BBSAddonPhase phase, String source, String message, String errorClass)
        {
            this.addonId = addonId;
            this.phase = phase;
            this.source = source;
            this.message = message;
            this.errorClass = errorClass;
        }

        public String addonId()
        {
            return this.addonId;
        }

        public BBSAddonPhase phase()
        {
            return this.phase;
        }

        public String source()
        {
            return this.source;
        }

        public String message()
        {
            return this.message;
        }

        public String errorClass()
        {
            return this.errorClass;
        }
    }
}
