package mchorse.bbs_mod.ui.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.categories.UserFormCategory;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.ExtrudedForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.forms.ItemForm;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.ParticleForm;
import mchorse.bbs_mod.forms.forms.TrailForm;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.forms.forms.VideoForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.sections.ExtraFormSection;
import mchorse.bbs_mod.forms.sections.ModelFormSection;
import mchorse.bbs_mod.forms.sections.ParticleFormSection;
import mchorse.bbs_mod.forms.sections.UserFormSection;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack;
import mchorse.bbs_mod.ui.forms.editors.forms.UIBillboardForm;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.forms.UIFramebufferForm;
import mchorse.bbs_mod.ui.forms.editors.forms.UIMobForm;
import mchorse.bbs_mod.ui.forms.editors.forms.UIVideoForm;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.watchdog.WatchDogEvent;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Actual category disk writes, model watcher events and readable track consumers. */
public final class FormStorageParityTest
{
    public static void runAll()
    {
        try (Fixture fixture = new Fixture())
        {
            readableTracks();
            tintMigration();
            icons(fixture);
            modelChanges(fixture);
            categoryWrites(fixture);
        }
        catch (Exception exception)
        {
            throw new AssertionError("Form storage parity failed", exception);
        }

        System.out.println("FormStorageParityTest passed");
    }

    private static void readableTracks()
    {
        TestForm root = new TestForm();
        root.name.set("Root");
        TestForm arm = new TestForm();
        arm.name.set("Arm");
        TestForm badge = new TestForm();
        badge.name.set("Badge");
        BodyPart outer = new BodyPart("");
        outer.setForm(arm);
        root.parts.add(outer);
        BodyPart inner = new BodyPart("");
        inner.setForm(badge);
        arm.parts.add(inner);
        String path = FormUtils.getPath(badge);
        check(root.getTrackLabel().isEmpty() && "Arm/Badge".equals(badge.getTrackLabel()),
            "Body-part labels did not follow the readable form hierarchy");
        check("Arm/Badge/head".equals(badge.getTrackName(TrackId.bone(path, "head").toKey()))
            && "Arm/Badge".equals(badge.getTrackName(path)), "Track label exposed a namespace or stable address");
        FormProperties properties = new FormProperties("properties");
        UIKeyframeSheet sheet = new UIKeyframeSheet(0xFFFFFF, false, properties.create(badge.lighting), badge.lighting);
        check("Arm/Badge/lighting".equals(sheet.title.get()), "Property sheet did not consume the readable owner label");
        badge.trackName.set("Chest badge");
        check("Chest badge".equals(badge.getTrackName(""))
            && "Chest badge/head".equals(badge.getTrackName(TrackId.bone(path, "head").toKey())),
            "Custom track name lost its leaf or empty-property contract");
        check("head".equals(root.getTrackName("pose.bones.head")), "Root bone label retained its namespace");
    }

    private static void tintMigration()
    {
        BillboardForm original = new BillboardForm();
        Color tint = new Color(0.2F, 0.4F, 0.6F, 0.5F);
        original.color.set(tint);
        original.additiveColor.set(true);
        MapType modern = original.toData().asMap();
        BillboardForm modernReloaded = (BillboardForm) FormUtils.fromData(modern.copy());
        check(modernReloaded.additiveColor.get() && modernReloaded.color.get().equals(tint)
            && modernReloaded.overlayColor.get().a == 0F, "A current explicit FSR additive form changed on reload");

        MapType legacy = modern.copy().asMap();
        legacy.remove("color_overlay");
        legacy.remove("glint");
        BillboardForm migrated = (BillboardForm) FormUtils.fromData(legacy);
        check(!migrated.additiveColor.get() && migrated.color.get().equals(Color.white())
            && migrated.overlayColor.get().equals(tint), "A pre-overlay additive map did not migrate to its color overlay");
        BillboardForm reopened = (BillboardForm) FormUtils.fromData(migrated.toData().asMap());
        check(!reopened.additiveColor.get() && reopened.color.get().equals(Color.white())
            && reopened.overlayColor.get().equals(tint), "Reload applied the legacy tint migration twice");

        MapType fsrGlint = modern.copy().asMap();
        fsrGlint.remove("color_overlay");
        BillboardForm protectedFsr = (BillboardForm) FormUtils.fromData(fsrGlint);
        check(protectedFsr.additiveColor.get() && protectedFsr.color.get().equals(tint),
            "An FSR glint form without an overlay was treated as a pre-overlay FS form");
        Color multiply = Color.white();
        Color compatible = Color.white();
        FormColorBlend.blend(multiply, tint);
        FormColorBlend.blend(compatible, tint, false);
        check(multiply.equals(compatible), "The compatibility overload changed the ordinary FS tint");
        Color brighten = Color.white();
        FormColorBlend.blend(brighten, tint, true);
        check(brighten.r > 1F && brighten.a == tint.a, "The explicit FSR brighten mode was lost");
    }

