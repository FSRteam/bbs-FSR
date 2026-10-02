package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MissingForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.factory.MapFactory;

public class FormArchitect extends MapFactory<Form, Void>
{
    @Override
    public String getTypeKey()
    {
        return "id";
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