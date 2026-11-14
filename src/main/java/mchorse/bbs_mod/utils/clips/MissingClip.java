package mchorse.bbs_mod.utils.clips;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.factory.IUnknownType;

/** Non-executing, data-preserving placeholder for an unavailable clip type. */
public final class MissingClip extends Clip implements IUnknownType
{
    private MapType source = new MapType();

    public MissingClip(MapType source)
    {
        this.fromData(source == null ? new MapType() : source);
    }

    @Override
    public void fromData(BaseType data)
    {
        if (data instanceof MapType map)
        {
            this.source = (MapType) map.copy();
        }

        super.fromData(data);
        this.enabled.set(false);
        this.tick.set(this.source.getInt("tick"));
        this.duration.set(Math.max(1, this.source.getInt("duration", 1)));
        this.layer.set(Math.max(0, this.source.getInt("layer")));
        this.title.set(this.source.getString("title", "Missing clip: " + this.typeId()));
    }

    public MapType sourceData()
    {
        return (MapType) this.source.copy();
    }

    @Override
    public BaseType toData()
    {
        return this.sourceData();
    }

    @Override
    public Clip copy()
    {
        return new MissingClip(this.source);
    }

    public String typeId()
    {
        return this.source.getString("type", "missing:clip");
    }

    /**
     * The placeholder answers for the type it stands in for, so a factory asked to save one
     * writes back the original type id instead of failing over an unregistered class.
     */
    @Override
    public Link getUnknownType()
    {
        return Link.create(this.typeId());
    }

    @Override
    protected Clip create()
    {
        return new MissingClip(this.source);
    }
}