    private static void icons(Fixture fixture)
    {
        Form[] forms = {new BillboardForm(), new BlockForm(), new ExtrudedForm(), new FramebufferForm(),
            new ItemForm(), new LabelForm(), new MobForm(), new ModelForm(), new ParticleForm(), new TrailForm(),
            new VanillaParticleForm(), new VideoForm()};
        Icon[] expected = {BillboardForm.ICON, BlockForm.ICON, ExtrudedForm.ICON, FramebufferForm.ICON,
            ItemForm.ICON, LabelForm.ICON, MobForm.ICON, ModelForm.ICON, ParticleForm.ICON, TrailForm.ICON,
            VanillaParticleForm.ICON, VideoForm.VIDEO_ICON};
        for (int i = 0; i < forms.length; i++)
        {
            check(forms[i].getIcon() == expected[i], "Form icon owner differs from the editor icon: " + forms[i].getClass());
        }
        editorIcon(new UIBillboardForm(), new BillboardForm());
        editorIcon(new UIFramebufferForm(), new FramebufferForm());
        editorIcon(new UIMobForm(), new MobForm());
        editorIcon(new UIVideoForm(), new VideoForm());
        ExtraFormSection extra = new ExtraFormSection(fixture.catalog);
        extra.initiate();
        List<FormCategory> categories = extra.getCategories();
        check(categories.get(0).icon == Icons.SHAPES && categories.get(1).icon == Icons.CHICKEN
            && categories.get(2).icon == Icons.PLAYER && categories.get(3).icon == Icons.SKULL
            && categories.get(4).icon == Icons.MORE, "Builtin category icons were not assigned");
        List<Form> palette = categories.get(0).getForms();
        check(palette.stream().anyMatch(VideoForm.class::isInstance)
            && palette.stream().anyMatch(SoundSphereForm.class::isInstance)
            && palette.stream().anyMatch(SoundConeForm.class::isInstance)
            && palette.stream().filter(FramebufferForm.class::isInstance).count() == 1,
            "Extra palette lost a builtin or an FSR form");
        fixture.catalog.getRecentForms().initiate();
        check(fixture.catalog.getRecentForms().getCategories().get(0).icon == Icons.TIME,
            "Recent category lost its clock icon");
        TestParticles particles = new TestParticles(fixture.catalog);
        particles.addKey("fixture");
        check(particles.getCategories().get(0).icon == Icons.PARTICLE, "Particle section did not provide its category icon");
    }

    private static <T extends Form> void editorIcon(UIForm<T> editor, T form)
    {
        check(editor.getButton(editor.defaultPanel).getIcon() == form.getIcon(), "Editor and selected-form status use different icons");
    }

    private static void modelChanges(Fixture fixture) throws Exception
    {
        ModelFormSection section = new ModelFormSection(fixture.catalog);
        section.initiate();
        section.accept(fixture.directory.resolve("outside.json"), WatchDogEvent.CREATED);
        Path folder = Files.createDirectories(fixture.assets.resolve("models/plain"));
        Path primary = Files.writeString(folder.resolve("primary.jem"), "{}");
        section.accept(primary, WatchDogEvent.MODIFIED);
        check(modelKeys(section).equals(List.of("plain")), "Model file event created a trailing-slash or filename key");
        check(section.getCategories().get(0).icon == Icons.POSE, "Model category lost its pose icon");
        Path secondary = Files.writeString(folder.resolve("secondary.jpm"), "{}");
        Files.delete(primary);
        section.accept(primary, WatchDogEvent.DELETED);
        check(modelKeys(section).equals(List.of("plain")), "Deleting one model file removed a folder with a second model file");
        Files.delete(secondary);
        section.accept(secondary, WatchDogEvent.DELETED);
        check(modelKeys(section).isEmpty(), "Deleting the last model file left its form in the palette");
        primary = Files.writeString(folder.resolve("primary.jem"), "{}");
        section.initiate();
        Files.delete(primary);
        Files.delete(folder);
        section.accept(folder, WatchDogEvent.DELETED);
        check(modelKeys(section).isEmpty(), "Deleted model directory left a stale palette entry");
    }

