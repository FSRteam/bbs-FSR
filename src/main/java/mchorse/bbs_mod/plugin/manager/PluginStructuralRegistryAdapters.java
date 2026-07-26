package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.BBSPluginClipRegistry;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginFormRegistry;
import mchorse.bbs_mod.api.plugin.BBSPluginParticleRegistry;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Host-owned common structural registries for one plugin generation. */
final class PluginStructuralRegistryAdapters
{
    private PluginStructuralRegistryAdapters() {}

    static BBSPluginFormRegistry forms(
        PluginStructuralRegistrationWindow window,
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        Supplier<FormArchitect> forms
    )
    {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ledger, "ledger");

        return (id, formType) ->
        {
            window.requireOpen("forms");
            requireCapability(descriptor, BBSPluginCapability.FORMS, "forms");
            String key = stringId(id);

            FormArchitect formRegistry = forms.get();

            if (formRegistry == null)
            {
                return BBSRegistrationResult.rejected(key, "form registry is not available");
            }

            if (id == null || formType == null)
            {
                return BBSRegistrationResult.rejected(key, "form id or type is null");
            }

            Class<? extends Form> existing = formRegistry.getTypeClass(id);
            String structuralKey = "form:" + key;

            if (existing != null && !window.canReplace(structuralKey))
            {
                return BBSRegistrationResult.duplicate(key, existing.getName());
            }

            window.stage(
                structuralKey,
                PluginStructuralRegistrationWindow.Kind.FORM,
                formType,
                ledger,
                () ->
                {
                    Class<? extends Form> current = formRegistry.getTypeClass(id);

                    if (current != null)
                    {
                        throw new IllegalStateException("form id is already registered at commit: " + key);
                    }
                    formRegistry.register(id, formType, null);
                },
                () ->
                {
                    if (formRegistry.getTypeClass(id) == formType)
                    {
                        formRegistry.unregister(id);
                    }
                }
            );

            return BBSRegistrationResult.accepted(key);
        };
    }

    static BBSPluginClipRegistry clips(
        PluginStructuralRegistrationWindow window,
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        Supplier<MapFactory<Clip, ClipFactoryData>> cameraClips,
        Supplier<MapFactory<Clip, ClipFactoryData>> actionClips
    )
    {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ledger, "ledger");

        return new BBSPluginClipRegistry()
        {
            @Override
            public BBSRegistrationResult registerCameraClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data)
            {
                return registerClip(window, descriptor, owner, ledger, cameraClips, id, clipType, data, "camera clip");
            }

            @Override
            public BBSRegistrationResult registerActionClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data)
            {
                return registerClip(window, descriptor, owner, ledger, actionClips, id, clipType, data, "action clip");
            }
        };
    }

    static BBSPluginParticleRegistry particles(
        PluginStructuralRegistrationWindow window,
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        PluginParticleComponents particleComponents,
        ClassLoader classLoader,
        Supplier<Map<String, PluginParticleComponentClass>> existingComponents
    )
    {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(particleComponents, "particleComponents");
        Objects.requireNonNull(existingComponents, "existingComponents");

        return (id, componentClassName) ->
        {
            window.requireOpen("particles");
            requireCapability(descriptor, BBSPluginCapability.PARTICLES, "particles");

            if (id == null || id.isBlank())
            {
                return BBSRegistrationResult.rejected("<blank>", "particle component id is blank");
            }

            if (componentClassName == null || componentClassName.isBlank())
            {
                return BBSRegistrationResult.rejected(id, "particle component class name is blank");
            }

            String key = id.trim();
            String className = componentClassName.trim();
            String existing = particleComponents.className(key);
            String structuralKey = "particle:" + key;

            if (existing == null)
            {
                Map<String, PluginParticleComponentClass> registeredComponents = existingComponents.get();
                PluginParticleComponentClass existingComponent = registeredComponents == null ? null : registeredComponents.get(key);
                existing = existingComponent == null ? null : existingComponent.className();
            }

            if (existing != null && !window.canReplace(structuralKey))
            {
                return BBSRegistrationResult.duplicate(key, existing);
            }

            window.stage(
                structuralKey,
                PluginStructuralRegistrationWindow.Kind.PARTICLE,
                null,
                ledger,
                () -> particleComponents.register(owner, key, className, classLoader),
                () -> particleComponents.unregister(owner, key)
            );

            return BBSRegistrationResult.accepted(key);
        };
    }

    private static BBSRegistrationResult registerClip(
        PluginStructuralRegistrationWindow window,
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        Supplier<MapFactory<Clip, ClipFactoryData>> factory,
        Link id,
        Class<? extends Clip> clipType,
        ClipFactoryData data,
        String kind
    )
    {
        window.requireOpen("clips");
        requireCapability(descriptor, BBSPluginCapability.CLIPS, "clips");
        String key = stringId(id);

        MapFactory<Clip, ClipFactoryData> clipFactory = factory.get();

        if (clipFactory == null)
        {
            return BBSRegistrationResult.rejected(key, kind + " registry is not available");
        }

        if (id == null || clipType == null || data == null)
        {
            return BBSRegistrationResult.rejected(key, kind + " id, type or data is null");
        }

        Class<? extends Clip> existing = clipFactory.getTypeClass(id);
        String structuralKey = (kind.startsWith("camera") ? "camera-clip:" : "action-clip:") + key;

        if (existing != null && !window.canReplace(structuralKey))
        {
            return BBSRegistrationResult.duplicate(key, existing.getName());
        }

        window.stage(
            structuralKey,
            PluginStructuralRegistrationWindow.Kind.CLIP,
            clipType,
            ledger,
            () ->
            {
                Class<? extends Clip> current = clipFactory.getTypeClass(id);

                if (current != null)
                {
                    throw new IllegalStateException(kind + " id is already registered at commit: " + key);
                }
                clipFactory.register(id, clipType, data);
            },
            () ->
            {
                if (clipFactory.getTypeClass(id) == clipType)
                {
                    clipFactory.unregister(id);
                }
            }
        );

        return BBSRegistrationResult.accepted(key);
    }

    private static void requireCapability(BBSPluginDescriptor descriptor, BBSPluginCapability capability, String facade)
    {
        if (descriptor == null || !descriptor.capabilities().contains(capability))
        {
            throw new IllegalStateException("plugin did not declare " + capability.wireName() + " capability required by " + facade + " facade");
        }
    }

    private static String stringId(Link id)
    {
        return id == null ? "<null>" : id.toString();
    }
}
