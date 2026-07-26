package mchorse.bbs_mod.plugin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.client.BBSPluginClientContext;
import mchorse.bbs_mod.api.plugin.client.BBSPluginClipClientRegistry;
import mchorse.bbs_mod.api.plugin.client.BBSPluginFormClientRegistry;
import mchorse.bbs_mod.api.plugin.client.BBSPluginKeyMappingRegistry;
import mchorse.bbs_mod.api.plugin.client.BBSPluginRendererRegistry;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.renderers.ParticleFormRenderer;
import mchorse.bbs_mod.mixin.client.BlockEntityRenderersAccessor;
import mchorse.bbs_mod.mixin.client.EntityRenderersAccessor;
import mchorse.bbs_mod.mixin.client.KeyMappingAccessor;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.plugin.manager.PluginStructuralRegistrationWindow;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Physical-client thread fence and export guard for structural plugin transactions. */
public final class BBSPluginClientStructuralBridge
{
    private static final Map<String, PluginOwner> KEY_OWNERS = new ConcurrentHashMap<>();
    private static final Map<String, InputConstants.Key> SAVED_KEYS = new ConcurrentHashMap<>();
    private static final Map<Object, PluginOwner> ENTITY_RENDERER_OWNERS = new ConcurrentHashMap<>();
    private static final Map<Object, PluginOwner> BLOCK_RENDERER_OWNERS = new ConcurrentHashMap<>();

    private BBSPluginClientStructuralBridge() {}

