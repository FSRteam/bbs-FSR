package mchorse.bbs_mod.ui.dashboard.textures;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.DynamicSourcePack;
import mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack;
import mchorse.bbs_mod.settings.values.ui.ValueEditorLayout;
import mchorse.bbs_mod.ui.dashboard.textures.data.Document;
import mchorse.bbs_mod.ui.dashboard.textures.data.TextureAnimation;
import mchorse.bbs_mod.ui.dashboard.textures.data.TextureLayer;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.utils.undo.UndoManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Real painter consumers and persistence; --gl additionally exercises native layer resources. */
public final class TextureEditorParityTest
{
    private TextureEditorParityTest()
    {}

    public static void main(String[] args) throws Exception
    {
        Method install = Class.forName("mchorse.bbs_mod.ui.film.replays.ReplayIndexRemappingTest")
            .getDeclaredMethod("installHeadlessClientRuntime");
        install.setAccessible(true);
        Runnable restore = (Runnable) install.invoke(null);

        try
        {
            runAll();

            if (List.of(args).contains("--gl"))
            {
                runWithGl();
            }
        }
        finally
        {
            restore.run();
        }
    }

    public static void runAll()
    {
        try (Fixture fixture = new Fixture())
        {
            testAnimationSidecar(fixture);
            testPainterChrome(fixture);
        }
        catch (Exception exception)
        {
            throw new AssertionError("Texture editor parity failed", exception);
        }

        System.out.println("TextureEditorParity: animation sidecar, shared actions, layout persistence and model picker passed");
    }

    private static MapType metadata()
    {
        return DataToString.mapFromString("{\"animation\":{\"frametime\":2,\"height\":2,\"interpolate\":true,\"addon\":\"kept\",\"frames\":[0,{\"index\":1,\"time\":5},1,2]},\"texture\":{\"blur\":true}}");
    }

    private static void testAnimationSidecar(Fixture fixture) throws Exception
    {
        TextureAnimation animation = TextureAnimation.fromMcmeta(metadata(), 2, 6);
        check(animation.frames.size() == 4 && animation.imageCount(2, 6) == 3, "frames were confused with strip images");
        check(animation.length() == 11 && animation.frameAt(2) == 1 && animation.frameAt(-1) == 3, "frame duration or looping changed");
        File texture = fixture.directory.resolve("metadata.png").toFile();
        check(animation.write(texture, 2, 6), "mcmeta was not written");
        TextureAnimation restored = TextureAnimation.read(Link.assets(texture.getName()), 2, 6);
        check(restored != null && restored.frames.get(1).time == 5, "provider did not reload frame overrides");
        MapType roundTrip = restored.toMcmeta(2, 6);
        check(roundTrip.getMap("animation").getBool("interpolate") && roundTrip.getMap("animation").getString("addon").equals("kept"), "unknown animation metadata was lost");
        check(roundTrip.getMap("texture").getBool("blur"), "another mcmeta section was lost");
        TextureAnimation plain = TextureAnimation.fromMcmeta(DataToString.mapFromString("{\"animation\":{}}"), 2, 6);
        check(plain.isDefaultSequence(2, 6) && !plain.toMcmeta(2, 6).getMap("animation").has("frames"), "implicit frames were unnecessarily serialized");
    }

