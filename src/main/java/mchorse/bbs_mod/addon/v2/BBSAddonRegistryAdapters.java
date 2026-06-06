package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.clips.BBSClipRegistry;
import mchorse.bbs_mod.api.events.BBSEventRegistry;
import mchorse.bbs_mod.api.forms.BBSFormRegistry;
import mchorse.bbs_mod.api.network.BBSNetworkRegistry;
import mchorse.bbs_mod.api.particles.BBSParticleRegistry;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.api.resources.BBSResourceRegistry;
import mchorse.bbs_mod.api.settings.BBSSettingsRegistry;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.util.Map;
import java.util.function.Consumer;

final class BBSAddonRegistryAdapters
{
    private BBSAddonRegistryAdapters() {}

    static BBSResourceRegistry resources(BBSAddonDiagnosticRecord diagnostics, AssetProvider provider)
    {
        return new BBSResourceRegistry()
        {
            @Override
            public BBSRegistrationResult registerSourcePack(ISourcePack sourcePack)
            {
                return registerPack(diagnostics, provider, sourcePack, false);
            }

            @Override
            public BBSRegistrationResult registerSourcePackFirst(ISourcePack sourcePack)
            {
                return registerPack(diagnostics, provider, sourcePack, true);
            }
        };
    }

    static BBSFormRegistry forms(BBSAddonDiagnosticRecord diagnostics, FormArchitect forms)
    {
        return (id, formType) ->
        {
            String key = stringId(id);

            if (forms == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected(key, "form registry is not available"));
            }

            if (id == null || formType == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected(key, "form id or type is null"));
            }

            Class<? extends Form> existing = forms.getTypeClass(id);

            if (existing != null)
            {
                return diagnostics.record(BBSRegistrationResult.duplicate(key, existing.getName()));
            }

            forms.register(id, formType, null);

