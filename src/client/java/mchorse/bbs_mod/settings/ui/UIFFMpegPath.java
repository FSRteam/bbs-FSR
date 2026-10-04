package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.IFileDropConsumer;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.FFMpegDownloadManager;
import mchorse.bbs_mod.utils.FFMpegUtils;
import mchorse.bbs_mod.utils.OS;
import net.minecraft.client.Minecraft;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

public class UIFFMpegPath extends UIElement implements IFileDropConsumer
{
    private static final int CONTROL_WIDTH = 220;
    private static final String EXECUTABLE_NAME = OS.CURRENT == OS.WINDOWS ? "ffmpeg.exe" : "ffmpeg";

    private final ValueString value;
    private final FFMpegDownloadManager downloads;
    private final UITextbox textbox;
    private final UIIcon pick;
    private final UIIcon download;
    private final UILabel status;

    private ManualState manualState = ManualState.UNCHECKED;
    private long validationGeneration;
    private boolean showDownloadStatus;

    public UIFFMpegPath(ValueString value)
    {
        this.value = value;
        this.downloads = FFMpegDownloadManager.getInstance();
        this.textbox = UIValueFactory.stringUI(value, (text) ->
        {
            this.validationGeneration += 1L;
            this.manualState = ManualState.UNCHECKED;
            this.showDownloadStatus = false;
        });
        this.pick = new UIIcon(Icons.FOLDER, (button) -> this.pickFile());
        this.download = new UIIcon(Icons.DOWNLOAD, (button) -> this.download());
        this.status = new UILabel((IKey) this::getStatusText);

        FFMpegDownloadManager.Status downloadStatus = this.downloads.getStatus();

        this.showDownloadStatus = this.downloads.isBusy();

        if (downloadStatus.phase() == FFMpegDownloadManager.Phase.READY
            && downloadStatus.executable() != null && this.matchesValue(downloadStatus.executable()))
        {
            this.manualState = ManualState.READY;
        }

        this.textbox.removeTooltip();
        this.pick.tooltip(UIKeys.GENERAL_FFMPEG_SELECT);
        this.download.tooltip(UIKeys.GENERAL_FFMPEG_DOWNLOAD);
        this.status.labelAnchor(0F, 0.5F).h(12);

        UIElement controls = UI.row(2, this.textbox, this.pick, this.download);
        UIElement pathRow = new UIElement();
        UIElement statusRow = new UIElement();

        controls.w(CONTROL_WIDTH).h(20);
        pathRow.row(0).preferred(0).height(20);
        pathRow.add(UIValueFactory.label(value), controls);
        statusRow.row(0).preferred(0).height(12);
        statusRow.add(new UIElement(), this.status.w(CONTROL_WIDTH));

        this.column(2).vertical().stretch();
        this.add(pathRow, statusRow);
        UIValueFactory.commetTooltip(this, value);
    }

    @Override
    public boolean consumeFilePaths(String[] paths)
    {
        if (paths == null || paths.length != 1 || this.getContext() == null || this.downloads.isBusy())
        {
            return false;
        }

        try
        {
            Path dropped = Path.of(paths[0]).toAbsolutePath().normalize();
            File resolved = FFMpegUtils.resolveFFMPEG(dropped);

            if (!Files.isRegularFile(resolved.toPath()) || !resolved.getName().equalsIgnoreCase(EXECUTABLE_NAME))
            {
                return false;
            }

            this.validate(resolved.toPath());

            return true;
        }
        catch (InvalidPathException e)
        {
            return false;
        }
    }

    @Override
    public void render(UIContext context)
    {
        boolean busy = this.downloads.isBusy() || this.manualState == ManualState.CHECKING;

        if (this.downloads.isBusy())
        {
            this.showDownloadStatus = true;
        }

        this.textbox.setEnabled(!busy);
        this.pick.setEnabled(!busy);
        this.download.setEnabled(!busy && this.downloads.isSupported());
        this.status.color = this.getStatusColor();

        super.render(context);
    }

    private void pickFile()
    {
        String current = this.value.get();
        CharSequence defaultPath = current == null || current.isBlank() || current.equalsIgnoreCase("ffmpeg")
            ? EXECUTABLE_NAME
            : current;
        String result = TinyFileDialogs.tinyfd_openFileDialog(
            UIKeys.GENERAL_FFMPEG_SELECT.get(), defaultPath, null, null, false);

        if (result != null)
        {
            try
            {
                this.validate(Path.of(result));
            }
            catch (InvalidPathException e)
            {
                this.showDownloadStatus = false;
                this.manualState = ManualState.FAILED;
                this.notifyError(UIKeys.GENERAL_FFMPEG_STATUS_INVALID);
            }
        }
    }

    private void validate(Path candidate)
    {
        long generation = ++this.validationGeneration;

        this.showDownloadStatus = false;
        this.manualState = ManualState.CHECKING;
        this.downloads.validateCandidate(candidate).whenComplete((validation, error) ->
            Minecraft.getInstance().execute(() ->
            {
                if (generation != this.validationGeneration)
                {
                    return;
                }

                if (error == null && validation != null && validation.usable())
                {
                    String path = validation.executable().toAbsolutePath().normalize().toString();

                    this.value.set(path);
                    this.textbox.setText(path);
                    this.manualState = ManualState.READY;
                    this.notifySuccess(UIKeys.GENERAL_FFMPEG_STATUS_READY);
                }
                else
                {
                    this.manualState = ManualState.FAILED;
                    this.notifyError(UIKeys.GENERAL_FFMPEG_STATUS_INVALID);
                }
            }));
    }

