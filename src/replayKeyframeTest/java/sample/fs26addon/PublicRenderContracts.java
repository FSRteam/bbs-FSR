package sample.fs26addon;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.api.client.compat.ClientVanillaAccess;
import mchorse.bbs_mod.api.client.editor.FormEditorTool;
import mchorse.bbs_mod.api.client.events.FilmEvents;
import mchorse.bbs_mod.api.client.events.RegisterFrameOverlaysEvent;
import mchorse.bbs_mod.api.client.events.RegisterFormPanelsEvent;
import mchorse.bbs_mod.api.client.events.RegisterPreviewOverlaysEvent;
import mchorse.bbs_mod.api.client.events.RegisterReplayActionsEvent;
import mchorse.bbs_mod.api.client.events.StructureRenderEvents;
import mchorse.bbs_mod.api.client.render.StructureRenderPart;
import mchorse.bbs_mod.api.compat.VanillaAccess;
import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.EventBus;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.BaseRegisterClipsEvent;
import mchorse.bbs_mod.api.events.BaseRegisterSettingsEvent;
import mchorse.bbs_mod.api.events.RegisterActionClipsEvent;
import mchorse.bbs_mod.api.events.RegisterCameraClipsEvent;
import mchorse.bbs_mod.api.events.RegisterFormsEvent;
import mchorse.bbs_mod.api.events.RegisterSettingsEvent;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.api.client.events.RegisterClientSettingsEvent;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.client.compat.BBSWorldRenderContext;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.SettingsManager;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.film.FrameOverlays;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.film.view.ViewDescriptor;
import mchorse.bbs_mod.ui.forms.editors.UIBodyPartEditor;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Compiled as an external addon; GL and mixin-backed calls are reserved for client smoke. */
public final class PublicRenderContracts
{
    public static void runAll() throws Exception
    {
        structureDataOwnership();
        renderOwnership();
        frameOverlays();
        panelToolOwnership();
        filmLifecycle();
        editorFactories();
        earlyAddonRegistration();

        System.out.println("PublicRenderContracts: ownership, overlays, film lifecycle, editor factories and early addon registration passed");
    }

    private static void earlyAddonRegistration() throws Exception
    {
        Field settingsField = BBSMod.class.getDeclaredField("settings");
        Field folderField = BBSMod.class.getDeclaredField("settingsFolder");
        Field formsField = BBSMod.class.getDeclaredField("forms");
        settingsField.setAccessible(true);
        folderField.setAccessible(true);
        formsField.setAccessible(true);
        Object previousSettings = settingsField.get(null);
        Object previousFolder = folderField.get(null);
        Object previousForms = formsField.get(null);
        Path folder = Files.createTempDirectory("bbs-api-early-");
        SettingsManager settings = new SettingsManager();
        EarlyAddon addon = new EarlyAddon();
        try
        {
            settingsField.set(null, settings);
            folderField.set(null, folder.toFile());

            /* A NeoForge mod constructor registers the FS marker before common setup.
             * API2's events facade runs later, after these early registry events. */
            EventBus.INSTANCE.register(addon);
            EventBus bus = new EventBus();
            AssetProvider provider = new AssetProvider();
            bus.post(new RegisterSourcePacksEvent(provider));
            check(provider.getSourceKeys().contains("fs26check"), "Early addon source pack was never registered");
            try (InputStream stream = provider.getAsset(Link.create("fs26check:probe.txt")))
            {
                check(stream != null && new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim().equals("fs26 addon source"),
                    "The addon source pack did not read the registering class's resources");
            }

            FormArchitect forms = new FormArchitect();
            formsField.set(null, forms);
            bus.post(new RegisterFormsEvent(forms));
            Form form = forms.create(Link.create("fs26check:form"));
            check(form instanceof EarlyForm && forms.fromData(forms.toData(form)) instanceof EarlyForm,
                "An early registered form did not reach create/load/save consumers");

            MapFactory<Clip, ClipFactoryData> camera = new MapFactory<>();
            MapFactory<Clip, ClipFactoryData> action = new MapFactory<>();
            bus.post(new RegisterCameraClipsEvent(camera));
            bus.post(new RegisterActionClipsEvent(action));
            check(camera.create(Link.create("fs26check:clip")) instanceof EarlyClip
                && action.create(Link.create("fs26check:clip")) instanceof EarlyClip,
                "An early clip subscriber did not reach both factory consumers");
            bus.post(new RegisterSettingsEvent());
            bus.post(new RegisterClientSettingsEvent());
            check(((ValueBoolean) settings.modules.get("fs26check").get("general", "enabled")).get()
                && ((ValueBoolean) settings.modules.get("fs26check_client").get("general", "enabled")).get(),
                "Common and client settings events did not build real settings modules");
            check(addon.order.equals(List.of("sources", "forms", "clips", "clips", "common-settings", "client-settings")),
                "Early marker registration lost events or delivered sibling settings twice: " + addon.order);
        }
        finally
        {
            addon.active = false;
            settingsField.set(null, previousSettings);
            folderField.set(null, previousFolder);
            formsField.set(null, previousForms);
            Files.deleteIfExists(folder.resolve("fs26check.json"));
            Files.deleteIfExists(folder.resolve("fs26check_client.json"));
            Files.deleteIfExists(folder);
        }
    }

