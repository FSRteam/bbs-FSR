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

    synchronized String addonId()
    {
        return this.addonId;
    }

    synchronized BBSAddonState state()
    {
        return this.state;
    }

    synchronized void state(BBSAddonState state)
    {
        this.state = state;
    }

    synchronized void fail(BBSAddonPhase phase, Throwable throwable)
    {
        this.fail(phase, throwable, "phase " + phase + " failed");
    }

    synchronized void fail(BBSAddonPhase phase, Throwable throwable, String message)
    {
        this.state = BBSAddonState.FAILED;
        this.failedPhase = phase;
        this.lastErrorClass = throwable == null ? null : throwable.getClass().getName();
        this.error(message, throwable);
    }

    synchronized void fail(BBSAddonPhase phase, String errorClass, String message)
    {
        this.state = BBSAddonState.FAILED;
        this.failedPhase = phase;
        this.lastErrorClass = errorClass;
        this.errors.add(message == null ? "phase " + phase + " failed" : message);
    }

    @Override
    public synchronized void info(String message)
    {
        if (message != null && !message.isBlank())
        {
            this.warnings.add("INFO: " + message);
        }
    }

    @Override
    public synchronized void warn(String message)
    {
        if (message != null && !message.isBlank())
        {
            this.warnings.add(message);
        }
    }

    @Override
    public synchronized void error(String message, Throwable error)
    {
        String suffix = error == null ? "" : " (" + error.getClass().getName() + ": " + error.getMessage() + ")";

        this.errors.add((message == null ? "error" : message) + suffix);
    }

    @Override
    public synchronized BBSRegistrationResult record(BBSRegistrationResult result)
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

    synchronized BBSAddonDiagnostics snapshot()
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

    synchronized void mergeRejectedAttempt(BBSAddonDiagnostics attempt)
    {
        if (attempt == null)
        {
            return;
        }

        String prefix = "rejected " + attempt.protocol() + " attempt: ";

        for (String rejected : attempt.rejectedRegistrations())
        {
            this.rejectedRegistrations.add(prefix + rejected);
        }

        for (String warning : attempt.warnings())
        {
            this.warnings.add(prefix + warning);
        }

        for (String error : attempt.errors())
        {
            this.errors.add(prefix + error);
        }

        if (attempt.failedPhase() != null || attempt.lastErrorClass() != null)
        {
            this.warnings.add(prefix + "failedPhase=" + attempt.failedPhase()
                + " lastErrorClass=" + attempt.lastErrorClass());
        }
    }
}