    private void download()
    {
        this.validationGeneration += 1L;
        this.manualState = ManualState.UNCHECKED;
        this.showDownloadStatus = true;
        this.downloads.start().whenComplete((result, error) ->
            Minecraft.getInstance().execute(() ->
            {
                if (error == null && result != null)
                {
                    String path = result.executable().toAbsolutePath().normalize().toString();

                    this.value.set(path);
                    this.textbox.setText(path);
                    this.showDownloadStatus = false;
                    this.manualState = ManualState.READY;
                    this.notifySuccess(UIKeys.GENERAL_FFMPEG_STATUS_READY);
                }
                else
                {
                    this.notifyError(this.getFailureKey(this.downloads.getStatus().failure()));
                }
            }));
    }

    private String getStatusText()
    {
        FFMpegDownloadManager.Status downloadStatus = this.downloads.getStatus();

        if (this.showDownloadStatus && downloadStatus.phase() != FFMpegDownloadManager.Phase.IDLE)
        {
            return switch (downloadStatus.phase())
            {
                case CHECKING -> UIKeys.GENERAL_FFMPEG_STATUS_CHECKING.get();
                case DOWNLOADING -> UIKeys.GENERAL_FFMPEG_STATUS_DOWNLOADING.format(
                    progress(downloadStatus), formatSpeed(downloadStatus.bytesPerSecond()), formatEta(downloadStatus.etaSeconds())).get();
                case VERIFYING -> UIKeys.GENERAL_FFMPEG_STATUS_VERIFYING.get();
                case INSTALLING -> UIKeys.GENERAL_FFMPEG_STATUS_INSTALLING.get();
                case READY -> UIKeys.GENERAL_FFMPEG_STATUS_READY.get();
                case FAILED, UNSUPPORTED -> this.getFailureKey(downloadStatus.failure()).get();
                case IDLE -> UIKeys.GENERAL_FFMPEG_STATUS_UNCHECKED.get();
            };
        }

        return switch (this.manualState)
        {
            case UNCHECKED -> UIKeys.GENERAL_FFMPEG_STATUS_UNCHECKED.get();
            case CHECKING -> UIKeys.GENERAL_FFMPEG_STATUS_CHECKING.get();
            case READY -> UIKeys.GENERAL_FFMPEG_STATUS_READY.get();
            case FAILED -> UIKeys.GENERAL_FFMPEG_STATUS_INVALID.get();
        };
    }

    private int getStatusColor()
    {
        FFMpegDownloadManager.Phase phase = this.downloads.getStatus().phase();

        if ((this.showDownloadStatus && phase == FFMpegDownloadManager.Phase.READY)
            || (!this.showDownloadStatus && this.manualState == ManualState.READY))
        {
            return BBSSettings.positiveColor();
        }
        else if ((this.showDownloadStatus
            && (phase == FFMpegDownloadManager.Phase.FAILED || phase == FFMpegDownloadManager.Phase.UNSUPPORTED))
            || (!this.showDownloadStatus && this.manualState == ManualState.FAILED))
        {
            return BBSSettings.negativeColor();
        }
        else if ((this.showDownloadStatus && phase != FFMpegDownloadManager.Phase.IDLE)
            || (!this.showDownloadStatus && this.manualState == ManualState.CHECKING))
        {
            return BBSSettings.warningColor();
        }

        return BBSSettings.mutedTextColor();
    }

    private boolean matchesValue(Path path)
    {
        try
        {
            return path.toAbsolutePath().normalize().equals(Path.of(this.value.get()).toAbsolutePath().normalize());
        }
        catch (InvalidPathException | NullPointerException e)
        {
            return false;
        }
    }

    private IKey getFailureKey(FFMpegDownloadManager.Failure failure)
    {
        return switch (failure)
        {
            case UNSUPPORTED_PLATFORM -> UIKeys.GENERAL_FFMPEG_STATUS_UNSUPPORTED;
            case NETWORK -> UIKeys.GENERAL_FFMPEG_STATUS_NETWORK_ERROR;
            case CHECKSUM -> UIKeys.GENERAL_FFMPEG_STATUS_CHECKSUM_ERROR;
            case VALIDATION -> UIKeys.GENERAL_FFMPEG_STATUS_INVALID;
            case ARCHIVE, FILESYSTEM -> UIKeys.GENERAL_FFMPEG_STATUS_INSTALL_ERROR;
            case NONE -> UIKeys.GENERAL_FFMPEG_STATUS_INVALID;
        };
    }

    private void notifySuccess(IKey key)
    {
        UIContext context = this.getContext();

        if (context != null)
        {
            context.notifySuccess(key);
        }
    }

    private void notifyError(IKey key)
    {
        UIContext context = this.getContext();

        if (context != null)
        {
            context.notifyError(key);
        }
    }

    private static String progress(FFMpegDownloadManager.Status status)
    {
        if (status.totalBytes() <= 0L)
        {
            return "0%";
        }

        double percent = Math.min(100D, Math.max(0D, status.downloadedBytes() * 100D / status.totalBytes()));

        return String.format(Locale.ROOT, "%.0f%%", percent);
    }

    private static String formatSpeed(double bytesPerSecond)
    {
        if (bytesPerSecond < 1024D)
        {
            return String.format(Locale.ROOT, "%.0f B/s", Math.max(bytesPerSecond, 0D));
        }

        double kib = bytesPerSecond / 1024D;

        if (kib < 1024D)
        {
            return String.format(Locale.ROOT, "%.1f KiB/s", kib);
        }

        return String.format(Locale.ROOT, "%.1f MiB/s", kib / 1024D);
    }

    private static String formatEta(long seconds)
    {
        if (seconds < 0L)
        {
            return "--:--";
        }

        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long remaining = seconds % 60L;

        return hours > 0L
            ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
            : String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }

    private enum ManualState
    {
        UNCHECKED,
        CHECKING,
        READY,
        FAILED
    }
}
