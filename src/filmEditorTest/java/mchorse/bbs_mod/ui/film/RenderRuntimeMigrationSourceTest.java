package mchorse.bbs_mod.ui.film;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level guards for render-state migrations that require an in-game visual smoke. */
public final class RenderRuntimeMigrationSourceTest
{
    private static final Path FILM_CONTROLLER = Path.of("src/client/java/mchorse/bbs_mod/film/BaseFilmController.java");
    private static final Path FRAMEBUFFER_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/FramebufferFormRenderer.java");

    private RenderRuntimeMigrationSourceTest()
    {}

    public static void main(String[] args) throws IOException
    {
        runAll();
        System.out.println("RenderRuntimeMigrationSourceTest passed");
    }

    public static void runAll() throws IOException
    {
        Path root = findProjectRoot();
        String film = compact(Files.readString(root.resolve(FILM_CONTROLLER)));
        String framebuffer = compact(Files.readString(root.resolve(FRAMEBUFFER_RENDERER)));
        String relativeRender = section(film, "stack.pushPose(); try", "if (UIBaseMenu.shouldRenderAxes() && context.anchorGizmo)");

        check(relativeRender.contains("stack.last().pose().rotate(context.camera.rotation());")
                && relativeRender.contains("stack.last().normal().rotate(context.camera.rotation());")
                && !relativeRender.contains("stack.last().pose().identity();"),
            "relative replay must compose the camera rotation without erasing the active Iris pass stack");

        check(framebuffer.contains("int viewportX;")
                && framebuffer.contains("int viewportY;")
                && framebuffer.contains("int prevCullFace = GL30.glGetInteger(GL11.GL_CULL_FACE_MODE);")
                && framebuffer.contains("VertexSorting vertexSorting = RenderSystem.getVertexSorting();"),
            "framebuffer forms no longer snapshot the complete borrowed viewport/cull/projection state");
        check(framebuffer.contains("GL30.glViewport(viewportX, viewportY, width, height);")
                && framebuffer.contains("RenderSystem.setProjectionMatrix(projectionMatrix, vertexSorting);")
                && framebuffer.contains("GL30.glCullFace(prevCullFace);")
                && occurrences(framebuffer, "RenderSystem.applyModelViewMatrix();") >= 2,
            "framebuffer forms no longer restore the exact caller render state");
        check(framebuffer.contains("finally { FormTranslucentQueue.restore(queueWasActive);")
                && framebuffer.contains("finally { depth -= 1;")
                && framebuffer.contains("finally { context.stack.popPose();"),
            "framebuffer child failures can leak queue, Iris depth, or PoseStack ownership");
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(FILM_CONTROLLER)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(FILM_CONTROLLER)))
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

        check(startIndex >= 0 && endIndex > startIndex, "missing production source section");

        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String marker)
    {
        int count = 0;
        int index = 0;

        while ((index = source.indexOf(marker, index)) >= 0)
        {
            count += 1;
            index += marker.length();
        }

        return count;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
