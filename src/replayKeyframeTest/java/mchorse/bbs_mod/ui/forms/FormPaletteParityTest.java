package mchorse.bbs_mod.ui.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.CategoryPreferences;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.model_editor.UIModelEditorPanel;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.GridLayout;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.ScrollZoomAnchor;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real category/drop and pointer paths, with equal-valued forms kept as separate items. */
public final class FormPaletteParityTest
{
    public static void runAll()
    {
        try
        {
            categoryPreferences();
            FormStorageParityTest.runAll();
            FormRenderRuntimeParityTest.runAll();
            Field registry = BBSMod.class.getDeclaredField("forms");
            Field catalog = BBSModClient.class.getDeclaredField("formCategories");
            registry.setAccessible(true);
            catalog.setAccessible(true);
            Object previousRegistry = registry.get(null);
            Object previousCatalog = catalog.get(null);
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("palette_fixture"), TestForm.class, null);
            FormCategory source = category("source", true);
            FormCategory destination = category("destination", true);
            FormCategory builtin = category("builtin", false);
            TestForm first = new TestForm();
            TestForm equal = new TestForm();
            TestForm locked = new TestForm();
            source.addForm(first);
            source.addForm(equal);
            builtin.addForm(locked);
            registry.set(null, architect);
            catalog.set(null, new FormCategories()
            {
                @Override
                public List<FormCategory> getAllCategories()
                {
                    return List.of(source, destination, builtin);
                }
            });

            try
            {
                int[] confirms = {0};
                int[] accepts = {0};
                UIFormList list = new UIFormList(new IUIFormList()
                {
                    @Override public void exit() {}
                    @Override public void toggleEditor() {}
                    @Override public void accept(Form form) { accepts[0]++; }
                    @Override public void confirm() { confirms[0]++; }
                });
                UIBaseMenu menu = new UIBaseMenu() {};
                menu.main.add(list);
                List<UIFormCategory> categories = list.forms.getChildren(UIFormCategory.class);
                UIFormCategory sourceUi = categories.get(0);
                UIFormCategory destinationUi = categories.get(1);
                list.selection.set(equal, source);
                list.dropForms(destinationUi, 0, List.of(equal));
                require(source.getForms().size() == 1 && source.getForms().get(0) == first
                    && destination.getForms().get(0) == equal, "Drop moved an equal neighbour instead of the chosen form");
                require(list.selection.contains(equal) && list.categoryOf(equal) == destination, "Moved selection lost its new category");
                list.selection.set(locked, builtin);
                list.dropForms(destinationUi, 1, List.of(locked));
                require(builtin.getForms().get(0) == locked && destination.getForms().size() == 2
                    && destination.getForms().get(1) != locked, "Read-only category must copy on drop");
                list.dropForms(categories.get(2), 0, List.of(first));
                require(builtin.getForms().size() == 1 && source.getForms().get(0) == first, "Read-only drop target accepted a mutation");

                int revision = source.getModCount();
                source.getDirectForms().add(new TestForm());
                require(source.getModCount() != revision && sourceUi.getForms().size() == 2, "Direct list edit left the searched view stale");
                source.removeForm(source.getForms().get(1));
                require(source.getForms().get(0) == first, "Identity removal removed the wrong equal item");
                sourceUi.area.set(0, 0, 300, 200);
                sourceUi.setCellSize(60);
                UIContext context = menu.context;
                context.mouseX = sourceUi.getLayout().getX(0) + 5;
                context.mouseY = sourceUi.getLayout().getY(0) + 5;
                context.mouseButton = 0;
                sourceUi.subMouseClicked(context);
                sourceUi.subMouseReleased(context);
                sourceUi.subMouseClicked(context);
                sourceUi.subMouseReleased(context);
                require(confirms[0] == 1 && accepts[0] == 2 && list.getSelected() == first, "Double click failed to choose and confirm the form");
                int cellSize = BBSSettings.formCellSize.get();
                try
                {
                    BBSSettings.formCellSize.set(64);
                    Method zoom = UIFormList.class.getDeclaredMethod("zoom", UIContext.class, int.class);
                    zoom.setAccessible(true);
                    zoom.invoke(list, context, UIFormList.ZOOM_STEP);
                    require(BBSSettings.formCellSize.get() == 72 && list.getCellSize() == 72,
                        "Palette zoom did not update the shared persistent cell size");
                }
                finally
                {
                    BBSSettings.formCellSize.set(cellSize);
                }
                modelEditorRoute(source, list);
                zoomAnchor();
                GridLayout strip = new GridLayout(0, 2, 2, 0, 0, 1F).strip().set(30, 20, 8);
                require(strip.getRows() == 1 && strip.getIndex(strip.getX(7) + 1, 1) == 7,
                    "Embedded grid changes broke horizontal frame strips");
                System.out.println("FormPaletteParityTest passed");
            }
            finally
            {
                registry.set(null, previousRegistry);
                catalog.set(null, previousCatalog);
            }
        }
        catch (ReflectiveOperationException | IOException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void categoryPreferences() throws ReflectiveOperationException, IOException
    {
        Field settings = BBSMod.class.getDeclaredField("settingsFolder");
        settings.setAccessible(true);
        Object previous = settings.get(null);
        Path directory = Files.createTempDirectory("bbs-category-preferences-");
        Path file = directory.resolve("categories.json");
        String legacy = "{\"nested_hidden\":{\"visible\":false},\"nested_default\":{},\"plain_hidden\":false,\"recent\":false}";

        try
        {
            settings.set(null, directory.toFile());
            Files.writeString(file, legacy);
            FormCategories catalog = new FormCategories();
            CategoryPreferences preferences = catalog.preferences;
            ValueBoolean hidden = preferences.visible("nested_hidden");
            preferences.read();
            require(!hidden.get() && preferences.visible("nested_default").get()
                && !preferences.visible("plain_hidden").get(), "Legacy category maps or plain booleans lost visibility");
            require(Files.readString(file).equals(legacy), "Reading category preferences rewrote the file");
            require(preferences == catalog.visibility && hidden == catalog.visibility.get("nested_hidden", true),
                "Compatibility category API owns separate visibility state");
            catalog.getRecentForms().initiate();
            require(catalog.getRecentForms().getCategories().get(0).visible == preferences.visible("recent")
                && !preferences.visible("recent").get(), "Recent category did not consume the shared preference");
            hidden.set(true);
            MapType saved = DataToString.read(file.toFile()).asMap();
            require(!saved.get("nested_hidden").isMap() && saved.getBool("nested_hidden")
                && !saved.getBool("plain_hidden"), "Preference edit did not persist the current boolean format");
            CategoryPreferences reopened = new CategoryPreferences();
            reopened.read();
            require(reopened.visible("nested_hidden").get() && !reopened.visible("recent").get(),
                "Reopened category preferences changed visibility");
            preferences.remove("nested_hidden");
            require(!DataToString.read(file.toFile()).asMap().keys().contains("nested_hidden"),
                "Deleted category retained its saved preference");
            require(preferences.visible("nested_hidden").get(), "Deleted category preference was retained in memory");
        }
        finally
        {
            settings.set(null, previous);
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static FormCategory category(String id, boolean mutable)
    {
        return new FormCategory(IKey.constant(id), new ValueBoolean(id, true))
        {
            @Override public boolean canModify(Form form) { return mutable; }
        };
    }

    private static void zoomAnchor()
    {
        Area area = new Area(0, 0, 300, 200);
        Scroll scroll = new Scroll(area, 20);
        scroll.scrollSize = 2000;
        scroll.setScroll(240);
        int[] height = {80};
        ScrollZoomAnchor.keep(scroll, 40, y -> 2,
            row -> new ScrollZoomAnchor.Placement(100 + row * height[0], height[0]), () -> height[0] = 120);
        require(Math.abs(scroll.getScroll() - 330D) < 0.01D, "Zoom failed to preserve the cursor's fractional row anchor");
    }

    private static void modelEditorRoute(FormCategory source, UIFormList list) throws ReflectiveOperationException
    {
        ModelTarget target = allocate(ModelTarget.class);
        TestDashboard dashboard = allocate(TestDashboard.class);
        dashboard.target = target;
        UIMorphingPanel morphing = allocate(UIMorphingPanel.class);
        Field owner = UIDashboardPanel.class.getDeclaredField("dashboard");
        owner.setAccessible(true);
        owner.set(morphing, dashboard);
        UIFormCategory category = new UIFormCategory(source, list)
        {
            @Override
            public <T extends UIElement> T getParent(Class<T> type)
            {
                return type == UIMorphingPanel.class ? type.cast(morphing) : super.getParent(type);
            }
        };
        ModelForm form = new ModelForm();
        form.model.set("fixture/model");
        ContextMenuManager menu = new ContextMenuManager();
        Method action = UIFormCategory.class.getDeclaredMethod("openModelEditorAction", ContextMenuManager.class, ModelForm.class);
        action.setAccessible(true);
        action.invoke(category, menu, form);
        require(menu.actions.size() == 1, "Morphing's model form did not produce an editor action");
        menu.actions.get(0).runnable.run();
        require(dashboard.selected == target && "fixture/model".equals(target.picked) && target.geometry,
            "Morphing model action did not select the model editor, load its model and open geometry");
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException
    {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static final class TestDashboard extends UIDashboard
    {
        private ModelTarget target;
        private UIDashboardPanel selected;

        @Override public <T> T getPanel(Class<T> type) { return type.cast(this.target); }
        @Override public void setPanel(UIDashboardPanel panel) { this.selected = panel; }
    }

    private static final class ModelTarget extends UIModelEditorPanel
    {
        private String picked;
        private boolean geometry;

        private ModelTarget() { super(null); }
        @Override public void pickData(String id) { this.picked = id; }
        @Override public void showGeometry(boolean show) { this.geometry = show; }
    }

    public static final class TestForm extends Form
    {}

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
