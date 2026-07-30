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
import java.util.List;

public class FormCategories implements IWatchDogListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FormCategories.class);

    public final VisibilityManager visibility = new VisibilityManager();

    private List<FormSection> sections = new ArrayList<>();
    private RecentFormSection recentForms = new RecentFormSection(this);
    private UserFormSection userForms = new UserFormSection(this);
    private ExtraFormSection extraForms = new ExtraFormSection(this);

    private long lastUpdate;

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
