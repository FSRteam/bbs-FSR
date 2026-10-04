package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.ui.film.view.ViewFrameGeometry;
import mchorse.bbs_mod.ui.film.view.ViewportPickIntent;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Press/release regressions for clicks waiting on a throttled preview's stencil. */
public final class ViewportPickIntentTest
{
    public static void main(String[] args)
    {
        runAll();
        System.out.println("ViewportPickIntentTest: all checks passed");
    }

    public static void runAll()
    {
        activeViewCommitsOneReleasedClick();
        firstClickAfterSwitchUsesOriginalPointAndModifiers();
        staleRightClickWaitsForBoneOrConfirmedMiss();
        navigationContextClickIsAlreadyReleased();
        draggingNeverBecomesAPick();
        otherGesturesAndPopupsRetirePendingClicks();
        viewFilmAndSceneChangesRetirePendingClicks();
        oldGenerationCannotResolveOrReleaseItsReplacement();
        deferredPointerScopeRestoresTheLiveEvent();
        requireProductionPickingFlow();
    }

    private static void activeViewCommitsOneReleasedClick()
    {
        TestMenu menu = new TestMenu();
        TestViewport view = menu.first;

        view.stencilReady = true;
        check(menu.mouseClicked(30, 40, 0), "ready view did not own the press");
        check(view.lastPick == null, "a deferred click committed before release");
        check(!menu.mouseReleased(30, 40, 2), "another release consumed the left click");
        check(menu.mouseReleased(30, 40, 0), "matching click release was lost");
        view.finishSample();
        check("bone:30:40".equals(view.lastPick), "ready stencil did not select the original bone");
        view.finishSample();
        menu.mouseReleased(30, 40, 0);
        check(view.commits == 1, "one click selected twice");
    }

    private static void firstClickAfterSwitchUsesOriginalPointAndModifiers()
    {
        TestMenu menu = new TestMenu();

        for (TestViewport view : new TestViewport[] {menu.second, menu.first, menu.second})
        {
            int x = view.area.x + 25;
            int before = view.commits;

            view.stencilReady = false;
            view.alt = true;
            view.control = false;
            view.shift = true;
            check(menu.mouseClicked(x, 45, 0), "first click after A/B switch was not owned");
            check(menu.active == view && view.intent.generation() != 0L,
                "activation must precede capturing the view generation");
            menu.mouseReleased(x, 45, 0);
            view.alt = false;
            view.control = true;
            view.shift = false;
            menu.context.setMouse(999, 999, 2);
            view.finishSample();
            check(view.commits == before, "missing stencil was mistaken for a completed pick");
            view.stencilReady = true;
            view.finishSample();
            check(view.commits == before + 1 && ("actor:" + x + ":45").equals(view.lastPick),
                "first released click must resolve at the original point and Alt picking mode");
            check(view.lastInput.button() == 0 && view.lastInput.alt() && !view.lastInput.control() && view.lastInput.shift(),
                "modifier or button changes after release retargeted the click");
        }
    }

    private static void draggingNeverBecomesAPick()
    {
        for (boolean ready : new boolean[] {false, true})
        {
            TestMenu menu = new TestMenu();
            TestViewport view = menu.first;

            view.stencilReady = ready;
            menu.mouseClicked(40, 50, 0);
            menu.context.setMouse(70, 50, 0);
            view.finishSample();
            menu.mouseReleased(40, 50, 0);
            view.stencilReady = true;
            view.finishSample();
            check(view.commits == 0, "a drag returning to its origin became a short click");

            menu.mouseClicked(40, 50, 0);
            menu.mouseReleased(80, 80, 0);
            view.finishSample();
            check(view.commits == 0, "release-time motion without a render update became a pick");

            menu.mouseClicked(40, 50, 0);
            menu.mouseCanceled(40, 50, 0);
            menu.mouseReleased(40, 50, 0);
            view.finishSample();
            check(view.commits == 0, "explicit cancellation committed a click");
        }
    }

