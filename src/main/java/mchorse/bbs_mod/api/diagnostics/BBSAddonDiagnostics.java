package mchorse.bbs_mod.api.diagnostics;

import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.addon.BBSAddonProtocol;
import mchorse.bbs_mod.api.addon.BBSAddonState;

import java.util.Collections;
import java.util.List;

public final class BBSAddonDiagnostics
{
    private final String addonId;
    private final String displayName;
    private final String addonVersion;
    private final String apiVersion;
    private final BBSAddonProtocol protocol;
    private final BBSAddonState state;
    private final BBSAddonPhase failedPhase;
    private final String lastErrorClass;
    private final List<String> acceptedRegistrations;
    private final List<String> rejectedRegistrations;
    private final List<String> warnings;
    private final List<String> errors;

    public BBSAddonDiagnostics(
        String addonId,
        String displayName,
        String addonVersion,
        String apiVersion,
        BBSAddonProtocol protocol,
        BBSAddonState state,
        BBSAddonPhase failedPhase,
        String lastErrorClass,
        List<String> acceptedRegistrations,
        List<String> rejectedRegistrations,
        List<String> warnings,
        List<String> errors
    )
    {
        this.addonId = addonId;
        this.displayName = displayName;
        this.addonVersion = addonVersion;
        this.apiVersion = apiVersion;
        this.protocol = protocol;
        this.state = state;
        this.failedPhase = failedPhase;
        this.lastErrorClass = lastErrorClass;
        this.acceptedRegistrations = immutableList(acceptedRegistrations);
        this.rejectedRegistrations = immutableList(rejectedRegistrations);
        this.warnings = immutableList(warnings);
        this.errors = immutableList(errors);
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

    public BBSAddonProtocol protocol()
    {
        return this.protocol;
    }

    public BBSAddonState state()
    {
        return this.state;
    }

    public BBSAddonPhase failedPhase()
    {
        return this.failedPhase;
    }

    public String lastErrorClass()
    {
        return this.lastErrorClass;
    }

    public List<String> acceptedRegistrations()
    {
        return this.acceptedRegistrations;
    }

    public List<String> rejectedRegistrations()
    {
        return this.rejectedRegistrations;
    }

    public List<String> warnings()
    {
        return this.warnings;
    }

    public List<String> errors()
    {
        return this.errors;
    }

    private static List<String> immutableList(List<String> values)
    {
        return Collections.unmodifiableList(values == null ? List.of() : List.copyOf(values));
    }
}