    public static void runSafepoint(Runnable operation)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.isSameThread())
        {
            operation.run();
            refreshProjection();
            return;
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        minecraft.execute(() ->
        {
            try
            {
                operation.run();
                refreshProjection();
                completion.complete(null);
            }
            catch (Throwable error)
            {
                completion.completeExceptionally(error);
            }
        });

        try
        {
            completion.get(15, TimeUnit.SECONDS);
        }
        catch (Exception error)
        {
            Throwable cause = error.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            if (cause instanceof Error fatal)
            {
                throw fatal;
            }

            throw new IllegalStateException("timed out waiting for the client structural safepoint", cause == null ? error : cause);
        }
    }

    public static boolean isBusy()
    {
        if (BBSModClient.getVideoRecorder() != null && BBSModClient.getVideoRecorder().isRecording())
        {
            return true;
        }

        if (BBSModClient.getWorldVideoExportSession().isExporting())
        {
            return true;
        }

        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null)
        {
            return false;
        }

        /* Query the Film panel directly rather than the currently selected
         * dashboard panel: a panel-level export keeps running in the shared
         * overlay layer even after the user navigates to a different panel,
         * so gating on "current panel is Film" would let a reload slip
         * through mid-export. */
        UIFilmPanel filmPanel = dashboard.getPanel(UIFilmPanel.class);

        return filmPanel != null && filmPanel.recorder.isExporting();
    }

    public static Object createExtension(
        Class<?> extensionType,
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        PluginStructuralRegistrationWindow window
    )
    {
        if (extensionType != BBSPluginClientContext.class)
        {
            return null;
        }

        return new ClientContext(descriptor, owner, ledger, window);
    }

    private static void refreshProjection()
    {
        if (BBSModClient.getFormCategories() != null)
        {
            BBSModClient.getFormCategories().markDirty();
        }

        /* Particle components are file-driven: dropping the resolved Class table and
         * poking every live emitter to recheck is teardown+rebuild for this holder,
         * mirroring the form/clip snapshot-rebuild without needing MapType round-trips. */
        ParticleScheme.PARSER.refreshApi2Components();
        ParticleFormRenderer.lastUpdate = System.currentTimeMillis();
    }

    private static final class ClientContext implements BBSPluginClientContext
    {
        private final BBSPluginDescriptor descriptor;
        private final PluginOwner owner;
        private final PluginContributionLedger ledger;
        private final PluginStructuralRegistrationWindow window;
        private final BBSPluginKeyMappingRegistry keyMappings = this::registerKeyMapping;
        private final BBSPluginRendererRegistry renderers = new Renderers();
        private final BBSPluginFormClientRegistry forms = new Forms();
        private final BBSPluginClipClientRegistry clips = new Clips();

        private ClientContext(
            BBSPluginDescriptor descriptor,
            PluginOwner owner,
            PluginContributionLedger ledger,
            PluginStructuralRegistrationWindow window
        )
        {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.ledger = Objects.requireNonNull(ledger, "ledger");
            this.window = Objects.requireNonNull(window, "window");
        }

        @Override
        public BBSPluginKeyMappingRegistry keyMappings()
        {
            return this.keyMappings;
        }

        @Override
        public BBSPluginRendererRegistry renderers()
        {
            return this.renderers;
        }

        @Override
        public BBSPluginFormClientRegistry forms()
        {
            return this.forms;
        }

        @Override
        public BBSPluginClipClientRegistry clips()
        {
            return this.clips;
        }

        private BBSRegistrationResult registerKeyMapping(KeyMapping mapping)
        {
            this.requireCapability(BBSPluginCapability.KEY_MAPPINGS, "key mappings");
            Objects.requireNonNull(mapping, "mapping");
            String name = mapping.getName();
            String key = "key-mapping:" + name;
            KeyMappingAccessor access = (KeyMappingAccessor) (Object) mapping;
            Map<String, KeyMapping> all = access.bbs$getAll();
            Minecraft minecraft = Minecraft.getInstance();
            KeyMapping active = null;

            if (minecraft != null && minecraft.options != null)
            {
                for (KeyMapping candidate : minecraft.options.keyMappings)
                {
                    if (candidate != null && name.equals(candidate.getName()) && candidate != mapping)
                    {
                        active = candidate;
                        break;
                    }
                }
            }

            if (active != null)
            {
                all.put(name, active);
                SAVED_KEYS.put(name, active.getKey());
            }
            else if (all.get(name) == mapping)
            {
                all.remove(name);
            }

            if (KEY_OWNERS.containsKey(name) && !this.window.canReplace(key))
            {
                return BBSRegistrationResult.duplicate(name, KEY_OWNERS.get(name).toString());
            }

            this.window.stage(
                key,
                PluginStructuralRegistrationWindow.Kind.CLIENT,
                null,
                this.ledger,
                () ->
                {
                    PluginOwner previousOwner = KEY_OWNERS.putIfAbsent(name, this.owner);

                    if (previousOwner != null && !previousOwner.equals(this.owner))
                    {
                        throw new IllegalStateException("key mapping is already owned by " + previousOwner);
                    }

                    all.put(name, mapping);
                    InputConstants.Key saved = SAVED_KEYS.get(name);

                    if (saved != null)
                    {
                        mapping.setKey(saved);
                    }

                    if (minecraft != null && minecraft.options != null)
                    {
                        List<KeyMapping> mappings = new ArrayList<>(Arrays.asList(minecraft.options.keyMappings));
                        mappings.removeIf((candidate) -> candidate == mapping || candidate != null && name.equals(candidate.getName()));
                        mappings.add(mapping);
                        minecraft.options.keyMappings = mappings.toArray(KeyMapping[]::new);
                        KeyMapping.resetMapping();
                    }
                },
                () ->
                {
                    if (KEY_OWNERS.get(name) == this.owner)
                    {
                        KEY_OWNERS.remove(name);
                    }
                    if (all.get(name) == mapping)
                    {
                        all.remove(name);
                    }
                    if (minecraft != null && minecraft.options != null)
                    {
                        minecraft.options.keyMappings = Arrays.stream(minecraft.options.keyMappings)
                            .filter((candidate) -> candidate != mapping)
                            .toArray(KeyMapping[]::new);
                        KeyMapping.resetMapping();
                    }
                }
            );

            return BBSRegistrationResult.accepted(name);
        }

        private void requireCapability(BBSPluginCapability capability, String facade)
        {
            if (!this.descriptor.capabilities().contains(capability))
            {
                throw new IllegalStateException("plugin did not declare " + capability.wireName()
                    + " capability required by " + facade + " facade");
            }
        }

        private final class Renderers implements BBSPluginRendererRegistry
        {
            @Override
            public <T extends Entity> BBSRegistrationResult registerEntity(EntityType<T> type, EntityRendererProvider<T> provider)
            {
                ClientContext.this.requireCapability(BBSPluginCapability.ENTITY_RENDERER, "entity renderers");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(provider, "provider");
                String key = "entity-renderer:" + type;
                Map<EntityType<?>, EntityRendererProvider<?>> providers = ((EntityRenderersAccessor) (Object) new EntityRenderers()).bbs$getProviders();
                PluginOwner previousOwner = ENTITY_RENDERER_OWNERS.get(type);

                if (previousOwner != null && !previousOwner.equals(ClientContext.this.owner) && !ClientContext.this.window.canReplace(key))
                {
                    return BBSRegistrationResult.duplicate(key, previousOwner.toString());
                }

                EntityRendererProvider<?>[] previous = new EntityRendererProvider<?>[1];
                ClientContext.this.window.stage(
                    key,
                    PluginStructuralRegistrationWindow.Kind.CLIENT,
                    null,
                    ClientContext.this.ledger,
                    () ->
                    {
                        PluginOwner owner = ENTITY_RENDERER_OWNERS.putIfAbsent(type, ClientContext.this.owner);
                        if (owner != null && !owner.equals(ClientContext.this.owner))
                        {
                            throw new IllegalStateException("entity renderer is already owned by " + owner);
                        }
                        previous[0] = providers.put(type, provider);
                        reloadRenderers();
                    },
                    () ->
                    {
                        if (ENTITY_RENDERER_OWNERS.get(type) == ClientContext.this.owner)
                        {
                            ENTITY_RENDERER_OWNERS.remove(type);
                        }
                        if (providers.get(type) == provider)
                        {
                            if (previous[0] == null) providers.remove(type); else providers.put(type, previous[0]);
                            reloadRenderers();
                        }
                    }
                );
                return BBSRegistrationResult.accepted(key);
            }

            @Override
            public <T extends BlockEntity> BBSRegistrationResult registerBlockEntity(BlockEntityType<T> type, BlockEntityRendererProvider<? super T> provider)
            {
                ClientContext.this.requireCapability(BBSPluginCapability.BLOCK_ENTITY_RENDERER, "block entity renderers");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(provider, "provider");
                String key = "block-entity-renderer:" + type;
                Map<BlockEntityType<?>, BlockEntityRendererProvider<?>> providers = ((BlockEntityRenderersAccessor) (Object) new BlockEntityRenderers()).bbs$getProviders();
                PluginOwner previousOwner = BLOCK_RENDERER_OWNERS.get(type);

                if (previousOwner != null && !previousOwner.equals(ClientContext.this.owner) && !ClientContext.this.window.canReplace(key))
                {
                    return BBSRegistrationResult.duplicate(key, previousOwner.toString());
                }

                BlockEntityRendererProvider<?>[] previous = new BlockEntityRendererProvider<?>[1];
                ClientContext.this.window.stage(
                    key,
                    PluginStructuralRegistrationWindow.Kind.CLIENT,
                    null,
                    ClientContext.this.ledger,
                    () ->
                    {
                        PluginOwner owner = BLOCK_RENDERER_OWNERS.putIfAbsent(type, ClientContext.this.owner);
                        if (owner != null && !owner.equals(ClientContext.this.owner))
                        {
                            throw new IllegalStateException("block entity renderer is already owned by " + owner);
                        }
                        previous[0] = providers.put(type, (BlockEntityRendererProvider<?>) provider);
                        reloadRenderers();
                    },
                    () ->
                    {
                        if (BLOCK_RENDERER_OWNERS.get(type) == ClientContext.this.owner)
                        {
                            BLOCK_RENDERER_OWNERS.remove(type);
                        }
                        if (providers.get(type) == provider)
                        {
                            if (previous[0] == null) providers.remove(type); else providers.put(type, previous[0]);
                            reloadRenderers();
                        }
                    }
                );
                return BBSRegistrationResult.accepted(key);
            }
        }

        private final class Forms implements BBSPluginFormClientRegistry
        {
            @Override
            public <T extends Form> BBSRegistrationResult registerRenderer(Class<T> type, FormUtilsClient.IFormRendererFactory<T> factory)
            {
                ClientContext.this.requireCapability(BBSPluginCapability.FORMS, "client forms");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(factory, "factory");
                String key = "form-renderer:" + type.getName();
                FormUtilsClient.IFormRendererFactory previous = FormUtilsClient.getRegisteredFactory(type);

                if (previous != null && !ClientContext.this.window.canReplace(key))
                {
                    return BBSRegistrationResult.duplicate(key, previous.getClass().getName());
                }

                ClientContext.this.window.stage(key, PluginStructuralRegistrationWindow.Kind.CLIENT, null, ClientContext.this.ledger,
                    () -> FormUtilsClient.register(type, factory),
                    () -> FormUtilsClient.unregisterPluginRenderer(type, factory));
                return BBSRegistrationResult.accepted(key);
            }

            @Override
            public BBSRegistrationResult registerEditor(Class<? extends Form> type, Supplier<UIForm> factory)
            {
                ClientContext.this.requireCapability(BBSPluginCapability.FORMS, "client forms");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(factory, "factory");
                String key = "form-editor:" + type.getName();
                Supplier<UIForm> previous = UIFormEditor.getRegisteredPanel(type);

                if (previous != null && !ClientContext.this.window.canReplace(key))
                {
                    return BBSRegistrationResult.duplicate(key, previous.getClass().getName());
                }

                ClientContext.this.window.stage(key, PluginStructuralRegistrationWindow.Kind.CLIENT, null, ClientContext.this.ledger,
                    () -> UIFormEditor.register(type, factory),
                    () -> UIFormEditor.unregisterPluginPanel(type, factory));
                return BBSRegistrationResult.accepted(key);
            }

            @Override
            public BBSRegistrationResult registerExtra(Form form)
            {
                ClientContext.this.requireCapability(BBSPluginCapability.FORMS, "client forms");
                Objects.requireNonNull(form, "form");
                String key = "form-extra:" + form.getClass().getName();
                ClientContext.this.window.stage(key, PluginStructuralRegistrationWindow.Kind.CLIENT, null, ClientContext.this.ledger,
                    () ->
                    {
                        FormCategories categories = BBSModClient.getFormCategories();
                        if (categories != null) categories.addExtraForm(form);
                    },
                    () ->
                    {
                        FormCategories categories = BBSModClient.getFormCategories();
                        if (categories != null) categories.removeExtraForm(form);
                    });
                return BBSRegistrationResult.accepted(key);
            }
        }

        private final class Clips implements BBSPluginClipClientRegistry
        {
            @Override
            public <T extends Clip> BBSRegistrationResult registerEditor(Class<T> type, UIClip.IUIClipFactory<T> factory)
            {
                ClientContext.this.requireCapability(BBSPluginCapability.CLIPS, "client clips");
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(factory, "factory");
                String key = "clip-editor:" + type.getName();
                UIClip.IUIClipFactory previous = UIClip.getRegisteredFactory(type);

                if (previous != null && !ClientContext.this.window.canReplace(key))
                {
                    return BBSRegistrationResult.duplicate(key, previous.getClass().getName());
                }

                ClientContext.this.window.stage(key, PluginStructuralRegistrationWindow.Kind.CLIENT, null, ClientContext.this.ledger,
                    () -> UIClip.register(type, factory),
                    () -> UIClip.unregisterPluginFactory(type, factory));
                return BBSRegistrationResult.accepted(key);
            }
        }
    }

    private static void reloadRenderers()
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.getResourceManager() == null)
        {
            return;
        }

        minecraft.getEntityRenderDispatcher().onResourceManagerReload(minecraft.getResourceManager());
        minecraft.getBlockEntityRenderDispatcher().onResourceManagerReload(minecraft.getResourceManager());
    }
}
