package mchorse.bbs_mod.client.ui.mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source contract for the FS overlay placement gizmo migration. */
public final class UIPlacementGizmoMigrationTest
{
    private UIPlacementGizmoMigrationTest()
    {}

    public static void main(String[] args)
    {
        String preview = read("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPreview.java");
        String gizmo = read("src/client/java/mchorse/bbs_mod/ui/film/UIPlacementGizmo.java");
        String image = read("src/client/java/mchorse/bbs_mod/ui/film/UIImageRenderer.java");
        String subtitle = read("src/client/java/mchorse/bbs_mod/ui/film/UISubtitleRenderer.java");
        String contract = read("src/main/java/mchorse/bbs_mod/camera/clips/IPlaceableClip.java");
        String subtitleClip = read("src/main/java/mchorse/bbs_mod/camera/clips/misc/SubtitleClip.java");
        String imageClip = read("src/main/java/mchorse/bbs_mod/camera/clips/misc/ImageClip.java");
        String videoClip = read("src/main/java/mchorse/bbs_mod/camera/clips/misc/VideoClip.java");

        check(preview.contains("private final UIPlacementGizmo placementGizmo")
                && preview.contains("placementGizmo.mouseClicked(context, area)")
                && preview.contains("placementGizmo.mouseReleased(context)")
                && preview.contains("placementGizmo.render(context, area)"),
            "Film preview does not own the placement gizmo click, release, and render lifecycle");
        check(contract.contains("getOverlayBox")
                && subtitleClip.contains("implements IPlaceableClip")
                && imageClip.contains("implements IPlaceableClip")
                && videoClip.contains("implements IPlaceableClip"),
            "Overlay placement has no clip contract exposing its rendered bounds");
        check(image.contains("image.box.set") && subtitle.contains("subtitle.box.set"),
            "Image and subtitle renderers do not publish the actual overlay bounds");
    }

    private static String read(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (IOException exception)
        {
            throw new AssertionError("Could not read regression source " + path, exception);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
