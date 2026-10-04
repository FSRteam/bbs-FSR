package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.l10n.keys.IKey;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Non-blocking wrapper around the native tinyfd pickers. */
public final class UIFileDialogs
{
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private UIFileDialogs() {}

    public static void pickFolder(IKey title, File folder, Consumer<File> callback)
    {
        String titleText = title.get();
        String path = folder == null ? "" : folder.getAbsolutePath();
        open(() -> TinyFileDialogs.tinyfd_selectFolderDialog(titleText, path), callback);
    }

    public static void pickFile(IKey title, File file, String[] patterns, IKey description, Consumer<File> callback)
    {
        String titleText = title.get();
        String path = file == null ? "" : file.getAbsolutePath();
        String descriptionText = description == null ? null : description.get();

        open(() ->
        {
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                PointerBuffer filters = null;
                if (patterns != null && patterns.length > 0)
                {
                    filters = stack.mallocPointer(patterns.length);
                    for (String pattern : patterns)
                    {
                        filters.put(stack.UTF8(pattern));
                    }
                    filters.flip();
                }

                return TinyFileDialogs.tinyfd_openFileDialog(titleText, path, filters, descriptionText, false);
            }
        }, callback);
    }

    private static void open(Supplier<String> dialog, Consumer<File> callback)
    {
        if (!OPEN.compareAndSet(false, true))
        {
            return;
        }

        Thread thread = new Thread(() ->
        {
            String picked = null;
            try
            {
                TinyFileDialogs.tinyfd_setGlobalInt(TinyFileDialogs.tinyfd_winUtf8, 1);
                picked = dialog.get();
            }
            catch (Throwable error)
            {
                error.printStackTrace();
            }
            finally
            {
                OPEN.set(false);
            }

            if (picked != null && !picked.isEmpty())
            {
                File result = new File(picked);
                Minecraft.getInstance().execute(() -> callback.accept(result));
            }
        }, "BBS file dialog");
        thread.setDaemon(true);
        thread.start();
    }
}
