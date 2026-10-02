package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.factory.IUnknownType;

/**
 * Data-preserving placeholder for a form whose factory type is unavailable. See
 * {@link IUnknownType}.
 *
 * <p>The common form properties are still read, so the placeholder sits where the original sat
 * and a body part holding it keeps its shape. Nothing about it is authored, though: every edit
 * would be dropped by {@link #toData()} anyway, so its values stay off the timeline and it has
 * neither a renderer nor an editor panel — both look their factories up by class and are content
 * to find nothing.</p>
 */
public final class MissingForm extends Form implements IUnknownType
{
    private MapType source;

    public MissingForm(MapType source)
    {
        super();

        this.source = copy(source);

        for (BaseValue value : this.getAll())
        {
            value.invisible();
        }
    }

    public MapType sourceData()
    {
        return copy(this.source);
    }

    @Override
    public Link getUnknownType()
    {
        return Link.create(this.source.getString("id", "missing:form"));
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
            /* Before super, which rewrites the map in place while migrating older forms. */
            this.source = copy(map);
        }

        super.fromData(data);
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
