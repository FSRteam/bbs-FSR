package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.ArrayList;
import java.util.Collection;
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

    /** Replace the palette without sharing mutable color objects with the caller. */
    public void setColors(List<Color> colors)
    {
        List<Color> copy = new ArrayList<>();

        for (Color color : colors)
        {
            copy.add(color.copy());
        }

        this.preNotify();
        this.colors.clear();
        this.colors.addAll(copy);
        this.trim();
        this.postNotify();
    }

    /**
     * The newest color goes in front, so the list reads in the order the palette shows it —
     * oldest entries fall off the tail.
     */
    public void addColor(Color color)
    {
        int i = this.colors.indexOf(color);

        if (i == -1)
        {
            this.preNotify();
            this.colors.add(0, color.copy());
            this.trim();
            this.postNotify();
        }
    }

    /** Drop the oldest entries — the tail, since the newest is put in front. */
    private void trim()
    {
        while (this.limit > 0 && this.colors.size() > this.limit)
        {
            this.colors.remove(this.colors.size() - 1);
        }
    }

    public void remove(int index)
    {
        if (index < 0 || index >= this.colors.size())
        {
            return;
        }

        this.preNotify();
        this.colors.remove(index);
        this.postNotify();
    }

    public void removeAll(Collection<Color> removed)
    {
        this.preNotify();
        this.colors.removeAll(removed);
        this.postNotify();
    }

    /** Move the selected colors before the original insertion slot. */
    public void reorder(List<Color> moved, int insertion)
    {
        List<Color> tail = new ArrayList<>(this.colors.subList(insertion, this.colors.size()));

        this.preNotify();
        this.colors.removeAll(moved);
        tail.removeAll(moved);
        this.colors.addAll(this.colors.size() - tail.size(), moved);
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