    private static void testPainterChrome(Fixture fixture) throws Exception
    {
        UITexturePicker picker = new UITexturePicker(null);
        fixture.menu.main.add(picker);
        picker.full(fixture.menu.main);
        fixture.menu.getRoot().resize();
        check(picker.tabs.getParent() == picker.topBar, "tabs do not belong to the shared top bar");
        check(picker.painter.savebar == picker.topBar.actions && picker.topBar.actions.getContentWidth() == 0, "action alias or browser visibility changed");
        picker.painter.setActionsVisible(true);
        check(picker.painter.saveIcon.getParent() == picker.topBar.actions && picker.painter.macrosIcon.getParent() == picker.topBar.actions, "actions still live in the tool palette");
        picker.painter.setActionsVisible(false);
        check(picker.topBar.actions.getContentWidth() == 0, "hidden painter actions still reserve tab space");

        UITexturePainter painter = picker.painter;
        UISplitter options = (UISplitter) field(painter, "optionsDraggable");
        UISplitter preview = (UISplitter) field(painter, "modelPreviewDraggable");
        UISplitter frames = (UISplitter) field(painter, "framesDraggable");
        options.setValue(0.26F);
        preview.setValue(0.4F);
        frames.setValue(123);
        ValueEditorLayout saved = new ValueEditorLayout("layout");
        saved.fromData(BBSSettings.editorLayoutSettings.toData());
        BBSSettings.editorLayoutSettings = saved;
        UITexturePainter reopened = new UITexturePainter(null);
        check(((UISplitter) field(reopened, "optionsDraggable")).getValue() == 0.26F, "options width was not persisted");
        check(((UISplitter) field(reopened, "modelPreviewDraggable")).getValue() == 0.4F, "preview width was not persisted");
        check(((UISplitter) field(reopened, "framesDraggable")).getPixels() == 123, "frame strip height was not persisted");
        options.reset();
        check(BBSSettings.editorLayoutSettings.getSplitSize("texture_painter.options", 0F) == 0.2F, "reset did not persist the default");

        painter.openModelPreview("example:second");
        UIElement modelHost = (UIElement) field(painter, "modelPreviewHost");
        UIElement optionsHost = (UIElement) field(painter, "optionsHost");
        UIElement canvas = (UIElement) field(painter, "editorHost");
        check(modelHost.area.x < canvas.area.x && canvas.area.ex() < optionsHost.area.ex(), "preview/options are on the old sides");
        painter.openModelPreview();
        List<UIListOverlayPanel> overlays = fixture.menu.overlay.getChildren(UIListOverlayPanel.class);
        check(overlays.size() == 1 && overlays.get(0).list.list.getCurrent().equals(List.of("example:second")), "model picker did not select the model on show");
        painter.closeModelPreview();
        painter.openModelPreview("example:first");
        check(modelHost.area.w == 400, "reopening the preview discarded its remembered width");
        painter.closeModelPreview();
        picker.removeFromParent();
    }

