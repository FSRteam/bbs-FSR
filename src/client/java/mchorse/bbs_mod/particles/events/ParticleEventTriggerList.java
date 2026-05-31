package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.StringType;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class ParticleEventTriggerList
{
    public final List<String> events = new ArrayList<>();

    private BaseType raw;
    private boolean edited;

    public void fromData(BaseType data)
    {
        this.events.clear();
        this.raw = null;
        this.edited = false;

        if (data == null)
        {
            return;
        }

        if (data.isString())
        {
            this.add(data.asString());
        }
        else if (data.isList())
        {
            boolean supported = true;

            for (BaseType element : data.asList())
            {
                if (element.isString())
                {
                    this.add(element.asString());
                }
                else
                {
                    supported = false;
                }
            }

            if (!supported)
            {
                this.raw = data.copy();
            }
        }
        else
        {
            this.raw = data.copy();
        }
    }

    public void add(String event)
    {
        if (event != null && !event.trim().isEmpty())
        {
            this.events.add(event.trim());
        }
    }

    public void setFromCSV(String csv)
    {
        this.events.clear();
        this.markEdited();

        if (csv == null || csv.trim().isEmpty())
        {
            return;
        }

        for (String split : csv.split(","))
        {
            this.add(split);
        }
    }

    public String toCSV()
    {
        StringJoiner joiner = new StringJoiner(", ");

        for (String event : this.events)
        {
            joiner.add(event);
        }

        return joiner.toString();
    }

    public BaseType toData()
    {
        if (this.raw != null && !this.edited)
        {
            return this.raw.copy();
        }

        if (this.events.isEmpty())
        {
            return null;
        }

        if (this.events.size() == 1)
        {
            return new StringType(this.events.get(0));
        }

        ListType list = new ListType();

        for (String event : this.events)
        {
            list.addString(event);
        }

        return list;
    }

    public boolean isEmpty()
    {
        return this.events.isEmpty() && this.raw == null;
    }

    public void markEdited()
    {
        this.edited = true;
        this.raw = null;
    }
}
