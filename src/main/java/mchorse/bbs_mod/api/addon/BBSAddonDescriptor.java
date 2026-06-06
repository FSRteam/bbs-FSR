package mchorse.bbs_mod.api.addon;

import mchorse.bbs_mod.api.BBSApiVersion;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class BBSAddonDescriptor
{
    private final String addonId;
    private final String displayName;
    private final String addonVersion;
    private final String apiVersion;
    private final BBSAddonSide side;
    private final BBSAddonCompatPolicy compatPolicy;
    private final Set<BBSAddonCapability> capabilities;
    private final Set<String> requiredMods;
    private final Set<String> optionalMods;
    private final Set<String> namespaces;

    private BBSAddonDescriptor(Builder builder)
    {
        this.addonId = builder.addonId;
        this.displayName = builder.displayName == null || builder.displayName.isBlank() ? builder.addonId : builder.displayName;
        this.addonVersion = builder.addonVersion == null || builder.addonVersion.isBlank() ? "0.0.0" : builder.addonVersion;
        this.apiVersion = builder.apiVersion == null || builder.apiVersion.isBlank() ? BBSApiVersion.CURRENT : builder.apiVersion;
        this.side = builder.side;
        this.compatPolicy = builder.compatPolicy;
        this.capabilities = immutableEnumSet(builder.capabilities);
        this.requiredMods = immutableSet(builder.requiredMods);
        this.optionalMods = immutableSet(builder.optionalMods);

        LinkedHashSet<String> namespaceCopy = new LinkedHashSet<>(builder.namespaces);

        if (this.addonId != null && !this.addonId.isBlank() && namespaceCopy.isEmpty())
        {
            namespaceCopy.add(this.addonId);
        }

        this.namespaces = immutableSet(namespaceCopy);
    }

    public static Builder builder(String addonId)
    {
        return new Builder(addonId);
    }

    public String addonId()
    {
        return this.addonId;
    }

    public String displayName()
    {
        return this.displayName;
    }

    public String addonVersion()
    {
        return this.addonVersion;
    }

    public String apiVersion()
    {
        return this.apiVersion;
    }

    public BBSAddonSide side()
    {
        return this.side;
    }

    public BBSAddonCompatPolicy compatPolicy()
    {
        return this.compatPolicy;
    }

    public Set<BBSAddonCapability> capabilities()
    {
        return this.capabilities;
    }

    public Set<String> requiredMods()
    {
        return this.requiredMods;
    }

    public Set<String> optionalMods()
    {
        return this.optionalMods;
    }

    public Set<String> namespaces()
    {
        return this.namespaces;
    }

    private static Set<String> immutableSet(Collection<String> values)
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Set<BBSAddonCapability> immutableEnumSet(EnumSet<BBSAddonCapability> values)
    {
        EnumSet<BBSAddonCapability> copy = values.isEmpty()
            ? EnumSet.noneOf(BBSAddonCapability.class)
            : EnumSet.copyOf(values);

        return Collections.unmodifiableSet(copy);
    }

    public static final class Builder
    {
        private final String addonId;
        private String displayName;
        private String addonVersion;
        private String apiVersion = BBSApiVersion.CURRENT;
        private BBSAddonSide side = BBSAddonSide.COMMON;
        private BBSAddonCompatPolicy compatPolicy = BBSAddonCompatPolicy.ALLOW_LEGACY_COMPAT;
        private final EnumSet<BBSAddonCapability> capabilities = EnumSet.noneOf(BBSAddonCapability.class);
        private final LinkedHashSet<String> requiredMods = new LinkedHashSet<>();
        private final LinkedHashSet<String> optionalMods = new LinkedHashSet<>();
        private final LinkedHashSet<String> namespaces = new LinkedHashSet<>();

        private Builder(String addonId)
        {
            this.addonId = Objects.requireNonNull(addonId, "addonId");
        }

        public Builder displayName(String displayName)
        {
            this.displayName = displayName;
            return this;
        }

        public Builder addonVersion(String addonVersion)
        {
            this.addonVersion = addonVersion;
            return this;
        }

        public Builder apiVersion(String apiVersion)
        {
            this.apiVersion = apiVersion;
            return this;
        }

        public Builder side(BBSAddonSide side)
        {
            this.side = Objects.requireNonNull(side, "side");
            return this;
        }

        public Builder compatPolicy(BBSAddonCompatPolicy compatPolicy)
        {
            this.compatPolicy = Objects.requireNonNull(compatPolicy, "compatPolicy");
            return this;
        }

        public Builder capability(BBSAddonCapability capability)
        {
            this.capabilities.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        public Builder capabilities(Collection<BBSAddonCapability> capabilities)
        {
            if (capabilities != null)
            {
                for (BBSAddonCapability capability : capabilities)
                {
                    this.capability(capability);
                }
            }

            return this;
        }

        public Builder requiredMod(String modId)
        {
            addTrimmed(this.requiredMods, modId);
            return this;
        }

        public Builder optionalMod(String modId)
        {
            addTrimmed(this.optionalMods, modId);
            return this;
        }

        public Builder namespace(String namespace)
        {
            addTrimmed(this.namespaces, namespace);
            return this;
        }

        public BBSAddonDescriptor build()
        {
            return new BBSAddonDescriptor(this);
        }

        private static void addTrimmed(Set<String> target, String value)
        {
            if (value != null && !value.isBlank())
            {
                target.add(value.trim());
            }
        }
    }
}
