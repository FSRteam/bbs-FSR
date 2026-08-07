package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.forms.categories.RecentFormCategoryTest;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.settings.values.ui.ValueOrder;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.factories.AnchorKeyframeFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class FilmEditorMigrationLogicTest
{
    public static void main(String[] args) throws Exception
    {
        Runnable restoreClientRuntime = HeadlessClientTestBootstrap.install();

        try
        {
            testTimelineWheelOwnership();
            testOccupiedLayerCentering();
            testPerFilmScrollIsolation();
            testAnchorTransformInterpolation();
            testFilmControllerContextReset();
            testValueOrderDefaultInsertion();
            FilmReplayFirstPersonSyncSourceTest.runAll();
            OrbitFilmCameraMissingRendererSourceTest.runAll();
            RenderRuntimeMigrationSourceTest.runAll();
            RecentFormCategoryTest.runAll();

            if (args.length == 0 || !"--logic-only".equals(args[0]))
            {
                testCompiledMigrationHooks();
            }
        }
        finally
        {
            restoreClientRuntime.run();
        }

        System.out.println("FilmEditorMigrationLogicTest: all tests passed");
    }

    private static void testTimelineWheelOwnership()
    {
        check(FilmEditorMigrationLogic.shouldMoveCursorWithWheel(true, false, true),
            "Ctrl+wheel over a timeline must move the cursor");
        check(!FilmEditorMigrationLogic.shouldMoveCursorWithWheel(true, false, false),
            "Ctrl+wheel over the preview must remain available to Orbit zoom");
        check(!FilmEditorMigrationLogic.shouldMoveCursorWithWheel(true, true, true),
            "flight mode must retain wheel ownership");
        check(!FilmEditorMigrationLogic.shouldMoveCursorWithWheel(false, false, true),
            "plain timeline wheel must not move the cursor");
    }

    private static void testOccupiedLayerCentering()
    {
        double scroll = FilmEditorMigrationLogic.centeredVerticalScroll(600, 200, 20, 4, 8);

        check(scroll == 370D, "occupied layer band was not centered");

        double singleLayer = FilmEditorMigrationLogic.centeredVerticalScroll(400, 180, 20, 8, 8);

        check(singleLayer == 140D, "single occupied layer was not centered");
    }

    private static void testPerFilmScrollIsolation()
    {
        FilmEditorMigrationLogic.TimelineScrollMemory memory = new FilmEditorMigrationLogic.TimelineScrollMemory();

        memory.capture("film-a", 10D, 20D, 30D);
        memory.capture("film-b", 40D, 50D, 60D);
        memory.capture("film-a", 11D, 21D, 31D);

        assertScroll(memory.get("film-a"), 11D, 21D, 31D, "film-a");
        assertScroll(memory.get("film-b"), 40D, 50D, 60D, "film-b");
        check(memory.get("missing") == null, "an unopened film must use first-open centering");
        check(memory.get(null) == null, "a null film id must not share scroll state");
    }

    private static void testAnchorTransformInterpolation()
    {
        AnchorKeyframeFactory factory = new AnchorKeyframeFactory();
        Anchor preA = anchor(1, 0F);
        Anchor a = anchor(1, 10F);
        Anchor b = anchor(1, 20F);
        Anchor postB = anchor(1, 30F);
        Anchor sameTarget = factory.interpolate(preA, a, b, postB, Interpolations.LINEAR, 0.5F);

        check(sameTarget.transform.translate.x == 15F,
            "matching anchor targets must interpolate their transform fields");
        check(sameTarget.previous == null,
            "matching targets must not enter the attachment crossfade path");

        Anchor differentTarget = anchor(2, 20F);
        Anchor crossfade = factory.interpolate(preA, a, differentTarget, postB, Interpolations.LINEAR, 0.5F);

        check(crossfade.transform.translate.x == 20F,
            "different targets must keep the destination transform for matrix crossfade");
        check(crossfade.previous != null && crossfade.previous.replay == 1 && crossfade.x == 0.5F,
            "different targets must preserve existing attachment crossfade state");
    }

    private static void testFilmControllerContextReset() throws Exception
    {
        FilmControllerContext context = FilmControllerContext.instance;
        Method reset = FilmControllerContext.class.getDeclaredMethod("reset");

        context.bone2 = "preview";
        context.local2 = true;
        reset.setAccessible(true);
        reset.invoke(context);

        check(context.bone2 == null && !context.local2,
            "film render context leaked its secondary preview axis across setup calls");
    }

    private static void testValueOrderDefaultInsertion()
    {
        ValueOrder order = new ValueOrder("test", "translate", "scale", "rotate");
        ListType saved = new ListType();

        saved.addString("rotate");
        saved.addString("translate");
        order.fromData(saved);

        check(order.get().equals(List.of("rotate", "scale", "translate")),
            "a newly introduced order token was not inserted at its default position");
    }

    private static Anchor anchor(int replay, float translateX)
    {
        Anchor anchor = new Anchor(replay, "body", false, false);

        anchor.transform.translate.x = translateX;

        return anchor;
    }

    private static void testCompiledMigrationHooks() throws Exception
    {
        ClassLoader loader = FilmEditorMigrationLogicTest.class.getClassLoader();

        check(hasMethod(loader, "mchorse.bbs_mod.ui.film.UIFilmPanel", "isCursorOverTimeline"),
            "timeline-region gate is missing");
        check(hasMethod(loader, "mchorse.bbs_mod.ui.film.UIClips", "restoreVerticalScroll"),
            "per-film clip scroll restoration hook is missing");
        check(hasMethod(loader, "mchorse.bbs_mod.ui.film.clips.UIClip", "restoreScroll"),
            "clip property scroll restoration hook is missing");
        check(hasMethod(loader, "mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory", "restoreScroll"),
            "keyframe property scroll restoration hook is missing");
        check(hasField(loader, "mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory", "transform"),
            "anchor transform editor is missing");
        check(hasField(loader, "mchorse.bbs_mod.film.FilmControllerContext", "anchorGizmo"),
            "anchor gizmo render context is missing");
        check(hasMethod(loader, "mchorse.bbs_mod.film.BaseFilmController", "getGizmoAnchorCompositeMatrix"),
            "anchor gizmo composite sampler is missing");
    }

    private static boolean hasMethod(ClassLoader loader, String className, String methodName) throws ClassNotFoundException
    {
        Class<?> type = Class.forName(className, false, loader);

        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).anyMatch(methodName::equals);
    }

    private static boolean hasField(ClassLoader loader, String className, String fieldName) throws ClassNotFoundException
    {
        Class<?> type = Class.forName(className, false, loader);

        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).anyMatch(fieldName::equals);
    }

    private static void assertScroll(FilmEditorMigrationLogic.TimelineScroll actual, double camera, double action, double replay, String label)
    {
        check(actual != null, label + " scroll state is missing");
        check(actual.camera == camera && actual.action == action && actual.replay == replay,
            label + " scroll state crossed film boundaries");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private FilmEditorMigrationLogicTest()
    {}
}