    private static void runWithGl() throws Exception
    {
        try (GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err))
        {
            errors.set();
            check(GLFW.glfwInit(), "GLFW could not initialize");
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            long window = GLFW.glfwCreateWindow(32, 32, "Texture parity", 0L, 0L);
            check(window != 0L, "hidden OpenGL context could not be created");

            try
            {
                GLFW.glfwMakeContextCurrent(window);
                GL.createCapabilities();
                RenderSystem.initRenderThread();
                System.out.println("TextureEditorParity GL: " + GL11.glGetString(GL11.GL_VERSION));
                try (Fixture fixture = new Fixture())
                {
                    testTextureBands();
                    testEditingAndSidecars(fixture);
                }
                check(GL11.glGetError() == GL11.GL_NO_ERROR, "texture operations left an OpenGL error");
            }
            finally
            {
                GL.setCapabilities(null);
                GLFW.glfwDestroyWindow(window);
                GLFW.glfwTerminate();
            }
        }
        System.out.println("TextureEditorParity GL: RGB bands, frame macros, duplication/removal, undo/redo, shared tabs and PNG/dat/mcmeta passed");
    }

    @SuppressWarnings("unchecked")
    private static void testTextureBands() throws Exception
    {
        Field limit = TextureLayer.class.getDeclaredField("maxBandHeight");
        limit.setAccessible(true);
        int previous = limit.getInt(null);
        limit.setInt(null, 2);
        Pixels rgb = new Pixels(MemoryUtil.memAlloc(3 * 3 * 5), 3, 5, 3);
        for (int i = 0; i < rgb.getCount(); i++)
        {
            rgb.setColor(i, new Color().set(0xFF000000 | (i + 1) * 0x010307, true));
        }
        TextureLayer layer = null;
        try
        {
            layer = new TextureLayer("RGB", rgb);
            List<Texture> bands = (List<Texture>) field(layer, "bands");
            check(bands.size() == 3 && layer.texture == null, "tall texture was not split into bands");
            for (int b = 0; b < bands.size(); b++)
            {
                Pixels read = Texture.pixelsFromTexture(bands.get(b));
                try
                {
                    for (int i = 0; i < read.getCount(); i++)
                    {
                        check(read.getColor(i).getARGBColor() == rgb.getColor(b * 6 + i).getARGBColor(), "odd-width RGB band upload changed pixels");
                    }
                }
                finally
                {
                    read.delete();
                }
            }
            List<Integer> ids = bands.stream().map(texture -> texture.id).toList();
            layer.delete();
            layer.delete();
            check(layer.pixels == null && ids.stream().noneMatch(GL11::glIsTexture), "layer resources survived deletion");
        }
        finally
        {
            if (layer != null) layer.delete();
            else rgb.delete();
            limit.setInt(null, previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testEditingAndSidecars(Fixture fixture) throws Exception
    {
        Pixels pixels = Pixels.fromIntArray(2, 6, new int[] {0x80402010, 0xFF102030, 0xFF203040, 0xFF304050, 0x80506070, 0xFF607080, 0xFF708090, 0xFF8090A0, 0xFF90A0B0, 0xFFA0B0C0, 0xFFB0C0D0, 0xFFC0D0E0});
        Document document = Document.fromPixels(Link.assets("strip.png"), pixels);
        document.animation = TextureAnimation.fromMcmeta(metadata(), 2, 6);
        UITextureEditor editor = new UITextureEditor();
        editor.setDocument(document);
        editor.setEditing(true);
        UndoManager<Document> history = (UndoManager<Document>) field(editor, "undoManager");
        try
        {
            int original = editor.getPixels().getColor(0, 2).getARGBColor();
            int mirrored = editor.getPixels().getColor(1, 2).getARGBColor();
            editor.applyMacroToFrames(PixelMacro.FLIP_HORIZONTAL, List.of(document.animation.frames.get(1), document.animation.frames.get(2)));
            check(editor.getPixels().getColor(0, 2).getARGBColor() == mirrored, "shared image was flipped twice");
            check(history.undo(document) && editor.getPixels().getColor(0, 2).getARGBColor() == original, "macro undo failed");
            check(history.redo(document) && editor.getPixels().getColor(0, 2).getARGBColor() == mirrored, "macro redo failed");
            editor.setFrame(2);
            check(editor.getFrameY() == 2 && editor.getWidth() == 2 && editor.getHeight() == 2, "frame window does not follow the order");
            editor.applyMacroToWindow(PixelMacro.CLEAR);
            check(editor.getPixels().getColor(0, 2).a == 0F && editor.getPixels().getColor(0, 0).getARGBColor() == 0x80402010, "window macro changed another image");
            check(history.undo(document), "window macro was not undoable");

            editor.duplicateFrames(List.of(document.animation.frames.get(1)));
            check(document.height == 8 && document.animation.frames.size() == 5, "frame duplication did not append one image");
            check(editor.getPixels().getColor(0, 6).getARGBColor() == mirrored && editor.getPixels().getColor(0, 0).getARGBColor() == 0x80402010, "resize/duplication blended existing translucent pixels");
            Texture replaced = document.getActiveLayer().texture;
            check(history.undo(document) && document.height == 6 && !replaced.isValid(), "structural undo did not release replaced GPU data");
            check(history.redo(document) && document.height == 8 && editor.getPixels() == document.getActiveLayer().pixels, "redo kept a stale active-layer cache");
            check(editor.removeFrames(List.of(document.animation.frames.get(2))) && document.height == 6, "unused image was not removed");
            check(!editor.removeFrames(List.copyOf(document.animation.frames)), "all frames could be removed");
            editor.setFrametime(5);
            editor.setFrametime(7);
            check(history.undo(document) && document.animation.frametime == 2, "continuous frametime edits did not merge");
            check(history.redo(document) && document.animation.frametime == 7, "merged frametime redo failed");

            TextureLayer mask = new TextureLayer("Mask", Pixels.fromSize(2, 6));
            mask.visible = false;
            mask.opacity = 0.5F;
            mask.offsetX = 1;
            document.layers.add(mask);
            editor.setActiveLayer(1);
            File file = editor.saveTo(Link.assets("strip.png"));
            check(file != null && file.isFile() && Document.datFile(file).isFile() && TextureAnimation.file(file).isFile(), "PNG/project/animation were not saved together");
            UITexturePainter loader = new UITexturePainter(null);
            UITextureEditor loaded = loader.openEditor(Link.assets("strip.png"));
            check(loaded != null && loaded.getDocument().layers.size() == 2 && loaded.getDocument().activeLayerIndex == 1, "project layer stack was not reloaded");
            check(loaded.getDocument().layers.get(1).offsetX == 1 && loaded.getDocument().layers.get(1).opacity == 0.5F && !loaded.getDocument().layers.get(1).visible, "project layer properties changed");
            check(loaded.getDocument().animation.frametime == 7 && loaded.getDocument().animation.toMcmeta(2, 6).getMap("animation").getString("addon").equals("kept"), "animation was not reloaded independently of the project");
            loaded.deleteTexture();
            loaded.deleteTexture();

            UITexturePicker picker = new UITexturePicker(null);
            fixture.menu.main.add(picker);
            picker.full(fixture.menu.main);
            int tab = picker.getTabCount();
            picker.openTexture(Link.assets("strip.png"));
            check(picker.getCurrentTab() == tab && picker.painter.saveIcon.getParent() == picker.topBar.actions, "opening a real texture did not show its actions");
            UITexturePicker peer = new UITexturePicker(null);
            fixture.menu.main.add(peer);
            peer.switchTab(tab);
            check(peer.painter.getCurrentEditor() == picker.painter.getCurrentEditor(), "pickers no longer share one editor");
            peer.switchTab(0);
            check(peer.topBar.actions.getContentWidth() == 0, "browser tab retained painter actions");
            peer.closeTab(tab);
            picker.removeFromParent();
            peer.removeFromParent();

            editor.setAnimated(false);
            check(editor.saveTo(Link.assets("strip.png")) != null && !TextureAnimation.file(file).exists(), "disabling animation did not remove mcmeta on save");
        }
        finally
        {
            editor.deleteTexture();
            editor.deleteTexture();
        }
    }

    private static Object field(Object instance, String name) throws Exception
    {
        Class<?> type = instance.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            }
            catch (NoSuchFieldException exception)
            {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable
    {
        private final Path directory = Files.createTempDirectory("bbs-texture-parity-");
        private final Map<Field, Object> previous = new LinkedHashMap<>();
        private final ValueEditorLayout previousLayout = BBSSettings.editorLayoutSettings;
        private final UIBaseMenu menu = new UIBaseMenu() {};

        private Fixture() throws Exception
        {
            AssetProvider provider = new AssetProvider();
            ExternalAssetsSourcePack source = new ExternalAssetsSourcePack(Link.ASSETS, this.directory.toFile()).providesFiles();
            DynamicSourcePack dynamic = new DynamicSourcePack(source);
            provider.register(dynamic);
            this.replace(BBSMod.class, "provider", provider);
            this.replace(BBSMod.class, "dynamicSourcePack", dynamic);
            this.replace(BBSModClient.class, "textures", new TextureManager(provider));
            this.replace(BBSModClient.class, "models", new ModelManager(provider)
            {
                @Override
                public List<String> getAvailableKeys()
                {
                    return List.of("example:first", "example:second");
                }
            });
            BBSSettings.editorLayoutSettings = new ValueEditorLayout("layout");
            this.menu.viewport.set(0, 0, 1000, 700);
            this.menu.getRoot().resize();
        }

        private void replace(Class<?> type, String name, Object value) throws Exception
        {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            this.previous.put(field, field.get(null));
            field.set(null, value);
        }

        @Override
        public void close() throws Exception
        {
            BBSSettings.editorLayoutSettings = this.previousLayout;
            for (Map.Entry<Field, Object> entry : this.previous.entrySet())
            {
                entry.getKey().set(null, entry.getValue());
            }
            try (Stream<Path> files = Files.list(this.directory))
            {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(this.directory);
        }
    }
}
