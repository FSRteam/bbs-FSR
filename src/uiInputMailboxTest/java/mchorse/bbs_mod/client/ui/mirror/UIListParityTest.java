package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/** Shared list selection, filtered input, and transactional drag regressions. */
public final class UIListParityTest
{
    public static void runAll()
    {
        long lease = BBSUiRemoteHeldState.install(71L, state(0));

        try
        {
            testSelectionTracksRows();
            testFilteredRebuildAndKeyboard();
            testGroupDragAndPlainClick();
            testCancelAndCallbackFailure();
            System.out.println("UIListParityTest passed");
        }
        finally
        {
            BBSUiRemoteHeldState.clear(lease);
        }
    }

    private static BBSUiRemoteInputState state(int modifiers)
    {
        return new BBSUiRemoteInputState(10D, 10D, 1, Set.of(), modifiers);
    }

    private static void testSelectionTracksRows()
    {
        Row a = new Row("same");
        Row b = new Row("same");
        TestList list = new TestList();

        list.setList(new ArrayList<>(List.of(a, b)));
        list.setIndex(1);
        list.getList().add(0, new Row("before"));
        check(list.getIndex() == 2 && list.getCurrentFirst() == b,
            "inserting a row moved selection to a different equal-valued object");
        list.remove(a);
        check(list.getIndex() == 1 && list.getCurrentFirst() == b,
            "removing another equal-valued row removed the selected object");
        list.replace(new Row("replacement"));
        check(list.getCurrentFirst() == list.getList().get(1), "replacing a row detached its selection");

        TestList rebuilt = new TestList((left, right) -> left.name.equals(right.name));
        rebuilt.setList(new ArrayList<>(List.of(new Row("a"), new Row("b"))));
        rebuilt.setIndex(1);
        Row replacement = new Row("b");
        rebuilt.setList(new ArrayList<>(List.of(replacement, new Row("a"))));
        check(rebuilt.getCurrentFirst() == replacement && rebuilt.getIndex() == 0,
            "stable-key row rebuild retained an obsolete wrapper");
    }

    private static void testFilteredRebuildAndKeyboard()
    {
        TestList list = new TestList();
        Row alpha = new Row("alpha");
        Row beta = new Row("beta");
        Row gamma = new Row("gamma");

        list.multi();
        list.setList(new ArrayList<>(List.of(alpha, beta, gamma)));
        list.filter("a");
        list.remove(beta);
        check(list.shown().equals(List.of(alpha, gamma)), "filter retained a removed row");
        UIContext context = new UIContext(null);
        context.setMouse(10, 10);
        context.setKeyEvent(GLFW.GLFW_KEY_END, 0, GLFW.GLFW_PRESS);
        check(list.key(context) && list.getCurrentFirst() == gamma,
            "keyboard navigation did not use the filtered rows");
        Row delta = new Row("delta");
        list.setList(new ArrayList<>(List.of(delta, new Row("hidden"))));
        check(list.shown().equals(List.of(delta)), "rebuilding a filtered list retained old row mappings");
        context.setMouse(10, 10, 0);
        list.subMouseClicked(context);
        context.mouseButton = 0;
        list.subMouseReleased(context);
        check(list.getCurrentFirst() == delta, "filtered click selected a stale backing index");
    }

    private static void testGroupDragAndPlainClick()
    {
        TestList list = new TestList();
        Row a = new Row("a");
        Row b = new Row("b");
        Row c = new Row("c");
        Row d = new Row("d");
        UIContext context = new UIContext(null);

        list.multi().sorting();
        list.setList(new ArrayList<>(List.of(a, b, c, d)));
        list.setCurrent(List.of(b, c));
        context.setMouse(10, 25, 0);
        list.subMouseClicked(context);
        check(list.getCurrent().size() == 2 && list.drag.getItems().size() == 2,
            "pressing a selected row discarded the group before dragging");
        context.setMouse(10, 85, 1);
        list.subMouseReleased(context);
        check(list.drag.isPressed() && list.getList().equals(List.of(a, b, c, d)),
            "foreign release ended a group drag");
        context.mouseButton = 0;
        list.subMouseReleased(context);
        check(list.getList().equals(List.of(a, d, b, c)) && list.getCurrent().equals(List.of(b, c)),
            "group reorder lost order or selected only one carried row");

        context.setMouse(10, 45, 0);
        list.subMouseClicked(context);
        list.subMouseReleased(context);
        check(list.getCurrent().equals(List.of(b)), "plain click did not narrow a group on release");
    }

    private static void testCancelAndCallbackFailure()
    {
        TestList list = new TestList();
        Row a = new Row("a");
        Row b = new Row("b");
        UIContext context = new UIContext(null);

        list.sorting();
        list.setList(new ArrayList<>(List.of(a, b)));
        context.setMouse(10, 5, 0);
        list.subMouseClicked(context);
        context.setMouse(10, 45, 1);
        list.cancel(context);
        check(list.drag.isPressed(), "foreign cancellation ended the left drag");
        context.mouseButton = 0;
        list.cancel(context);
        list.subMouseReleased(context);
        check(!list.drag.isPressed() && list.getList().equals(List.of(a, b)),
            "canceled drag committed on a later release");

        list.callback = (rows) -> { throw new IllegalStateException("fixture selection failure"); };
        context.setMouse(10, 5, 0);

        try
        {
            list.subMouseClicked(context);
            throw new AssertionError("selection failure was swallowed");
        }
        catch (IllegalStateException expected)
        {}

        check(!list.drag.isPressed(), "selection callback failure retained the old drag");
        list.callback = null;
        check(list.subMouseClicked(context), "selection callback failure blocked the next gesture");
        context.setMouse(10, 45, 0);
        list.subMouseReleased(context);
        check(list.getList().equals(List.of(b, a)), "new drag could not finish after callback failure");

        list.afterSwap = () ->
        {
            list.afterSwap = null;
            context.setMouse(10, 5, 0);
            list.subMouseClicked(context);
        };
        context.setMouse(10, 5, 0);
        list.subMouseClicked(context);
        context.setMouse(10, 45, 0);
        list.subMouseReleased(context);
        check(list.drag.isPressed(), "finishing an old drop cleared its callback's new gesture");
        list.cancel(context);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private record Row(String name)
    {}

    private static final class TestList extends UIList<Row>
    {
        private Runnable afterSwap;

        private TestList()
        {
            super(null);
            this.area.set(0, 0, 160, 120);
        }

        private TestList(BiPredicate<Row, Row> same)
        {
            super(null, same);
            this.area.set(0, 0, 160, 120);
        }

        private List<Row> shown()
        {
            return new ArrayList<>(this.visible());
        }

        private boolean key(UIContext context)
        {
            return this.subKeyPressed(context);
        }

        private void cancel(UIContext context)
        {
            this.subMouseCanceled(context);
        }

        @Override
        protected void handleSwap(int from, int to)
        {
            super.handleSwap(from, to);

            if (this.afterSwap != null) this.afterSwap.run();
        }

        @Override
        protected String elementToString(UIContext context, int index, Row element)
        {
            return element.name;
        }
    }
}
