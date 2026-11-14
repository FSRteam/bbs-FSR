package mchorse.bbs_mod.forms.categories;

import mchorse.bbs_mod.data.IMapSerializable;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.forms.UIFormList;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.StringUtils;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

public class FormCategory implements IMapSerializable
{
    public IKey title;
    public final ValueBoolean visible;

    /** The icon the category wears in a form list, before its name. */
    public Icon icon = Icons.FOLDER;

    private final List<Form> forms = new AbstractList<>()
    {
        private final List<Form> entries = new ArrayList<>();

        @Override
        public Form get(int index)
        {
            return this.entries.get(index);
        }

        @Override
        public int size()
        {
            return this.entries.size();
        }

        @Override
        public Form set(int index, Form form)
        {
            Form previous = this.entries.set(index, form);
            FormCategory.this.modCount++;
            return previous;
        }

        @Override
        public void add(int index, Form form)
        {
            this.entries.add(index, form);
            this.modCount++;
            FormCategory.this.modCount++;
        }

        @Override
        public Form remove(int index)
        {
            Form previous = this.entries.remove(index);
            this.modCount++;
            FormCategory.this.modCount++;
            return previous;
        }
    };

    /**
     * Bumped on every change to {@link #forms}, so a view over them (searched) can tell when
     * it's stale without comparing lists.
     */
    private int modCount;

    public FormCategory(IKey title, ValueBoolean visible)
    {
        this.title = title;
        this.visible = visible;
    }

    public FormCategory icon(Icon icon)
    {
        this.icon = icon;

        return this;
    }

    public String getProcessedTitle()
    {
        return StringUtils.processColoredText(this.title.get());
    }

    /** Whether the user may add, remove and rearrange forms here. */
    public boolean canModify(Form form)
    {
        return false;
    }

    public int getModCount()
    {
        return this.modCount;
    }

    public List<Form> getForms()
    {
        return Collections.unmodifiableList(this.forms);
    }

    public List<Form> getDirectForms()
    {
        return this.forms;
    }

    public void addForm(Form form)
    {
        this.insertForm(this.forms.size(), form);
    }

    public void insertForm(int index, Form form)
    {
        if (form != null)
        {
            this.forms.add(Math.max(0, Math.min(index, this.forms.size())), form);
        }
    }

    public void replaceForm(int index, Form form)
    {
        if (form != null && CollectionUtils.inRange(this.forms, index))
        {
            this.forms.set(index, form);
        }
    }

    /**
     * Move a form to a new index, {@code to} being a position in the list as it is now
     * (the way an insertion caret between forms reads).
     */
    public void moveForm(Form form, int to)
    {
        int from = this.indexOfForm(form);

        if (from == -1)
        {
            return;
        }

        this.forms.remove(from);

        if (to > from)
        {
            to -= 1;
        }

        this.forms.add(Math.max(0, Math.min(to, this.forms.size())), form);
    }

    /** Mutations address the chosen instance even when two saved forms have equal values. */
    public int indexOfForm(Form form)
    {
        for (int i = 0; i < this.forms.size(); i++)
        {
            if (this.forms.get(i) == form) return i;
        }

        return -1;
    }

    public void removeForm(Form form)
    {
        int index = this.indexOfForm(form);

        if (index >= 0)
        {
            this.forms.remove(index);
        }
    }

    public void clearForms()
    {
        this.forms.clear();
    }

    public UIFormCategory createUI(UIFormList list)
    {
        return new UIFormCategory(this, list);
    }

    @Override
    public void fromData(MapType data)
    {
        this.forms.clear();

        if (data.has("title"))
        {
            this.title = IKey.constant(data.getString("title"));
        }

        if (data.has("id"))
        {
            this.visible.setId(data.getString("id"));
        }

        for (BaseType formData : data.getList("forms"))
        {
            if (formData.isMap())
            {
                Form form = FormUtils.fromData(formData.asMap());

                if (form != null)
                {
                    this.forms.add(form);
                }
            }
        }

    }

    @Override
    public void toData(MapType data)
    {
        ListType forms = new ListType();

        data.putString("title", this.title.get());
        data.putString("id", this.visible.getId());
        data.put("forms", forms);

        for (int i = 0; i < this.forms.size(); i++)
        {
            MapType formData = FormUtils.toData(this.forms.get(i));

            if (formData != null)
            {
                forms.add(formData);
            }
            else
            {
                System.err.println("Form at index " + i + " is null in \"" + this.title.get() + "\" category!");
            }
        }
    }
}