    public static final class EarlyAddon implements BBSAddonMod
    {
        final List<String> order = new ArrayList<>();
        boolean active = true;

        @Subscribe
        public void sources(RegisterSourcePacksEvent event)
        {
            if (!this.active) return;
            event.registerAddon("fs26check", PublicRenderContracts.class);
            this.order.add("sources");
        }

        @Subscribe
        public void forms(RegisterFormsEvent event)
        {
            if (!this.active) return;
            event.forms.register(Link.create("fs26check:form"), EarlyForm.class);
            this.order.add("forms");
        }

        @Subscribe
        public void clips(BaseRegisterClipsEvent event)
        {
            if (!this.active) return;
            event.factory.register(Link.create("fs26check:clip"), EarlyClip.class);
            this.order.add("clips");
        }

        @Subscribe
        public void settings(BaseRegisterSettingsEvent event)
        {
            if (!this.active) return;
            boolean client = event instanceof RegisterClientSettingsEvent;
            event.register(Icons.GEAR, client ? "fs26check_client" : "fs26check", builder ->
            {
                builder.category("general");
                builder.getBoolean("enabled", true);
            });
            this.order.add(client ? "client-settings" : "common-settings");
        }
    }

    public static final class EarlyForm extends Form
    {}

    public static final class EarlyClip extends Clip
    {
        @Override protected Clip create() { return new EarlyClip(); }
    }

    private static void filmLifecycle()
    {
        List<String> order = new ArrayList<>();
        LifecycleController expected = new LifecycleController(order);
        ProbeWorldContext world = new ProbeWorldContext();
        FilmEvents.Created created = controller ->
        {
            check(controller == expected, "Film-created event lost its controller");
            order.add("created");
        };
        FilmEvents.Tick before = (controller, tick) ->
        {
            check(controller == expected && tick == 37, "Before-tick event lost its controller or tick");
            order.add("before");
        };
        FilmEvents.Tick after = (controller, tick) ->
        {
            check(controller == expected && tick == 37, "After-tick event lost its controller or tick");
            order.add("after");
        };
        FilmEvents.Render render = (controller, context) ->
        {
            check(controller == expected && context.camera() == world.camera
                && context.matrixStack() == world.stack && context.consumers() == world.consumers()
                && context.frustum() == world.frustum && context.tickDelta() == 0.375F
                && context.modelViewMatrix() == world.modelView && context.projectionMatrix() == world.projection,
                "Film-render event lost the active pass context");
            check(BBSWorldRenderContext.bridge(context) == context,
                "A compatible render context must preserve its identity");
            order.add("render-after");
        };
        FilmEvents.Shutdown shutdown = controller ->
        {
            check(controller == expected, "Film-shutdown event lost its controller");
            order.add("shutdown");
        };
        FilmEvents.CREATED.register(created);
        FilmEvents.TICK_BEFORE.register(before);
        FilmEvents.TICK_AFTER.register(after);
        FilmEvents.RENDER_AFTER.register(render);
        FilmEvents.SHUTDOWN.register(shutdown);
        try
        {
            expected.createEntities();
            expected.update();
            expected.render(world);
            expected.shutdown();
            check(order.equals(List.of("created", "before", "tick-scene", "after", "draw-scene", "render-after", "shutdown")),
                "Film lifecycle callbacks must surround the actual scene work in order: " + order);
        }
        finally
        {
            FilmEvents.CREATED.unregister(created);
            FilmEvents.TICK_BEFORE.unregister(before);
            FilmEvents.TICK_AFTER.unregister(after);
            FilmEvents.RENDER_AFTER.unregister(render);
            FilmEvents.SHUTDOWN.unregister(shutdown);
        }
    }

