package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.addon.BBSAddonProtocol;
import mchorse.bbs_mod.api.addon.BBSAddonState;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnosticSink;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnostics;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.api.registry.BBSRegistrationStatus;

import java.util.ArrayList;
import java.util.List;

final class BBSAddonDiagnosticRecord implements BBSAddonDiagnosticSink
{
    private final String addonId;
    private final String displayName;
    private final String addonVersion;
    private final String apiVersion;
    private final BBSAddonProtocol protocol;
    private final List<String> acceptedRegistrations = new ArrayList<>();
    private final List<String> rejectedRegistrations = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private BBSAddonState state;
    private BBSAddonPhase failedPhase;
    private String lastErrorClass;

    BBSAddonDiagnosticRecord(BBSAddonDescriptor descriptor, BBSAddonProtocol protocol, BBSAddonState state)
    {
        this.addonId = descriptor == null ? "<unknown>" : descriptor.addonId();
        this.displayName = descriptor == null ? this.addonId : descriptor.displayName();
        this.addonVersion = descriptor == null ? "<unknown>" : descriptor.addonVersion();
        this.apiVersion = descriptor == null ? "<unknown>" : descriptor.apiVersion();
        this.protocol = protocol;
        this.state = state;
    }

    String addonId()
    {
        return this.addonId;
    }

    BBSAddonState state()
    {
        return this.state;
    }

    void state(BBSAddonState state)
    {
        this.state = state;
    }

    void fail(BBSAddonPhase phase, Throwable throwable)
    {
        this.state = BBSAddonState.FAILED;
        this.failedPhase = phase;
        this.lastErrorClass = throwable == null ? null : throwable.getClass().getName();
        this.error("phase " + phase + " failed", throwable);
    }

    @Override
    public void info(String message)
    {
        if (message != null && !message.isBlank())
        {
            this.warnings.add("INFO: " + message);
        }
    }

    @Override
    public void warn(String message)
    {
        if (message != null && !message.isBlank())
        {
            this.warnings.add(message);
        }
    }

    @Override
    public void error(String message, Throwable error)
    {
        String suffix = error == null ? "" : " (" + error.getClass().getName() + ": " + error.getMessage() + ")";

        this.errors.add((message == null ? "error" : message) + suffix);
    }

    @Override
    public BBSRegistrationResult record(BBSRegistrationResult result)
    {
        if (result == null)
        {
            return null;
        }

        if (result.status() == BBSRegistrationStatus.ACCEPTED)
        {
            this.acceptedRegistrations.add(result.id());
        }
        else
        {
            this.rejectedRegistrations.add(result.toString());
        }

        return result;
    }

    BBSAddonDiagnostics snapshot()
    {
        return new BBSAddonDiagnostics(
            this.addonId,
            this.displayName,
            this.addonVersion,
            this.apiVersion,
            this.protocol,
            this.state,
            this.failedPhase,
            this.lastErrorClass,
            this.acceptedRegistrations,
            this.rejectedRegistrations,
            this.warnings,
            this.errors
        );
    }
}
