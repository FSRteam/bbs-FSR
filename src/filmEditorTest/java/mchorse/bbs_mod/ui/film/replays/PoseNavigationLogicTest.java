package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.lang.reflect.Proxy;
import java.util.List;

public final class PoseNavigationLogicTest
{
    public static void runAll()
    {
        UIKeyframeSheet pose = sheet("pose", true);
        UIKeyframeSheet overlay = sheet("pose_overlay2", true);
        UIKeyframeSheet emptyBone = sheet("pose.bones.arm", false);
        UIKeyframeSheet animatedBone = sheet("pose.bones.leg", true);

        IUIKeyframeGraph selectedOverlay = graph(List.of(pose, overlay, emptyBone, animatedBone), overlay, pose);

        require(UIReplaysEditorUtils.resolveBoneSheet(selectedOverlay, emptyBone.id, "") == overlay,
            "an empty bone track must prefer the selected pose overlay");
        require(UIReplaysEditorUtils.resolveBoneSheet(selectedOverlay, animatedBone.id, "") == animatedBone,
            "a non-empty bone track must retain priority");
        require(UIReplaysEditorUtils.isPoseSheet(overlay, ""),
            "numbered pose overlays must be recognized as pose sheets");

        IUIKeyframeGraph lastOverlay = graph(List.of(pose, overlay, emptyBone), null, overlay);

        require(UIReplaysEditorUtils.resolveBoneSheet(lastOverlay, emptyBone.id, "") == overlay,
            "the last pose overlay must win when no pose keyframe is selected");

        UIKeyframeSheet nestedPose = sheet("parts.arm/pose", true);
        UIKeyframeSheet nestedEmptyBone = sheet("parts.arm/pose.bones.hand", false);
        IUIKeyframeGraph nested = graph(List.of(pose, nestedPose, nestedEmptyBone), null, null);

        require(UIReplaysEditorUtils.resolveBoneSheet(nested, nestedEmptyBone.id, "parts.arm") == nestedPose,
            "nested forms must fall back only to their own pose sheet");
    }

    private static UIKeyframeSheet sheet(String id, boolean populated)
    {
        KeyframeChannel<Double> channel = new KeyframeChannel<>(id, KeyframeFactories.DOUBLE);

        if (populated)
        {
            channel.insert(0F, 0D);
        }

        return new UIKeyframeSheet(id, IKey.constant(id), 0, false, channel, null);
    }

    private static IUIKeyframeGraph graph(List<UIKeyframeSheet> sheets, UIKeyframeSheet selected, UIKeyframeSheet last)
    {
        Keyframe<?> selectedKeyframe = selected == null ? null : selected.channel.get(0);

        return (IUIKeyframeGraph) Proxy.newProxyInstance(
            PoseNavigationLogicTest.class.getClassLoader(),
            new Class<?>[] {IUIKeyframeGraph.class},
            (proxy, method, args) -> switch (method.getName())
            {
                case "getSheets" -> sheets;
                case "getLastSheet" -> last;
                case "getSelected" -> selectedKeyframe;
                case "getSheet" -> args[0] instanceof String id
                    ? sheets.stream().filter((sheet) -> sheet.id.equals(id)).findFirst().orElse(null)
                    : sheets.stream().filter((sheet) -> sheet.channel == ((Keyframe<?>) args[0]).getParent()).findFirst().orElse(null);
                case "toString" -> "PoseNavigationGraph";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private PoseNavigationLogicTest()
    {}
}
