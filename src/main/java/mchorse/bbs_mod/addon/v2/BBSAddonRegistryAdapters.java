package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.clips.BBSClipRegistry;
import mchorse.bbs_mod.api.events.BBSEventRegistry;
import mchorse.bbs_mod.api.forms.BBSFormRegistry;
import mchorse.bbs_mod.api.network.BBSAddonServerNetworkReceiver;
import mchorse.bbs_mod.api.network.BBSNetworkRegistry;
import mchorse.bbs_mod.api.particles.BBSParticleRegistry;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.api.resources.BBSResourceRegistry;
import mchorse.bbs_mod.api.settings.BBSSettingsRegistry;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.compat.AddonPayloadBroker;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.File;
import java.util.Map;
import java.util.function.Consumer;

final class BBSAddonRegistryAdapters
{
    private BBSAddonRegistryAdapters() {}

    static BBSResourceRegistry resources(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, AssetProvider provider)
    {
        return new BBSResourceRegistry()
        {
            @Override
            public BBSRegistrationResult registerSourcePack(ISourcePack sourcePack)
            {
                return registerPack(diagnostics, descriptor, provider, sourcePack, false);
            }

            @Override
            public BBSRegistrationResult registerSourcePackFirst(ISourcePack sourcePack)
            {
                return registerPack(diagnostics, descriptor, provider, sourcePack, true);
            }
        };
    }

    static BBSFormRegistry forms(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, FormArchitect forms)
    {
        return (id, formType) ->
        {
            String key = stringId(id);

            BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.FORMS, key, "forms");

            if (capability != null)
            {
                return capability;
            }

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

    static BBSSettingsRegistry settings(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, File settingsFolder)
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
                BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.SETTINGS, id, "settings");

                if (capability != null)
                {
                    return capability;
                }

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

    static BBSClipRegistry clips(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, MapFactory<Clip, ClipFactoryData> cameraClips, MapFactory<Clip, ClipFactoryData> actionClips)
    {
        return new BBSClipRegistry()
        {
            @Override
            public BBSRegistrationResult registerCameraClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data)
            {
                return registerClip(diagnostics, descriptor, cameraClips, id, clipType, data, "camera clip");
            }

            @Override
            public BBSRegistrationResult registerActionClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data)
            {
                return registerClip(diagnostics, descriptor, actionClips, id, clipType, data, "action clip");
            }
        };
    }

    static BBSParticleRegistry particles(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, Map<String, String> particleComponents)
    {
        return (id, componentClassName) ->
        {
            BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.PARTICLES, id, "particles");

            if (capability != null)
            {
                return capability;
            }

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

    static BBSNetworkRegistry network(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor)
    {
        return new BBSNetworkRegistry()
        {
            @Override
            public BBSRegistrationResult registerLegacyServerReceiver(ResourceLocation id, NetworkCompat.ServerReceiver receiver)
            {
                String key = id == null ? "<null>" : id.toString();
                BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.NETWORK, key, "network");

                if (capability != null)
                {
                    return capability;
                }

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
            }

            @Override
            public BBSRegistrationResult registerServerReceiver(ResourceLocation id, BBSAddonServerNetworkReceiver receiver)
            {
                String key = id == null ? "<null>" : id.toString();
                BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.NETWORK, key, "network");

                if (capability != null)
                {
                    return capability;
                }

                return diagnostics.record(AddonPayloadBroker.registerServerReceiver(descriptor, id, receiver));
            }

            @Override
            public FriendlyByteBuf createBuffer()
            {
                return AddonPayloadBroker.createBuffer();
            }

            @Override
            public boolean sendToPlayer(ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload)
            {
                return AddonPayloadBroker.sendToPlayer(descriptor, player, id, payload);
            }

            @Override
            public boolean sendToPlayersTrackingEntity(Entity entity, ResourceLocation id, FriendlyByteBuf payload)
            {
                return AddonPayloadBroker.sendToPlayersTrackingEntity(descriptor, entity, id, payload);
            }

            @Override
            public boolean sendToPlayersTrackingEntityAndSelf(ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload)
            {
                return AddonPayloadBroker.sendToPlayersTrackingEntityAndSelf(descriptor, player, id, payload);
            }
        };
    }

    static BBSEventRegistry events(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, EventBus eventBus)
    {
        return (subscriber) ->
        {
            String key = subscriber == null ? "<null>" : subscriber.getClass().getName();
            BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.EVENTS, key, "events");

            if (capability != null)
            {
                return capability;
            }

            if (subscriber == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected("<null>", "subscriber is null"));
            }

            if (eventBus == null)
            {
                return diagnostics.record(BBSRegistrationResult.rejected(subscriber.getClass().getName(), "event bus is not available"));
            }

            eventBus.register(subscriber);

            return diagnostics.record(BBSRegistrationResult.accepted(key));
        };
    }

    private static BBSRegistrationResult registerPack(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor, AssetProvider provider, ISourcePack sourcePack, boolean first)
    {
        String key = sourcePack == null ? "<null>" : sourcePack.getPrefix();
        BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.SOURCE_PACKS, key, "resources");

        if (capability != null)
        {
            return capability;
        }

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
        BBSAddonDescriptor descriptor,
        MapFactory<Clip, ClipFactoryData> factory,
        Link id,
        Class<? extends Clip> clipType,
        ClipFactoryData data,
        String kind
    )
    {
        String key = stringId(id);
        BBSRegistrationResult capability = requireCapability(diagnostics, descriptor, BBSAddonCapability.CLIPS, key, "clips");

        if (capability != null)
        {
            return capability;
        }

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

    private static BBSRegistrationResult requireCapability(
        BBSAddonDiagnosticRecord diagnostics,
        BBSAddonDescriptor descriptor,
        BBSAddonCapability capability,
        String id,
        String facade
    )
    {
        String key = id == null || id.isBlank() ? "<blank>" : id;

        if (descriptor == null || !descriptor.capabilities().contains(capability))
        {
            return diagnostics.record(BBSRegistrationResult.rejected(key,
                "addon did not declare " + capability + " capability required by " + facade + " facade"));
        }

        return null;
    }
}
