package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.api.addon.BBSAddonProtocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local first-wins index shared by the Addon v1 and API 2.0
 * collectors. The lifecycle implementations keep their own records, while
 * this index owns the cross-protocol addon id boundary.
 */
public final class BBSAddonIdentityRegistry
{
    private final Map<String, Owner> owners = new LinkedHashMap<>();

    public synchronized Owner owner(String addonId)
    {
        return this.owners.get(normalize(addonId));
    }

    /**
     * Claims an addon id. A {@code null} return value means the incoming owner
     * won; otherwise the returned owner remains authoritative.
     */
    public synchronized Owner claim(String addonId, BBSAddonProtocol protocol, String source)
    {
        String key = normalize(addonId);
        Owner existing = this.owners.get(key);

        if (existing != null)
        {
            return existing;
        }

        this.owners.put(key, new Owner(protocol, normalizeSource(source)));

        return null;
    }

    private static String normalize(String addonId)
    {
        return addonId == null ? "<unknown>" : addonId.trim();
    }

    private static String normalizeSource(String source)
    {
        return source == null || source.isBlank() ? "<unknown>" : source;
    }

    public static final class Owner
    {
        private final BBSAddonProtocol protocol;
        private final String source;

        private Owner(BBSAddonProtocol protocol, String source)
        {
            this.protocol = Objects.requireNonNull(protocol, "protocol");
            this.source = source;
        }

        public BBSAddonProtocol protocol()
        {
            return this.protocol;
        }

        public String source()
        {
            return this.source;
        }

        @Override
        public String toString()
        {
            return this.protocol + ":" + this.source;
        }
    }
}
