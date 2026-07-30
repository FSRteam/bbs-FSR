package mchorse.bbs_mod.client.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression checks for films that retain clips from an unavailable plugin. */
final class MissingClipTimelineFallbackTest
{
    private MissingClipTimelineFallbackTest()
    {}

    static void runAll()
    {
        String clips = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIClips.java");
        String renderer = readSource("src/client/java/mchorse/bbs_mod/ui/film/clips/renderer/UIClipRenderer.java");
        String replays = readSource("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java");

        check(clips.contains("private static final ClipFactoryData MISSING_CLIP_DATA")
                && clips.contains("return data == null ? MISSING_CLIP_DATA : data;"),
            "the film timeline no longer supplies stable metadata for unavailable plugin clips");
        check(clips.contains("clip instanceof MissingClip missing")
                && clips.contains("CAMERA_TIMELINE_MISSING_CLIP.format(missing.typeId())"),
            "unavailable plugin clips no longer expose their original type in the timeline");
        check(renderer.contains("clips.getClipFactoryData(clip)")
                && replays.contains("clipsPanel.clips.getClipFactoryData(clip)"),
            "a timeline renderer can still dereference missing plugin registration data");
        check(occurrences(clips, "if (data == null)") >= 2
                && clips.contains("if (targetData != null)")
                && clips.contains("if (converter == null)"),
            "clip conversion actions can still use an unloaded plugin generation");
    }

    private static int occurrences(String source, String value)
    {
        int count = 0;
        int index = 0;

        while ((index = source.indexOf(value, index)) >= 0)
        {
            count += 1;
            index += value.length();
        }

        return count;
    }

    private static String readSource(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            Path source = current.resolve(relativePath);

            if (Files.isRegularFile(source))
            {
                try
                {
                    return Files.readString(source);
                }
                catch (IOException exception)
                {
                    throw new AssertionError("could not read " + source, exception);
                }
            }

            Path nestedSource = current.resolve("new").resolve(relativePath);

            if (Files.isRegularFile(nestedSource))
            {
                try
                {
                    return Files.readString(nestedSource);
                }
                catch (IOException exception)
                {
                    throw new AssertionError("could not read " + nestedSource, exception);
                }
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relativePath);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
