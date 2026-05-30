package mchorse.bbs_mod.ui.framework;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.importers.IImportPathProvider;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.Importers;
import mchorse.bbs_mod.importers.types.IImporter;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.input.text.UIBaseTextbox;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.utils.IFileDropListener;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.FFMpegUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class UIScreen extends Screen implements IFileDropListener
{
    private UIBaseMenu menu;
    private UIRenderingContext context;

    private int lastGuiScale;

    public static void open(UIBaseMenu menu)
    {
        Minecraft.getInstance().setScreen(new UIScreen(Component.empty(), menu));
    }

    public static UIBaseMenu getCurrentMenu()
    {
        Screen currentScreen = Minecraft.getInstance().screen;

        if (currentScreen instanceof UIScreen uiScreen)
        {
            return uiScreen.menu;
        }

        return null;
    }

    public UIScreen(Component title, UIBaseMenu menu)
    {
        super(title);

        Minecraft mc = Minecraft.getInstance();

        this.menu = menu;
        this.context = new UIRenderingContext(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));

        this.menu.context.setup(this.context);
    }

    public UIBaseMenu getMenu()
    {
        return this.menu;
    }

    public void update()
    {
        this.menu.update();
    }

    public void renderInWorld(IBbsWorldRenderContext context)
    {
        this.menu.renderInWorld(context);
    }

    @Override
    public void onFilesDrop(List<Path> paths)
    {
        super.onFilesDrop(paths);

        String[] filePaths = new String[paths.size()];
        int i = 0;

        for (Path path : paths)
        {
            filePaths[i] = path.toAbsolutePath().toString();

            i += 1;
        }

        this.acceptFilePaths(filePaths);
    }

    @Override
    public void removed()
    {
        Minecraft mc = Minecraft.getInstance();

        mc.options.guiScale().set(this.lastGuiScale);
        mc.resizeDisplay();

        super.removed();

        this.menu.onClose(null);

        if (this.menu.canHideHUD())
        {
            mc.options.hideGui = false;
        }
    }

    @Override
    public void added()
    {
        Minecraft mc = Minecraft.getInstance();

        this.lastGuiScale = mc.options.guiScale().get();

        mc.options.guiScale().set(BBSModClient.getGUIScale());
        mc.resizeDisplay();

        super.added();

        this.menu.onOpen(null);

        if (this.menu.canHideHUD())
        {
            mc.options.hideGui = true;
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return this.menu.canPause();
    }

    @Override
    protected void init()
    {
        super.init();

        this.menu.resize(this.width, this.height);
    }

    @Override
    public void resize(Minecraft client, int width, int height)
    {
        /* MC 1.21.1: Screen.resize() no longer assigns this.minecraft
         * (only Screen.init() does).  During added() → guiScale().set()
         * → resizeDisplay() → resize(), the field is still null because
         * Minecraft.setScreen() calls init() AFTER added(). */
        this.minecraft = client;

        super.resize(client, width, height);

        this.menu.resize(width, height);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        return this.menu.mouseClicked((int) mouseX, (int) mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        return this.menu.mouseScrolled((int) mouseX, (int) mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        return this.menu.mouseReleased((int) mouseX, (int) mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        /* Disable long-press repeat (GLFW_REPEAT) in BBS panel.
         * MC 1.21.1 calls keyPressed() for both PRESS and REPEAT,
         * but original BBS only processes initial PRESS. */
        if (BBSRendering.lastAction == GLFW.GLFW_REPEAT && !this.canFocusedElementRepeatKeys())
        {
            return false;
        }

        return this.menu.handleKey(keyCode, scanCode, BBSRendering.lastAction, modifiers);
    }

    private boolean canFocusedElementRepeatKeys()
    {
        return this.menu.context.activeElement instanceof UIBaseTextbox
            || this.menu.context.activeElement instanceof UITextarea;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers)
    {
        return this.menu.handleKey(keyCode, scanCode, GLFW.GLFW_RELEASE, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers)
    {
        this.menu.handleTextInput(chr);

        return true;
    }

    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta)
    {}

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta)
    {
        super.render(context, mouseX, mouseY, delta);

        this.context.setContext(context);
        this.menu.context.setTransition(delta);
        this.menu.renderMenu(this.context, mouseX, mouseY);
        this.menu.context.render.executeRunnables();
    }

    @Override
    public void acceptFilePaths(String[] paths)
    {
        if (this.menu != null)
        {
            if (!FFMpegUtils.checkFFMPEG())
            {
                this.menu.context.notifyError(UIKeys.IMPORTER_FFMPEG_NOTIFICATION);

                return;
            }

            File directory = null;
            boolean open = true;

            for (IImportPathProvider provider : this.menu.getRoot().getChildren(IImportPathProvider.class))
            {
                directory = provider.getImporterPath();

                if (directory != null)
                {
                    open = false;

                    break;
                }
            }

            List<File> files = new ArrayList<>();

            for (String path : paths)
            {
                File file = new File(path);

                if (file.exists())
                {
                    files.add(file);
                }
            }

            ImporterContext context = new ImporterContext(files, directory);

            for (IImporter importer : Importers.getImporters())
            {
                if (importer.canImport(context))
                {
                    importer.importFiles(context);

                    if (open)
                    {
                        UIUtils.openFolder(context.getDestination(importer));
                    }

                    this.menu.context.notifySuccess(UIKeys.IMPORTER_SUCCESS_NOTIFICATION.format(importer.getName()));

                    return;
                }
            }
        }
    }
}