    private static void staleRightClickWaitsForBoneOrConfirmedMiss()
    {
        for (boolean empty : new boolean[] {false, true})
        {
            TestMenu menu = new TestMenu();
            TestViewport view = menu.second;

            view.emptyStencil = empty;
            view.shift = true;
            check(menu.mouseClicked(230, 45, 1), "first right click did not own the pending pick");
            check(view.lastPick == null && view.commits == 0, "unknown right-click stencil opened a world menu");
            menu.mouseReleased(230, 45, 1);
            view.shift = false;
            menu.context.setMouse(900, 900, 0);
            view.finishSample();
            check(view.lastPick == null, "released right click treated the missing stencil as a miss");
            view.stencilReady = true;
            view.finishSample();
            check((empty ? "menu:230:45" : "insert:230:45").equals(view.lastPick),
                "right click did not distinguish a bone insertion from a confirmed world miss");
            check(view.lastInput.button() == 1 && view.lastInput.shift(), "right click lost its insertion/snap modifiers");

            int committed = view.commits;
            view.stencilReady = false;
            menu.mouseClicked(230, 45, 1);
            menu.mouseReleased(250, 65, 1);
            view.stencilReady = true;
            view.finishSample();
            check(view.commits == committed, "a real right drag committed a deferred bone/context click");
        }
    }

    private static void navigationContextClickIsAlreadyReleased()
    {
        TestMenu menu = new TestMenu();
        TestViewport view = menu.first;

        view.contextClickOnRelease = true;
        menu.mouseClicked(40, 50, 1);
        check(view.intent.generation() == 0L, "navigation started its context click before the short gesture completed");
        menu.mouseReleased(40, 50, 1);
        check(view.intent.generation() != 0L && view.lastPick == null,
            "the navigation release did not retain its unresolved context click");
        view.stencilReady = true;
        view.finishSample();
        check("insert:40:50".equals(view.lastPick), "a context click emitted on release required an impossible second release");
    }

    private static void otherGesturesAndPopupsRetirePendingClicks()
    {
        for (int interruption = 0; interruption < 4; interruption++)
        {
            TestMenu menu = new TestMenu();
            TestViewport view = menu.first;

            menu.mouseClicked(40, 50, 0);
            menu.mouseReleased(40, 50, 0);

            switch (interruption)
            {
                case 0 -> menu.mouseClicked(900, 900, 1);
                case 1 -> menu.mouseScrolled(900, 900, 0D, 1D);
                case 2 -> menu.context.closeContextMenu();
                case 3 -> view.available = false;
            }

            view.stencilReady = true;
            view.finishSample();
            check(view.commits == 0 && view.intent.generation() == 0L,
                "another press, scroll, context menu or modal left a deferred click armed");
        }
    }

    private static void viewFilmAndSceneChangesRetirePendingClicks()
    {
        for (int interruption = 0; interruption < 7; interruption++)
        {
            TestMenu menu = new TestMenu();
            TestViewport view = menu.first;

            menu.mouseClicked(40, 50, 0);
            menu.mouseReleased(40, 50, 0);

            switch (interruption)
            {
                case 0 -> menu.activate(menu.second);
                case 1 -> view.film = new Object();
                case 2 -> view.replay = new Object();
                case 3 -> view.epoch++;
                case 4 -> view.mode++;
                case 5 -> view.tick++;
                case 6 -> view.area.w++;
            }

            view.stencilReady = true;
            view.finishSample();
            check(view.commits == 0 && view.intent.generation() == 0L,
                "view, Film, selection, camera, playhead or layout change reused a stale click");
        }
    }