    private static void editorFactories() throws ReflectiveOperationException
    {
        List<CountingPanel> panels = new ArrayList<>();
        Consumer<UIForm<?>> panelFactory = editor ->
        {
            check(editor.form == null, "An addon panel must be created before the edited form is assigned");
            @SuppressWarnings("unchecked")
            UIForm<Form> owner = (UIForm<Form>) editor;
            CountingPanel panel = new CountingPanel(owner);
            owner.registerPanel(panel, IKey.constant("Addon panel"), Icons.GEAR);
            panels.add(panel);
        };
        new RegisterFormPanelsEvent().register(panelFactory);
        try
        {
            ToolForm first = new ToolForm();
            ToolForm second = new ToolForm();
            first.registerDefaults();
            second.registerDefaults();
            check(panels.size() == 2 && panels.get(0) != panels.get(1)
                && first.panels.get(0) == panels.get(0) && second.panels.get(0) == panels.get(1),
                "Each form editor needs its own addon panel before the shared panels");
            Form form = new AddonApiCheck.TestForm();
            first.startEdit(form, CountingPanel.class);
            first.finishEdit();
            check(panels.get(0).started == form && panels.get(0).finished == 1 && first.view == panels.get(0),
                "Registered addon panels must receive editor lifecycle and remain selectable");
        }
        finally
        {
            RegisterFormPanelsEvent.unregister(panelFactory);
        }

        FactoryFilmPanel filmPanel = allocate(FactoryFilmPanel.class);
        filmPanel.previewController = new UIFilmController(filmPanel, false);
        List<UIElement> overlays = new ArrayList<>();
        List<UIFilmPreview> owners = new ArrayList<>();
        Function<UIFilmPreview, UIElement> overlayFactory = preview ->
        {
            owners.add(preview);
            UIElement overlay = new UIElement();
            overlays.add(overlay);
            return overlay;
        };
        new RegisterPreviewOverlaysEvent().register(overlayFactory);
        try
        {
            UIFilmPreview first = new UIFilmPreview(filmPanel, new ViewDescriptor(filmPanel, "addon-main", true));
            UIFilmPreview second = new UIFilmPreview(filmPanel, new ViewDescriptor(filmPanel, "addon-side", false));
            check(owners.equals(List.of(first, second)) && overlays.get(0) != overlays.get(1)
                && first.getChildren().contains(overlays.get(0)) && second.getChildren().contains(overlays.get(1)),
                "Primary and auxiliary previews must attach their own addon overlay");
        }
        finally
        {
            RegisterPreviewOverlaysEvent.unregister(overlayFactory);
        }

        List<Supplier<Replay>> selections = new ArrayList<>();
        List<UIElement> actions = new ArrayList<>();
        BiFunction<UIFilmPanel, Supplier<Replay>, UIElement> actionFactory = (owner, selection) ->
        {
            check(owner == filmPanel && selection.get() == null, "Replay-action factory lost its film panel or initial selection");
            selections.add(selection);
            UIElement action = new UIElement();
            actions.add(action);
            return action;
        };
        new RegisterReplayActionsEvent().register(actionFactory);
        try
        {
            UIReplayPropertiesPanel panel = new UIReplayPropertiesPanel(filmPanel);
            Replay first = new Replay("first");
            Replay second = new Replay("second");
            check(panel.properties.getChildren().contains(actions.get(0)), "Replay actions must be attached to the properties panel");
            panel.setReplay(first);
            check(selections.get(0).get() == first, "Replay action did not observe the selected actor");
            panel.setReplay(second);
            check(selections.get(0).get() == second, "Replay action retained a stale actor selection");
            panel.setReplay(null);
            check(selections.get(0).get() == null, "Replay action did not observe cleared selection");
        }
        finally
        {
            RegisterReplayActionsEvent.unregister(actionFactory);
        }
    }

