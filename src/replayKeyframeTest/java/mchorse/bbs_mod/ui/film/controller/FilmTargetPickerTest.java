package mchorse.bbs_mod.ui.film.controller;

import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scene-owned target requests must survive a view switch without accepting stale picks. */
public final class FilmTargetPickerTest
{
    public static void runAll() throws ReflectiveOperationException
    {
        insertFrameRespectsTheSelectedCategory();

        HeadlessPanel panel = allocate(HeadlessPanel.class);
        panel.scene = new LinkedHashMap<>();
        HeadlessController first = controller(panel);
        HeadlessController second = controller(panel);
        panel.owner = first;
        TestMenu menu = new TestMenu();
        UIElement source = new UIElement();
        UIElement replacement = new UIElement();
        menu.main.add(source, replacement);
        Form ownForm = new Form() {};
        Form target = new Form() {};
        StubEntity ownEntity = new StubEntity();
        StubEntity targetEntity = new StubEntity();
        ownEntity.setForm(ownForm);
        targetEntity.setForm(target);
        panel.scene.put("self", ownEntity);
        panel.scene.put("stable-target", targetEntity);
        List<String> accepted = new ArrayList<>();

        second.picker.toggleTargetPick(source, "self", (id, bone) -> accepted.add(id + ":" + bone.b));
        Object request = request(first.picker);
        check(first.picker.isPickingTarget(source) && second.picker.isPickingTarget(source), "Views did not share the scene's target request");
        second.picker.cancelPendingViewportPick();
        check(first.picker.isPickingTarget(source), "Switching or resetting a view cancelled the scene request");
        accept(second.picker, request, new Pair<>(ownForm, "root"));
        check(accepted.isEmpty() && first.picker.isPickingTarget(), "An excluded replay was accepted as its own target");
        accept(second.picker, request, new Pair<>(target, "arm"));
        check(accepted.equals(List.of("stable-target:arm")) && !first.picker.isPickingTarget(), "A target pick lost its stable replay id or fired more than once");
        accept(first.picker, request, new Pair<>(target, "arm"));
        check(accepted.size() == 1, "A delayed sample repeated an already completed request");

        first.picker.toggleTargetPick(source, "self", (id, bone) -> accepted.add("old"));
        Object stale = request(first.picker);
        second.picker.toggleTargetPick(replacement, "self", (id, bone) -> accepted.add("new"));
        accept(first.picker, stale, new Pair<>(target, "arm"));
        check(accepted.size() == 1 && second.picker.isPickingTarget(replacement), "An old view's deferred sample completed a replacement request");
        replacement.setVisible(false);
        check(!second.picker.isPickingTarget(), "A hidden editor retained the target request");
        replacement.setVisible(true);
        second.picker.toggleTargetPick(replacement, "self", (id, bone) -> accepted.add("removed"));
        replacement.removeFromParent();
        check(!first.picker.isPickingTarget(), "A detached editor retained the target request");
        first.picker.toggleTargetPick(source, "self", (id, bone) -> {});
        second.picker.toggleTargetPick(source, "self", (id, bone) -> {});
        check(!first.picker.isPickingTarget(), "Pressing the same eyedropper twice did not cancel");
    }

    private static void insertFrameRespectsTheSelectedCategory() throws ReflectiveOperationException
    {
        HeadlessPanel panel = allocate(HeadlessPanel.class);
        CategoryEditor editor = allocate(CategoryEditor.class);
        InsertionController controller = allocate(InsertionController.class);
        Field panelField = UIFilmController.class.getDeclaredField("panel");
        panelField.setAccessible(true);
        panelField.set(controller, panel);
        Field sceneOwner = UIFilmController.class.getDeclaredField("sceneOwner");
        sceneOwner.setAccessible(true);
        sceneOwner.setBoolean(controller, true);
        panel.replayEditor = editor;
        controller.replay = new Replay("category-owner");
        FilmKeyframeInsertion insertion = new FilmKeyframeInsertion(controller);

        for (TrackCategory category : List.of(TrackCategory.FORM, TrackCategory.IK,
            TrackCategory.PHYSICS, TrackCategory.builtin("addon", TrackCategory.FORM.icon)))
        {
            editor.category = category;
            insertion.insertFrame();
            check(controller.replay.keyframes.x.getKeyframes().isEmpty(),
                "Manual insertion wrote player channels from a non-replay category");
        }
    }

    private static HeadlessController controller(HeadlessPanel panel) throws ReflectiveOperationException
    {
        HeadlessController controller = allocate(HeadlessController.class);
        Field owner = UIFilmController.class.getDeclaredField("panel");
        owner.setAccessible(true);
        owner.set(controller, panel);
        Field picker = UIFilmController.class.getDeclaredField("picker");
        picker.setAccessible(true);
        picker.set(controller, new FilmStencilPicker(controller));
        return controller;
    }

    private static Object request(FilmStencilPicker picker) throws ReflectiveOperationException
    {
        Field request = FilmStencilPicker.class.getDeclaredField("targetPick");
        request.setAccessible(true);
        return request.get(picker);
    }

    private static void accept(FilmStencilPicker picker, Object request, Pair<Form, String> form) throws ReflectiveOperationException
    {
        for (Method method : FilmStencilPicker.class.getDeclaredMethods())
        {
            if (method.getName().equals("acceptTarget"))
            {
                method.setAccessible(true);
                method.invoke(picker, request, form);
                return;
            }
        }
        throw new AssertionError("Missing target completion consumer");
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException
    {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static final class TestMenu extends UIBaseMenu
    {}

    private static final class HeadlessPanel extends UIFilmPanel
    {
        private HeadlessController owner;
        private Map<String, IEntity> scene;

        HeadlessPanel()
        {
            super(null);
        }

        @Override
        public UIFilmController getController()
        {
            return this.owner;
        }

        @Override
        public List<UIFilmPreview> getPreviews()
        {
            return List.of();
        }
    }

    private static final class HeadlessController extends UIFilmController
    {
        HeadlessController()
        {
            super(null);
        }

        @Override
        public Map<String, IEntity> getEntities()
        {
            return ((HeadlessPanel) this.panel).scene;
        }
    }

    private static final class CategoryEditor extends UIReplaysEditor
    {
        private TrackCategory category;

        private CategoryEditor()
        {
            super(null);
        }

        @Override
        public TrackCategory getCategory()
        {
            return this.category;
        }
    }

    private static final class InsertionController extends UIFilmController
    {
        private Replay replay;

        private InsertionController()
        {
            super(null);
        }

        @Override
        Replay getReplay()
        {
            return this.replay;
        }

        @Override
        public IEntity getCurrentEntity()
        {
            throw new AssertionError("A non-replay category sampled the actor's recorded channels");
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
