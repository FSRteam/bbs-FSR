package mchorse.bbs_mod.cubic.ik;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source contract for the IK editor's bone-role markers. */
public final class IKMarkerSourceTest
{
    private IKMarkerSourceTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("IKMarkerSourceTest passed");
    }

    public static void runAll() throws IOException
    {
        Path root = findProjectRoot();
        String tree = Files.readString(root.resolve("src/client/java/mchorse/bbs_mod/ui/utils/bones/UIBoneTreeList.java"));
        String panel = Files.readString(root.resolve("src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIModelIKFormPanel.java"));
        String keys = Files.readString(root.resolve("src/client/java/mchorse/bbs_mod/ui/UIKeys.java"));
        String locale = Files.readString(root.resolve("src/client/resources/assets/bbs/assets/strings/en_us.json"));

        require(tree.contains("record Marker(int color, boolean small)"), "bone list marker value type is missing");
        require(tree.contains("markers(Function<String, Marker[]> markers, IKey legend)"), "bone list marker legend API is missing");
        require(tree.contains("renderMarkers(context, element, x, y, h)"), "bone list does not render role markers");
        require(tree.contains("limitToWidth(label, right - textX - 2)"), "bone labels are not shortened for marker slots");
        require(panel.contains("MARKER_CHAIN") && panel.contains("MARKER_TARGET")
                && panel.contains("MARKER_POLE") && panel.contains("MARKER_JOINT"),
            "IK panel role colors are incomplete");
        require(panel.contains("ModelIKRuntime.chainBones(model, tip, data.chainLength)"),
            "IK panel markers do not follow the configured chain");
        require(panel.contains("this.bones.markers(this.boneMarkers::get"),
            "IK panel does not bind marker data to the bone list");
        require(keys.contains("FORMS_EDITORS_MODEL_IK_BONES_TOOLTIP"), "IK marker tooltip key is missing");
        require(locale.contains("bbs.ui.forms.editors.model.ik.bones_tooltip"), "IK marker tooltip translation is missing");
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        for (Path candidate = current; candidate != null; candidate = candidate.getParent())
        {
            if (Files.isDirectory(candidate.resolve("src/client/java"))
                    && Files.isRegularFile(candidate.resolve("build.gradle")))
            {
                return candidate;
            }
        }

        throw new IllegalStateException("could not locate project root from " + current);
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
