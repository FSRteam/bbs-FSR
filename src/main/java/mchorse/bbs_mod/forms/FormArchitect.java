package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.data.migration.FormStableIds;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MissingForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.factory.MapFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class FormArchitect extends MapFactory<Form, Void>
{
    private static final List<Consumer<Form>> MODIFIERS = new ArrayList<>();

    /** Adds a step applied to every form created after registration. */
    public static void registerModifier(Consumer<Form> modifier)
    {
        if (modifier != null)
        {
            MODIFIERS.add(modifier);
        }
    }
    @Override
    public String getTypeKey()
    {
        return "id";
    }

    /**
     * Every form read anywhere passes through here, so this is where form fragments living outside
     * the versioned documents — model blocks, morphs, items, old clipboards — get their body part
     * ids on first contact. Film documents arrive already converted (see {@code FilmStableIds});
     * for them this is a no-op walk.
     *
     * <p>The walk has to stay <em>before</em> {@code super.fromData}: a form this build has no
     * class for comes back as a {@link MissingForm} that copies the map it was handed, so it is the
     * already-converted map that ends up written back out. Running the walk after would preserve
     * the legacy shape instead.</p>
     */
    @Override
    public Form fromData(MapType data)
    {
        if (data == null || data.getString(this.getTypeKey()).isEmpty())
        {
            return null;
        }

        if (data != null)
        {
            FormStableIds.ensure(data);
        }

        return super.fromData(data);
    }

    @Override
    public Form create(Link type)
    {
        Form form = super.create(type);

        for (Consumer<Form> modifier : MODIFIERS)
        {
            modifier.accept(form);
        }

        return form;
    }

    @Override
    public Form createUnknown(Link type, MapType data)
    {
        return new MissingForm(data);
    }

    public boolean has(MapType data)
    {
        if (data.has(this.getTypeKey()))
        {
            Link id = Link.create(data.getString(this.getTypeKey()));

            return this.factory.containsKey(id);
        }

        return false;
    }
}
