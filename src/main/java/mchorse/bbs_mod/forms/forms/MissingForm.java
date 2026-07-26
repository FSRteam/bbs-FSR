package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;

/** Data-preserving placeholder for a form whose factory type is unavailable. */
public final class MissingForm extends Form
{
    private MapType source;

    public MissingForm(MapType source)
    {
        this.source = copy(source);
    }

    public MapType sourceData()
    {
        return copy(this.source);
    }

    @Override
    public String getFormId()
    {
        return this.source.getString("id", "missing:form");
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "Missing form: " + this.getFormId();
    }

    @Override
    public void fromData(BaseType data)
    {
        if (data instanceof MapType map)
        {
            this.source = copy(map);
        }
    }

    @Override
    public BaseType toData()
    {
        return this.sourceData();
    }

    private static MapType copy(MapType source)
    {
        return source == null ? new MapType() : (MapType) source.copy();
    }
}
