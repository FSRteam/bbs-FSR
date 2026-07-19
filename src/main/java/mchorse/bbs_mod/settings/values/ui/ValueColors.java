package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class ValueColors extends BaseValue
{
    private List<Color> colors = new ArrayList<>();
    private int limit;

    public ValueColors(String id)
    {
        super(id);
    }

    /** Set a positive maximum for bounded histories; zero leaves the list unlimited. */
    public ValueColors limit(int limit)
    {
        if (limit < 0)
        {
            throw new IllegalArgumentException("Color history limit cannot be negative");
        }

        this.limit = limit;
        this.trim();

        return this;
    }

    public List<Color> getCurrentColors()
    {
        return this.colors;
    }

    public void addColor(Color color)
    {
        int i = this.colors.indexOf(color);

        if (i == -1)
        {
            this.preNotify();
            this.colors.add(color.copy());
            this.trim();
            this.postNotify();
        }
    }

    private void trim()
    {
        while (this.limit > 0 && this.colors.size() > this.limit)
        {
            this.colors.remove(0);
        }
    }

    public void remove(int index)
    {
        this.preNotify();
        this.colors.remove(index);
        this.postNotify();
    }

    @Override
    public BaseType toData()
    {
        ListType list = new ListType();

        for (Color color : this.colors)
        {
            list.addInt(color.getARGBColor());
        }

        return list;
    }

    @Override
    public void fromData(BaseType data)
    {
        if (!BaseType.isList(data))
        {
            return;
        }

        ListType list = (ListType) data;

        for (BaseType color : list)
        {
            if (color.isNumeric())
            {
                this.colors.add(new Color().set(color.asNumeric().intValue()));
            }
        }

        this.trim();
    }

    @Override
    public String toString()
    {
        StringJoiner joiner = new StringJoiner(", ");

        for (Color color : this.colors)
        {
            joiner.add("#" + Integer.toHexString(color.getARGBColor()));
        }

        return joiner.toString();
    }
}