    private static List<String> modelKeys(ModelFormSection section)
    {
        return section.getCategories().stream().flatMap(category -> category.getForms().stream())
            .map(form -> ((ModelForm) form).model.get()).toList();
    }

    private static void categoryWrites(Fixture fixture) throws Exception
    {
        UserFormSection section = fixture.catalog.getUserForms();
        Field field = UserFormSection.class.getDeclaredField("writer");
        field.setAccessible(true);
        ExecutorService writer = (ExecutorService) field.get(section);

        try
        {
            UserFormCategory first = new UserFormCategory(IKey.constant("First"), fixture.catalog.preferences.visible("first"), section);
            UserFormCategory second = new UserFormCategory(IKey.constant("Second"), fixture.catalog.preferences.visible("second"), section);
            section.addUserCategory(first);
            section.addUserCategory(second);
            first.addForm(new TestForm());
            first.addForm(new TestForm());
            check(!UserFormSection.getUserCategoriesFile(0).exists(), "Category edits still wrote disk before the tick flush");
            section.flush();
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            check(DataToString.read(UserFormSection.getUserCategoriesFile(0)).asMap().getList("forms").size() == 2,
                "Batched category save lost an edit");
            section.moveUserCategory(second, 0);
            section.flush();
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            check("second".equals(DataToString.read(UserFormSection.getUserCategoriesFile(0)).asMap().getString("id")),
                "Moving a category did not rewrite its index file");
            section.removeUserCategory(second);
            section.flush();
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            check(!UserFormSection.getUserCategoriesFile(1).exists()
                && "first".equals(DataToString.read(UserFormSection.getUserCategoriesFile(0)).asMap().getString("id")),
                "Deleting a category retained a stale index file");
            section.initiate();
            check(section.categories.size() == 1 && section.categories.get(0).getForms().size() == 2,
                "Reopening the user category folder lost the saved forms");
        }
        finally
        {
            writer.shutdown();
            check(writer.awaitTermination(5, TimeUnit.SECONDS), "Category writer did not finish");
        }
    }

    public static final class TestForm extends Form
    {}

    private static final class TestParticles extends ParticleFormSection
    {
        private TestParticles(FormCategories parent) { super(parent); }
        private void addKey(String key) { this.add(key); }
    }

    private static final class Fixture implements AutoCloseable
    {
        private final Path directory = Files.createTempDirectory("bbs-form-storage-");
        private final Path assets = Files.createDirectories(this.directory.resolve("assets"));
        private final Map<Field, Object> previous = new HashMap<>();
        private final FormCategories catalog = new FormCategories();

        private Fixture() throws Exception
        {
            AssetProvider provider = new AssetProvider();
            provider.register(new ExternalAssetsSourcePack(Link.ASSETS, this.assets.toFile()).providesFiles());
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("storage_fixture"), TestForm.class, null);
            architect.register(Link.bbs("billboard"), BillboardForm.class, null);
            this.replace(BBSMod.class, "provider", provider);
            this.replace(BBSMod.class, "forms", architect);
            this.replace(BBSMod.class, "settingsFolder", Files.createDirectories(this.directory.resolve("settings")).toFile());
            this.replace(BBSModClient.class, "models", new ModelManager(provider));
        }

        private void replace(Class<?> type, String name, Object value) throws Exception
        {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            this.previous.put(field, field.get(null));
            field.set(null, value);
        }

        @Override
        public void close() throws Exception
        {
            for (Map.Entry<Field, Object> entry : this.previous.entrySet()) entry.getKey().set(null, entry.getValue());
            try (Stream<Path> files = Files.walk(this.directory))
            {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
