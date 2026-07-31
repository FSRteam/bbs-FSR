package mchorse.bbs_mod.ui.film;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for replay switching with unavailable plugin forms. */
public final class OrbitFilmCameraMissingRendererSourceTest
{
    private static final Path ORBIT_CONTROLLER = Path.of("src/client/java/mchorse/bbs_mod/ui/film/controller/OrbitFilmCameraController.java");

    private OrbitFilmCameraMissingRendererSourceTest()
    {}

    public static void runAll() throws IOException
    {
        Path root = findProjectRoot();
        String source = compact(Files.readString(root.resolve(ORBIT_CONTROLLER)));
        String orbitTarget = section(source,
            "private OrbitTarget getOrbitTarget(float transition)",
            "private boolean canStart(UIContext context)");

        check(orbitTarget.contains("FormRenderer<?> renderer = FormUtilsClient.getRenderer(form);"),
            "orbit target no longer resolves an optional form renderer");
        check(orbitTarget.contains("if (renderer != null)"),
            "orbit target no longer handles missing plugin form renderers");
        check(orbitTarget.contains("MatrixCache map = renderer.collectMatrices("),
            "orbit target bypasses the guarded renderer when collecting matrices");
        check(!orbitTarget.contains("FormUtilsClient.getRenderer(form).collectMatrices("),
            "orbit target directly dereferences a possibly missing form renderer");
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(ORBIT_CONTROLLER)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(ORBIT_CONTROLLER)))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static String section(String source, String start, String end)
    {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);

        check(startIndex >= 0 && endIndex > startIndex, "missing orbit target source section");

        return source.substring(startIndex, endIndex);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