    private static final class LifecycleController extends BaseFilmController
    {
        private final List<String> order;

        LifecycleController(List<String> order)
        {
            super(new Film());
            this.order = order;
        }

        @Override public Map<String, Integer> getActors() { return Map.of(); }
        @Override public int getTick() { return 37; }
        @Override protected void updateScene(int tick) { this.order.add("tick-scene"); }
        @Override protected void renderScene(IBbsWorldRenderContext context) { this.order.add("draw-scene"); }
    }

    private static final class ProbeWorldContext implements IBbsWorldRenderContext
    {
        final Camera camera = new Camera();
        final PoseStack stack = new PoseStack();
        final Matrix4f modelView = new Matrix4f().translate(1F, 2F, 3F);
        final Matrix4f projection = new Matrix4f().scale(2F);
        final Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f());

        @Override public Camera camera() { return this.camera; }
        @Override public PoseStack matrixStack() { return this.stack; }
        @Override public MultiBufferSource.BufferSource consumers() { return null; }
        @Override public float tickDelta() { return 0.375F; }
        @Override public Frustum frustum() { return this.frustum; }
        @Override public Matrix4f modelViewMatrix() { return this.modelView; }
        @Override public Matrix4f projectionMatrix() { return this.projection; }
    }

    private static final class FactoryFilmPanel extends UIFilmPanel
    {
        UIFilmController previewController;

        FactoryFilmPanel() { super(null); }
        @Override public UIFilmController getController() { return this.previewController; }
        @Override public int getEditPanelTopOffsetPx() { return 0; }
    }

    private static final class CountingPanel extends UIFormPanel<Form>
    {
        Form started;
        int finished;

        CountingPanel(UIForm<Form> editor) { super(editor); }
        @Override public void startEdit(Form form) { this.started = form; }
        @Override public void finishEdit() { this.finished++; }
    }

    private static void structureDataOwnership()
    {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(1, 2, 3);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> entities = new LinkedHashMap<>();
        CompoundTag tag = new CompoundTag();
        tag.putInt("value", 3);
        blocks.put(position, Blocks.STONE.defaultBlockState());
        entities.put(position, tag);
        StructureRenderData data = StructureRenderPart.createData("addon:part", new Vec3i(4, 5, 6), blocks, entities);
        position.set(8, 9, 10);
        tag.putInt("value", 99);
        blocks.clear();
        check(data.getBlocks().containsKey(new BlockPos(1, 2, 3)) && data.getBlockEntities().get(new BlockPos(1, 2, 3)).getInt("value") == 3,
            "Structure part must own immutable positions and copied NBT");
        check(data.size.equals(new Vec3i(4, 5, 6)), "Structure part dimensions changed");
    }

    private static void renderOwnership()
    {
        List<Integer> called = new ArrayList<>();
        StructureRenderEvents.Render first = (renderer, form, data, context) -> { called.add(1); return false; };
        StructureRenderEvents.Render second = (renderer, form, data, context) -> { called.add(2); return true; };
        StructureRenderEvents.Render third = (renderer, form, data, context) -> { called.add(3); return true; };
        StructureRenderEvents.RENDER.register(first);
        StructureRenderEvents.RENDER.register(second);
        StructureRenderEvents.RENDER.register(third);
        try
        {
            check(StructureRenderEvents.RENDER.invoker().render(null, null, null, null) && called.equals(List.of(1, 2)),
                "The first structure renderer accepting ownership must stop later listeners");
        }
        finally
        {
            StructureRenderEvents.RENDER.unregister(first);
            StructureRenderEvents.RENDER.unregister(second);
            StructureRenderEvents.RENDER.unregister(third);
        }
    }

    @SuppressWarnings("unchecked")
    private static void frameOverlays() throws ReflectiveOperationException
    {
        Field field = FrameOverlays.class.getDeclaredField("RENDERERS");
        field.setAccessible(true);
        List<FrameOverlays.IFrameOverlayRenderer> renderers = (List<FrameOverlays.IFrameOverlayRenderer>) field.get(null);
        List<FrameOverlays.IFrameOverlayRenderer> previous = new ArrayList<>(renderers);
        renderers.clear();
        try
        {
            List<Integer> order = new ArrayList<>();
            PoseStack stack = new PoseStack();
            ClipContext context = new CameraClipContext();
            RegisterFrameOverlaysEvent event = new RegisterFrameOverlaysEvent();
            event.register((receivedStack, batcher, receivedContext) ->
            {
                check(receivedStack == stack && receivedContext == context, "Overlay lost the finished frame context");
                order.add(1);
            });
            event.register((receivedStack, batcher, receivedContext) -> order.add(2));
            FrameOverlays.render(stack, null, context);
            check(order.equals(List.of(1, 2)), "Frame overlay registration order changed");
        }
        finally
        {
            renderers.clear();
            renderers.addAll(previous);
        }
    }

    private static void panelToolOwnership() throws ReflectiveOperationException
    {
        ToolForm form = new ToolForm();
        ToolPanel panel = new ToolPanel(form);
        form.view = panel;
        check(form.getEditableTransform() == panel.transform && form.view == panel,
            "An addon panel tool must remain active when its transform is requested");

        ToolHost host = allocate(ToolHost.class);
        host.editor = form;
        host.statesEditor = new UIElement();
        host.statesEditor.setVisible(false);
        host.bodyPartEditor = allocate(UIBodyPartEditor.class);
        host.bodyPartEditor.transform = new UIPropTransform();
        check(host.getEditableTransform() == panel.transform && host.getOrigin(0F).equals(panel.origin),
            "Panel tool transform and origin must belong to the same owner");
        host.attachment = true;
        check(host.getEditableTransform() == host.bodyPartEditor.transform,
            "Attachment mode must select its transform before an addon panel tool");
    }

    private static final class ToolForm extends UIForm<Form>
    {
        void registerDefaults() { this.registerDefaultPanels(); }
    }

    private static final class ToolPanel extends UIFormPanel<Form> implements FormEditorTool
    {
        final UIPropTransform transform = new UIPropTransform();
        final Matrix4f origin = new Matrix4f().translate(1F, 2F, 3F);

        ToolPanel(UIForm<Form> form)
        {
            super(form);
        }

        @Override
        public UIPropTransform getGizmoTransform()
        {
            return this.transform;
        }

        @Override
        public Matrix4f getGizmoOrigin(float transition, TransformSpace space)
        {
            return this.origin;
        }
    }

    private static final class ToolHost extends UIFormEditor
    {
        boolean attachment;

        ToolHost()
        {
            super(null);
        }

        @Override
        public boolean isBodyPartGizmoMode()
        {
            return this.attachment;
        }
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException
    {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    /** Ensures an external consumer can compile the complete vanilla facade without mixins of its own. */
    private static void vanillaAccess(LivingEntity entity, ItemStack stack, Minecraft client, RenderTarget target)
    {
        VanillaAccess.getActiveItemStack(entity);
        VanillaAccess.setActiveItemStack(entity, stack);
        VanillaAccess.setItemUseTimeLeft(entity, VanillaAccess.getItemUseTimeLeft(entity));
        VanillaAccess.setLivingFlag(entity, 1, true);
        VanillaAccess.setRiptideTicks(entity, VanillaAccess.getRiptideTicks(entity));
        ClientVanillaAccess.getFramebuffer(client);
        ClientVanillaAccess.setFramebuffer(client, target);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
