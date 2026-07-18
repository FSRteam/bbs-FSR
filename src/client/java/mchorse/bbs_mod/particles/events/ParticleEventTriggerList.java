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

    /* Null entries reserve the position of a parsed string; non-null entries
     * are unsupported siblings that must survive edits to supported strings. */
    private final List<BaseType> elementOrder = new ArrayList<>();
    private BaseType raw;
    private boolean edited;
    private boolean hasRawSiblings;

    public void fromData(BaseType data)
    {
        this.events.clear();
        this.elementOrder.clear();
        this.raw = null;
        this.edited = false;
        this.hasRawSiblings = false;

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
            for (BaseType element : data.asList())
            {
                if (element.isString() && !element.asString().trim().isEmpty())
                {
                    this.add(element.asString());
                    this.elementOrder.add(null);
                }
                else
                {
                    this.elementOrder.add(element.copy());
                    this.hasRawSiblings = true;
                }
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

        if (this.events.isEmpty() && !this.hasRawSiblings)
        {
            return null;
        }

        if (!this.hasRawSiblings && this.events.size() == 1)
        {
            return new StringType(this.events.get(0));
        }

        ListType list = new ListType();
        int eventIndex = 0;

        if (this.hasRawSiblings)
        {
            for (BaseType element : this.elementOrder)
            {
                if (element == null)
                {
                    if (eventIndex < this.events.size())
                    {
                        list.addString(this.events.get(eventIndex++));
                    }
                }
                else
                {
                    list.add(element.copy());
                }
            }
        }

        while (eventIndex < this.events.size())
        {
            list.addString(this.events.get(eventIndex++));
        }

        return list;
    }

    public boolean isEmpty()
    {
        return this.events.isEmpty() && this.raw == null && !this.hasRawSiblings;
    }

    public void markEdited()
    {
        this.edited = true;
        this.raw = null;
    }
}
