package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.events.register.RegisterFormCategoriesEvent;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.ExtraFormSection;
import mchorse.bbs_mod.forms.sections.FormSection;
import mchorse.bbs_mod.forms.sections.ModelFormSection;
import mchorse.bbs_mod.forms.sections.ParticleFormSection;
import mchorse.bbs_mod.forms.sections.RecentFormSection;
import mchorse.bbs_mod.forms.sections.UserFormSection;
import mchorse.bbs_mod.utils.watchdog.IWatchDogListener;
import mchorse.bbs_mod.utils.watchdog.WatchDogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class FormCategories implements IWatchDogListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FormCategories.class);

    /**
     * Sections an addon added to the palette.
     *
     * <p>Factories rather than sections, because {@link #setup()} runs again on every asset reload
     * and rebuilds its list from scratch — a section handed over once would be thrown away the
     * first time the user touched a file.</p>
     */
    private static final List<Function<FormCategories, FormSection>> EXTRA_SECTIONS = new ArrayList<>();

    public final VisibilityManager visibility = new VisibilityManager();

    private List<FormSection> sections = new ArrayList<>();
    private RecentFormSection recentForms = new RecentFormSection(this);
    private UserFormSection userForms = new UserFormSection(this);
    private ExtraFormSection extraForms = new ExtraFormSection(this);

    private long lastUpdate;

    /**
     * Adds a section — a top-level tab — to the form palette.
     *
     * <p>Without this, an addon's forms worked but had nowhere to be picked from: the list of
     * sections was private and built from scratch in {@link #setup()}.</p>
     */
    public static void registerSection(Function<FormCategories, FormSection> factory)
    {
        if (factory != null)
        {
            EXTRA_SECTIONS.add(factory);
        }
    }

    /** Removes a previously added section factory; returns whether it was there. */
    public static boolean unregisterSection(Function<FormCategories, FormSection> factory)
    {
        return factory != null && EXTRA_SECTIONS.remove(factory);
    }

    /** The section factories added so far, in registration order. */
    public static List<Function<FormCategories, FormSection>> getRegisteredSections()
    {
        return Collections.unmodifiableList(EXTRA_SECTIONS);
    }

    /* Setup */

    public void setup()
    {
        LOGGER.info("[bbs-form-categories] setup started");

        this.sections.clear();
        this.sections.add(this.recentForms);
        this.sections.add(this.userForms);
        this.sections.add(new ModelFormSection(this));
        this.sections.add(new ParticleFormSection(this));
        this.sections.add(this.extraForms);

        for (Function<FormCategories, FormSection> factory : EXTRA_SECTIONS)
        {
            FormSection section = factory.apply(this);

            if (section != null)
            {
                this.sections.add(section);
            }
        }

        for (FormSection section : this.sections)
        {
            section.initiate();
        }

        LOGGER.info("[bbs-form-categories] posting RegisterFormCategoriesEvent");
        BBSMod.events.post(new RegisterFormCategoriesEvent(this::addExtraForm));

        this.markDirty();
        this.visibility.read();
        LOGGER.info("[bbs-form-categories] setup completed with {} category group(s)", this.getAllCategories().size());
    }

    public long getLastUpdate()
    {
        return lastUpdate;
    }

    public void markDirty()
    {
        this.lastUpdate = System.currentTimeMillis();
    }

    public RecentFormSection getRecentForms()
    {
        return this.recentForms;
    }

    public UserFormSection getUserForms()
    {
        return this.userForms;
    }

    public void addExtraForm(mchorse.bbs_mod.forms.forms.Form form)
    {
        if (form == null)
        {
            LOGGER.warn("[bbs-form-categories] ignored null extra form");

            return;
        }

        LOGGER.info("[bbs-form-categories] adding extra form {}", form.getClass().getName());
        this.extraForms.addForm(form);
        this.markDirty();
    }

    public void removeExtraForm(mchorse.bbs_mod.forms.forms.Form form)
    {
        if (form == null)
        {
            return;
        }

        this.extraForms.removeForm(form);
        this.markDirty();
    }

    public List<FormCategory> getAllCategories()
    {
        List<FormCategory> formCategories = new ArrayList<>();

        for (FormSection section : this.sections)
        {
            formCategories.addAll(section.getCategories());
        }

        return formCategories;
    }

    @Override
    public void accept(Path path, WatchDogEvent event)
    {
        for (FormSection section : this.sections)
        {
            section.accept(path, event);
        }
    }
}
