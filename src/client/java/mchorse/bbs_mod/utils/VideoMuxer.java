package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Performs the post-recording FFmpeg pass used to merge captured Minecraft audio. */
public final class VideoMuxer
{
    public static final String DEFAULT_ARGUMENTS = "-y -i %VIDEO% -i %AUDIO_TRACK% -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k -shortest %NAME%.mp4";

    private VideoMuxer() {}

    public static File mux(File video, File audio, String movieName)
    {
        return mux(video, audio, movieName, DEFAULT_ARGUMENTS);
    }

    public static File mux(File video, File audio, String movieName, String arguments)
    {
        if (video == null || audio == null || !video.isFile() || !audio.isFile()) return null;
        File folder = video.getParentFile();
        String tempName = movieName + ".tmp";
        try
        {
            List<String> args = new ArrayList<>();
            args.add(FFMpegUtils.getFFMPEG());
            args.addAll(VideoExportUtils.resolveArguments(arguments, Map.of(
                "%VIDEO%", video.getName(),
                "%AUDIO_TRACK%", audio.getName(),
                "%NAME%", tempName
            )));
            ProcessBuilder builder = new ProcessBuilder(args).directory(folder).redirectErrorStream(true);
            File log = BBSSettings.videoEncoderLog.get() ? new File(folder, movieName + ".mux.log") : BBSMod.getSettingsPath("video.log");
            builder.redirectOutput(log);
            Process process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.MINUTES)) { process.destroyForcibly(); deleteTemp(folder, tempName); return null; }
            File merged = findTemp(folder, tempName);
            if (process.exitValue() != 0 || merged == null) { deleteTemp(folder, tempName); return null; }
            String extension = merged.getName().substring(tempName.length());
            File result = new File(folder, movieName + extension);
            try
            {
                Files.move(merged.toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!result.equals(video) && video.exists()) video.delete();
                return result;
            }
            catch (Exception ignored)
            {
                return merged;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            deleteTemp(folder, tempName);

            return null;
        }
        catch (Exception e) { deleteTemp(folder, tempName); return null; }
    }

    private static File findTemp(File folder, String name)
    {
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return null;
        String prefix = name + ".";
        for (File file : files) if (file.isFile() && file.getName().startsWith(prefix)) return file;
        return null;
    }

    private static void deleteTemp(File folder, String name)
    {
        File temp = findTemp(folder, name);
        if (temp != null) temp.delete();
    }
}
