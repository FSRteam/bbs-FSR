package mchorse.bbs_mod.client.compat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Binary descriptor and delegation regression for legacy Film action delivery. */
final class UIFilmPanelCompatibilityDescriptorTest
{
    private static final Path FILM_PANEL = Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");

    private UIFilmPanelCompatibilityDescriptorTest()
    {}

    public static void main(String[] args)
    {
        verifyLegacyDelegation();

        if (args.length == 0 || !"source-only".equals(args[0]))
        {
            verifyDescriptors();
        }

        System.out.println("UIFilmPanelCompatibilityDescriptorTest passed");
    }

    static void runAll()
    {
        verifyDescriptors();
        verifyLegacyDelegation();
    }

    private static void verifyDescriptors()
    {
        try
        {
            ClassLoader loader = UIFilmPanelCompatibilityDescriptorTest.class.getClassLoader();
            Class<?> panelClass = Class.forName("mchorse.bbs_mod.ui.film.UIFilmPanel", false, loader);
            Class<?> baseTypeClass = Class.forName("mchorse.bbs_mod.data.types.BaseType", false, loader);
            Class<?> recorderClass = Class.forName("mchorse.bbs_mod.film.Recorder", false, loader);
            Class<?> terminalClass = Class.forName(
                "mchorse.bbs_mod.network.ServerNetwork$RecordingTerminal",
                false,
                loader
            );
            Method legacy = panelClass.getMethod(
                "receiveActions",
                String.class,
                int.class,
                int.class,
                baseTypeClass
            );
            Method terminalAware = panelClass.getMethod(
                "receiveActions",
                String.class,
                int.class,
                int.class,
                baseTypeClass,
                recorderClass,
                boolean.class,
                boolean.class,
                terminalClass
            );

            check(legacy.getReturnType() == void.class,
                "legacy UIFilmPanel.receiveActions JVM descriptor changed");
            check(terminalAware.getReturnType() == void.class,
                "terminal-aware UIFilmPanel.receiveActions descriptor changed");
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            throw new AssertionError("UIFilmPanel recording compatibility descriptor changed", e);
        }
    }

    private static void verifyLegacyDelegation()
    {
        try
        {
            String source = Files.readString(findProjectRoot().resolve(FILM_PANEL));
            String wrapper = section(
                source,
                "public void receiveActions(String filmId, int replayId, int tick, BaseType clips)",
                "ServerNetwork.RecordingTerminal recordingTerminal"
            );

            assertOrdered(wrapper,
                "this.receiveActions(",
                "filmId",
                "replayId",
                "tick",
                "clips",
                "null",
                "false",
                "true",
                "ServerNetwork.RecordingTerminal.LEGACY_MANUAL");
            check(!wrapper.contains("stopRecording") && !wrapper.contains("applyRecordedKeyframes"),
                "legacy receiveActions wrapper gained recorder or keyframe side effects");
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError("could not inspect UIFilmPanel compatibility wiring", e);
        }
    }

    private static String section(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start, "could not locate UIFilmPanel wrapper");

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int cursor = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, cursor + 1);

            check(next >= 0, "missing or out-of-order UIFilmPanel marker: " + marker);
            cursor = next;
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        for (int i = 0; i < 8 && current != null; i += 1)
        {
            if (Files.isRegularFile(current.resolve(FILM_PANEL)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate project root");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
