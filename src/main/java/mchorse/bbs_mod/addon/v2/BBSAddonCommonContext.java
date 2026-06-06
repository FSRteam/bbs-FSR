package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonRegistrationContext;
import mchorse.bbs_mod.api.addon.BBSAddonSetupContext;
import mchorse.bbs_mod.api.clips.BBSClipRegistry;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnosticSink;
import mchorse.bbs_mod.api.events.BBSEventRegistry;
import mchorse.bbs_mod.api.forms.BBSFormRegistry;
import mchorse.bbs_mod.api.network.BBSNetworkRegistry;
import mchorse.bbs_mod.api.particles.BBSParticleRegistry;
import mchorse.bbs_mod.api.resources.BBSResourceRegistry;
import mchorse.bbs_mod.api.settings.BBSSettingsRegistry;
import mchorse.bbs_mod.loader.LoaderAccess;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

final class BBSAddonCommonContext implements BBSAddonRegistrationContext, BBSAddonSetupContext
{
    private final BBSAddonDescriptor descriptor;
    private final BBSAddonDiagnosticRecord diagnostics;
    private final LoaderAccess loader;
    private final BBSResourceRegistry resources;
    private final BBSFormRegistry forms;
    private final BBSSettingsRegistry settings;
    private final BBSClipRegistry clips;
    private final BBSParticleRegistry particles;
    private final BBSNetworkRegistry network;
    private final BBSEventRegistry events;

    BBSAddonCommonContext(
        BBSAddonDescriptor descriptor,
        BBSAddonDiagnosticRecord diagnostics,
        LoaderAccess loader,
        BBSResourceRegistry resources,
        BBSFormRegistry forms,
        BBSSettingsRegistry settings,
        BBSClipRegistry clips,
        BBSParticleRegistry particles,
        BBSNetworkRegistry network,
        BBSEventRegistry events
    )
    {
        this.descriptor = descriptor;
        this.diagnostics = diagnostics;
        this.loader = loader;
        this.resources = resources;
        this.forms = forms;
        this.settings = settings;
        this.clips = clips;
        this.particles = particles;
        this.network = network;
        this.events = events;
    }

    @Override
    public BBSAddonDescriptor descriptor()
    {
        return this.descriptor;
    }

    @Override
    public BBSAddonDiagnosticSink diagnostics()
    {
        return this.diagnostics;
    }

    @Override
    public Path gameDir()
    {
        return this.loader == null ? Paths.get(".") : this.loader.getGameDir();
    }

    @Override
    public boolean isDevelopmentEnvironment()
    {
        return this.loader != null && this.loader.isDevelopmentEnvironment();
    }

    @Override
    public boolean isModLoaded(String modId)
    {
        return this.loader != null && this.loader.isModLoaded(modId);
    }

    @Override
    public Optional<String> getModVersion(String modId)
    {
        return this.loader == null ? Optional.empty() : this.loader.getModVersion(modId);
    }

    @Override
    public BBSResourceRegistry resources()
    {
        return this.resources;
    }

    @Override
    public BBSFormRegistry forms()
    {
        return this.forms;
    }

    @Override
    public BBSSettingsRegistry settings()
    {
        return this.settings;
    }

    @Override
    public BBSClipRegistry clips()
    {
        return this.clips;
    }

    @Override
    public BBSParticleRegistry particles()
    {
        return this.particles;
    }

    @Override
    public BBSNetworkRegistry network()
    {
        return this.network;
    }

    @Override
    public BBSEventRegistry events()
    {
        return this.events;
    }
}