            return diagnostics.record(BBSRegistrationResult.accepted(key));
        };
    }

    static BBSSettingsRegistry settings(BBSAddonDiagnosticRecord diagnostics, File settingsFolder)
    {
        return new BBSSettingsRegistry()
        {
            @Override
            public BBSRegistrationResult register(Icon icon, String id, Consumer<SettingsBuilder> registerer)
            {
                File destination = settingsFolder == null ? null : new File(settingsFolder, id + ".json");

                return this.register(icon, id, destination, registerer);
            }

            @Override
            public BBSRegistrationResult register(Icon icon, String id, File destination, Consumer<SettingsBuilder> registerer)
            {
                if (id == null || id.isBlank())
                {
                    return diagnostics.record(BBSRegistrationResult.rejected("<blank>", "settings id is blank"));
                }

                if (icon == null || destination == null || registerer == null)
                {
                    return diagnostics.record(BBSRegistrationResult.rejected(id, "settings icon, destination or registerer is null"));
                }

                try
                {
                    if (BBSMod.getSettings() != null && BBSMod.getSettings().modules.containsKey(id))
                    {
                        return diagnostics.record(BBSRegistrationResult.duplicate(id, BBSMod.getSettings().modules.get(id).getClass().getName()));
                    }

                    BBSMod.setupConfig(icon, id, destination, registerer);
                    return diagnostics.record(BBSRegistrationResult.accepted(id));
                }
                catch (Exception e)
                {
                    diagnostics.error("settings registration failed for '" + id + "'", e);
                    return diagnostics.record(BBSRegistrationResult.rejected(id, e.getMessage()));
                }
            }
        };
    }

    static BBSClipRegistry clips(BBSAddonDiagnosticRecord diagnostics, MapFactory<Clip, ClipFactoryData> cameraClips, MapFactory<Clip, ClipFactoryData> actionClips)
    {
        return new BBSClipRegistry()
        {
            @Override
            public BBSRegistrationResult registerCameraClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data)
            {
                return registerClip(diagnostics, cameraClips, id, clipType, data, "camera clip");
            }

            @Override
            public BBSRegistrationResult registerActionClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data)
            {
                return registerClip(diagnostics, actionClips, id, clipType, data, "action clip");
            }
        };
    }

    static BBSParticleRegistry particles(BBSAddonDiagnosticRecord diagnostics, Map<String, String> particleComponents)
    {
        return (id, componentClassName) ->
        {
            if (id == null || id.isBlank())
            {
                return diagnostics.record(BBSRegistrationResult.rejected("<blank>", "particle component id is blank"));
            }

            if (componentClassName == null || componentClassName.isBlank())
            {
                return diagnostics.record(BBSRegistrationResult.rejected(id, "particle component class name is blank"));
            }

            String key = id.trim();

            if (particleComponents.containsKey(key))
            {
                return diagnostics.record(BBSRegistrationResult.duplicate(key, particleComponents.get(key)));
            }

            particleComponents.put(key, componentClassName.trim());

            return diagnostics.record(BBSRegistrationResult.accepted(key));
        };
    }

    static BBSNetworkRegistry network(BBSAddonDiagnosticRecord diagnostics)
    {
        return (id, receiver) ->
        {
            String key = id == null ? "<null>" : id.toString();

            if (id == null || receiver == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected(key, "network id or receiver is null"));
            }

            try
            {
                NetworkCompat.registerServerReceiver(id, receiver);
                return diagnostics.record(BBSRegistrationResult.accepted(key));
            }
            catch (Exception e)
            {
                diagnostics.error("legacy network receiver registration failed for '" + key + "'", e);
                return diagnostics.record(BBSRegistrationResult.rejected(key, e.getMessage()));
            }
        };
    }

    static BBSEventRegistry events(BBSAddonDiagnosticRecord diagnostics, EventBus eventBus)
    {
        return (subscriber) ->
        {
            if (subscriber == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected("<null>", "subscriber is null"));
            }

            if (eventBus == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected(subscriber.getClass().getName(), "event bus is not available"));
            }

            eventBus.register(subscriber);

            return diagnostics.record(BBSRegistrationResult.accepted(subscriber.getClass().getName()));
        };
    }

    private static BBSRegistrationResult registerPack(BBSAddonDiagnosticRecord diagnostics, AssetProvider provider, ISourcePack sourcePack, boolean first)
    {
        String key = sourcePack == null ? "<null>" : sourcePack.getPrefix();

        if (provider == null)
        {
            return diagnostics.record(BBSRegistrationResult.rejected(key, "asset provider is not available"));
        }

        if (sourcePack == null)
        {
            return diagnostics.record(BBSRegistrationResult.rejected(key, "source pack is null"));
        }

        if (first)
        {
            provider.registerFirst(sourcePack);
        }
        else
        {
            provider.register(sourcePack);
        }

        return diagnostics.record(BBSRegistrationResult.accepted(key));
    }

    private static BBSRegistrationResult registerClip(
        BBSAddonDiagnosticRecord diagnostics,
        MapFactory<Clip, ClipFactoryData> factory,
        Link id,
        Class<? extends Clip> clipType,
        ClipFactoryData data,
        String kind
    )
    {
        String key = stringId(id);

        if (factory == null)
        {
            return diagnostics.record(BBSRegistrationResult.rejected(key, kind + " registry is not available"));
        }

        if (id == null || clipType == null || data == null)
        {
            return diagnostics.record(BBSRegistrationResult.rejected(key, kind + " id, type or data is null"));
        }

        Class<? extends Clip> existing = factory.getTypeClass(id);

        if (existing != null)
        {
            return diagnostics.record(BBSRegistrationResult.duplicate(key, existing.getName()));
        }

        factory.register(id, clipType, data);

        return diagnostics.record(BBSRegistrationResult.accepted(key));
    }

    private static String stringId(Link id)
    {
        return id == null ? "<null>" : id.toString();
    }
}