    private static void oldGenerationCannotResolveOrReleaseItsReplacement()
    {
        ViewportPickIntent<String> intent = new ViewportPickIntent<>();
        ViewportPickIntent.Target target = new ViewportPickIntent.Target(new Object(), new Object(), null,
            0L, 1, 0, new ViewFrameGeometry(0, 0, 100, 100));
        ViewportPickIntent.Input input = new ViewportPickIntent.Input(30, 40, 0, false, false, false);
        long old = intent.begin(target, input, 1L, 0L);
        long replacement = intent.begin(target, input, 2L, 0L);

        intent.resolve(old, "old bone");
        check(!intent.release(0, old, 30, 40, false) && !intent.isResolved(), "stale work reached a replacement generation");
        intent.resolve(replacement, "original bone");
        intent.resolve(replacement, "later hover");
        intent.release(0, replacement, 30, 40, false);
        check(intent.take(old) == null, "old completion consumed the replacement");
        check("original bone".equals(intent.take(replacement).result()), "a later stencil replaced the original click result");
        check(intent.take(replacement) == null, "one resolved click could be taken twice");
    }

    private static void deferredPointerScopeRestoresTheLiveEvent()
    {
        TestMenu menu = new TestMenu();
        UIContext context = menu.context;

        context.setMouse(700, 800, 2);
        long generation = context.getPointerGestureGeneration();
        context.withPointerState(30, 40, 0, () ->
        {
            check(context.mouseX == 30 && context.mouseY == 40 && context.mouseButton == 0,
                "deferred selection did not receive the original pointer state");
            context.withPointerState(10, 20, 1, () -> {});
            check(context.mouseX == 30 && context.mouseY == 40 && context.mouseButton == 0,
                "nested pointer scope corrupted its caller");
        });
        check(context.mouseX == 700 && context.mouseY == 800 && context.mouseButton == 2,
            "deferred selection changed the live cursor/button state");
        check(context.getPointerGestureGeneration() == generation, "pointer scope dispatched a new gesture");

        try
        {
            context.withPointerState(30, 40, 0, () -> { throw new IllegalStateException("test"); });
            throw new AssertionError("expected pointer scope failure");
        }
        catch (IllegalStateException expected)
        {
            check(context.mouseX == 700 && context.mouseY == 800 && context.mouseButton == 2,
                "failed deferred selection leaked its pointer state");
        }
    }

    private static void requireProductionPickingFlow()
    {
        String controller = read("src/client/java/mchorse/bbs_mod/ui/film/controller/UIFilmController.java");
        String replay = read("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java");
        String preview = read("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPreview.java");

        check(controller.contains("sample != this.worldContextFrame || sample != BBSRendering.getSceneFrameId()"),
            "stencil refresh mixed a displayed camera with another scene's actors");
        check(controller.contains("boolean altPressed = pending ? this.pendingViewportPick.input().alt() : Window.isAltPressed();")
            && controller.contains("int mouseX = input == null ? context.mouseX : input.x();")
            && controller.contains("int mouseY = input == null ? context.mouseY : input.y();")
            && controller.contains("context.render.postRunnable(() -> this.commitViewportPick(context, generation))"),
            "controller did not preserve deferred coordinates/mode or schedule selection outside rendering");
        check(replay.contains("controller.deferViewportPick(context, generation)")
            && replay.contains("controller.deferViewportPick(context, 0L)")
            && replay.contains("input.button(), input.alt(), input.control(), input.shift()")
            && !replay.contains("this.pendingPick = stencil"),
            "orbit or free mode still stores an unavailable stencil as the final click");
        int rightPending = replay.indexOf("inside && context.mouseButton == 1 && !controller.isViewportPickReady()");
        int rightFallback = replay.indexOf("return this.openViewportContextMenu(context, area, controller, Window.isShiftPressed())");

        check(rightPending >= 0 && rightFallback > rightPending
            && controller.contains("pick.empty() && input.button() == 1")
            && controller.contains("this.panel.replayEditor.openViewportContextMenu(context, this.getViewArea(), this, input.shift())")
            && preview.contains("this.getViewController().clickViewportOnRelease(context)")
            && controller.contains("this.releaseViewportPick(context, false, 0L);"),
            "stale right clicks bypass readiness or navigation context clicks wait for a second release");
        int release = controller.indexOf("protected boolean subMouseReleased(UIContext context)");
        int motion = controller.indexOf("this.orbit.handleOrbiting(context);", release);
        int dragged = controller.indexOf("boolean orbitDragged = this.orbit.wasDragged();", release);

        check(motion > release && motion < dragged, "release failed to classify movement since the last rendered input update");
    }

