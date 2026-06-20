package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

public class ValueVideoSettings extends ValueGroup
{
    public static final String DEFAULT_FFMPEG_ARGUMENTS = "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf %FILTERS% -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p %NAME%.mp4";
    public static final String DEFAULT_AUDIO_FFMPEG_ARGUMENTS = "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -i %AUDIO_TRACK% -vf %FILTERS% -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p -c:a aac -b:a 128k -shortest %NAME%.mp4";

    public final ValueString arguments;
    public final ValueString argumentsAudio;
    public final ValueBoolean audio;
    public final ValueInt width;
    public final ValueInt height;
    public final ValueInt frameRate;
    public final ValueInt motionBlur;
    public final ValueInt heldFrames;
    public final ValueFloat delay;
    public final ValueString path;
    public final ValueBoolean openFolderAfterExport;
    public final ValueBoolean playSoundAfterExport;

    public ValueVideoSettings(String id)
    {
        this(
            id,
            new ValueString("arguments", DEFAULT_FFMPEG_ARGUMENTS),
            new ValueString("arguments_audio", DEFAULT_AUDIO_FFMPEG_ARGUMENTS),
            new ValueBoolean("audio", false),
            new ValueInt("width", 1280, 2, 8096),
            new ValueInt("height", 720, 2, 8096),
            new ValueInt("frameRate", 60, 10, 1000),
            new ValueInt("motionBlur", 0, 0, 6),
            new ValueInt("heldFrames", 1, 1, 1000),
            new ValueFloat("delay", 0.5F, 0F, 30F),
            new ValueString("exportPath", ""),
            new ValueBoolean("openFolderAfterExport", false),
            new ValueBoolean("playSoundAfterExport", true),
            true
        );
    }

    public static ValueVideoSettings bridge(
        String id,
        ValueString arguments,
        ValueString argumentsAudio,
        ValueBoolean audio,
        ValueInt width,
        ValueInt height,
        ValueInt frameRate,
        ValueInt motionBlur,
        ValueInt heldFrames,
        ValueFloat delay,
        ValueString path,
        ValueBoolean openFolderAfterExport,
        ValueBoolean playSoundAfterExport)
    {
        return new ValueVideoSettings(
            id,
            arguments,
            argumentsAudio,
            audio,
            width,
            height,
            frameRate,
            motionBlur,
            heldFrames,
            delay,
            path,
            openFolderAfterExport,
            playSoundAfterExport,
            false
        );
    }

    private ValueVideoSettings(
        String id,
        ValueString arguments,
        ValueString argumentsAudio,
        ValueBoolean audio,
        ValueInt width,
        ValueInt height,
        ValueInt frameRate,
        ValueInt motionBlur,
        ValueInt heldFrames,
        ValueFloat delay,
        ValueString path,
        ValueBoolean openFolderAfterExport,
        ValueBoolean playSoundAfterExport,
        boolean registerValues)
    {
        super(id);

        this.arguments = arguments;
        this.argumentsAudio = argumentsAudio;
        this.audio = audio;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.motionBlur = motionBlur;
        this.heldFrames = heldFrames;
        this.delay = delay;
        this.path = path;
        this.openFolderAfterExport = openFolderAfterExport;
        this.playSoundAfterExport = playSoundAfterExport;

        if (registerValues)
        {
            this.add(this.arguments);
            this.add(this.argumentsAudio);
            this.add(this.audio);
            this.add(this.width);
            this.add(this.height);
            this.add(this.frameRate);
            this.add(this.motionBlur);
            this.add(this.heldFrames);
            this.add(this.delay);
            this.add(this.path);
            this.add(this.openFolderAfterExport);
            this.add(this.playSoundAfterExport);
        }
    }
}
