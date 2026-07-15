package mchorse.bbs_mod.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Executable regression checks for argument boundaries and owned temp-file cleanup. */
public class VideoExportUtilsTest
{
    public static void main(String[] args) throws Exception
    {
        assertFilmName("My Film");
        assertFilmName("My Film (1)");
        assertQuotedArguments();
        assertBackslashesPreserved();
        assertEscapedQuotes();
        assertExplicitEmptyArgument();
        assertUnclosedQuoteRejected();
        assertReplacementDoesNotCascade();
        assertTemporaryAudioCleanup();
    }

    private static void assertFilmName(String name)
    {
        assertEquals(
            List.of(name + ".mp4"),
            VideoExportUtils.resolveArguments("%NAME%.mp4", Map.of("%NAME%", name))
        );
    }

    private static void assertQuotedArguments()
    {
        assertEquals(
            List.of("-metadata", "title=My Film", "-i", "C:\\Audio Files\\track.wav"),
            VideoExportUtils.resolveArguments(
                "-metadata \"title=My Film\" -i \"%AUDIO_TRACK%\"",
                Map.of("%AUDIO_TRACK%", "C:\\Audio Files\\track.wav")
            )
        );
    }

    private static void assertBackslashesPreserved()
    {
        assertEquals(
            List.of("-i", "C:\\Audio Files\\track.wav", "\\\\server\\share\\track.wav"),
            VideoExportUtils.resolveArguments(
                "-i \"C:\\Audio Files\\track.wav\" \"\\\\server\\share\\track.wav\"",
                Map.of()
            )
        );
    }

    private static void assertEscapedQuotes()
    {
        assertEquals(
            List.of("-metadata", "title=Say \"Hi\"", "-metadata", "comment=It's fine"),
            VideoExportUtils.resolveArguments(
                "-metadata \"title=Say \\\"Hi\\\"\" -metadata 'comment=It\\'s fine'",
                Map.of()
            )
        );
    }

    private static void assertExplicitEmptyArgument()
    {
        assertEquals(
            List.of("-metadata", "", "tail"),
            VideoExportUtils.resolveArguments("-metadata \"\" tail", Map.of())
        );
    }

    private static void assertUnclosedQuoteRejected()
    {
        try
        {
            VideoExportUtils.resolveArguments("-metadata \"unfinished", Map.of());
        }
        catch (IllegalArgumentException e)
        {
            return;
        }

        throw new AssertionError("Unclosed ffmpeg argument quote was accepted");
    }

    private static void assertReplacementDoesNotCascade()
    {
        assertEquals(
            List.of("%FILTERS%.mp4"),
            VideoExportUtils.resolveArguments(
                "%NAME%.mp4",
                Map.of("%NAME%", "%FILTERS%", "%FILTERS%", "unexpected")
            )
        );
    }

    private static void assertTemporaryAudioCleanup() throws Exception
    {
        Path folder = Files.createTempDirectory("bbs-export-test-");
        File userFile = folder.resolve("user.wav").toFile();
        Files.writeString(userFile.toPath(), "keep");

        File temporary = VideoExportUtils.createTemporaryAudioFile(folder.toFile());
        VideoExportUtils.deleteTemporaryFile(temporary);

        if (temporary.exists() || !userFile.exists())
        {
            throw new AssertionError("Temporary cleanup affected the wrong file");
        }

        Files.delete(userFile.toPath());
        Files.delete(folder);
    }

    private static void assertEquals(List<String> expected, List<String> actual)
    {
        if (!actual.equals(expected))
        {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