    private static String read(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (IOException exception)
        {
            throw new AssertionError("Could not read " + path, exception);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TestMenu extends UIBaseMenu
    {
        private final TestViewport first = new TestViewport(this, 0);
        private final TestViewport second = new TestViewport(this, 200);
        private TestViewport active = this.first;

        private TestMenu()
        {
            this.viewport.set(0, 0, 1000, 1000);
            this.main.add(this.first, this.second);
        }

        private void activate(TestViewport view)
        {
            if (this.active != view)
            {
                this.active.intent.cancel();
                this.active = view;
                view.epoch++;
            }
        }
    }

    private static final class TestViewport extends UIElement
    {
        private final TestMenu menu;
        private final ViewportPickIntent<String> intent = new ViewportPickIntent<>();
        private Object film = new Object();
        private Object replay = new Object();
        private long epoch;
        private int mode = 1;
        private int tick;
        private boolean available = true;
        private boolean stencilReady;
        private boolean emptyStencil;
        private boolean contextClickOnRelease;
        private boolean navigationPressed;
        private boolean alt;
        private boolean control;
        private boolean shift;
        private String lastPick;
        private ViewportPickIntent.Input lastInput;
        private int commits;

        private TestViewport(TestMenu menu, int x)
        {
            this.menu = menu;
            this.area.set(x, 0, 180, 100);
        }

        private ViewportPickIntent.Target target()
        {
            return new ViewportPickIntent.Target(this, this.film, this.replay, this.epoch, this.mode, this.tick,
                new ViewFrameGeometry(this.area.x, this.area.y, this.area.w, this.area.h));
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (!this.area.isInside(context) || context.mouseButton < 0 || context.mouseButton > 1)
            {
                return false;
            }

            this.menu.activate(this);

            if (this.contextClickOnRelease && context.mouseButton == 1)
            {
                this.navigationPressed = true;

                return true;
            }

            this.beginPick(context);

            return true;
        }

        private void beginPick(UIContext context)
        {
            this.intent.begin(this.target(), new ViewportPickIntent.Input(context.mouseX, context.mouseY, context.mouseButton,
                this.alt, this.control, this.shift), context.getPointerGestureGeneration(), context.getContextMenuIntentGeneration());
            this.finishSample();
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            if (this.navigationPressed && context.mouseButton == 1)
            {
                this.navigationPressed = false;
                this.beginPick(context);
            }

            return this.intent.release(context.mouseButton, this.intent.generation(), context.mouseX, context.mouseY, false);
        }

        @Override
        protected void subMouseCanceled(UIContext context)
        {
            if (this.intent.isOwnedBy(context.mouseButton))
            {
                this.intent.cancel();
            }
        }

        private void finishSample()
        {
            UIContext context = this.menu.context;

            this.intent.move(context.mouseX, context.mouseY);

            if (!this.intent.validate(this.target(), context.getPointerGestureGeneration(), context.getContextMenuIntentGeneration(),
                this.available && this.menu.active == this))
            {
                return;
            }

            ViewportPickIntent.Input input = this.intent.input();

            if (this.stencilReady)
            {
                String prefix = input.button() == 1 ? "insert:" : (input.alt() ? "actor:" : "bone:");

                this.intent.resolve(this.intent.generation(), this.emptyStencil ? null : prefix + input.x() + ":" + input.y());
            }

            ViewportPickIntent.Completion<String> completion = this.intent.take(this.intent.generation());

            if (completion != null)
            {
                this.lastPick = completion.result() == null && completion.input().button() == 1
                    ? "menu:" + completion.input().x() + ":" + completion.input().y() : completion.result();
                this.lastInput = completion.input();
                this.commits++;
            }
        }
    }
}
