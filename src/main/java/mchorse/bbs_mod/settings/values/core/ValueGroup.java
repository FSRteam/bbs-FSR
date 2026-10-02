package mchorse.bbs_mod.settings.values.core;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ValueGroup extends BaseValueGroup
{
    private Map<String, BaseValue> children = new LinkedHashMap<>();

    /**
     * Keys read out of the data that no child of this group claimed, kept so they can be written
     * back untouched.
     *
     * <p>Two kinds end up here. A key carrying a namespace ({@code myaddon:something}) belongs to
     * an addon that is not loaded right now — dropping it would mean that starting the game once
     * without the addon quietly rewrites every scene that used it. A key named by
     * {@link #preservedUnknownKeys()} belongs to BBS itself: the parent is modelled here, the
     * child is one build newer than this one.</p>
     *
     * <p>Both kinds are rebuilt from the data being read — see {@link #fromData(BaseType)} — so a
     * key that is not in that data is not written back, and a property that was removed from the
     * data does not come back. Neither kind is written over a child that claims the same key.</p>
     */
    private Map<String, BaseType> foreign;

    public Icon icon;

    public ValueGroup(String id)
    {
        super(id);
    }

    public void removeAll()
    {
        this.children.clear();
    }

    public void add(BaseValue value)
    {
        if (value != null)
        {
            this.children.put(value.getId(), value);
            value.setParent(this);
        }
    }

    public void remove(BaseValue child)
    {
        BaseValue baseValue = this.children.get(child.getId());

        if (baseValue == child)
        {
            this.children.remove(child.getId());
        }
    }

    @Override
    public List<BaseValue> getAll()
    {
        return new ArrayList<>(this.children.values());
    }

    public Map<String, BaseValueBasic> getAllMap()
    {
        Map<String, BaseValueBasic> map = new HashMap<>();

        for (BaseValue value : this.children.values())
        {
            if (value instanceof BaseValueBasic<?> basic)
            {
                map.put(basic.getId(), basic);
            }
        }

        return map;
    }

    @Override
    public BaseValue get(String key)
    {
        return this.children.get(key);
    }

    @Override
    public void copy(BaseValueGroup group)
    {
        for (BaseValue groupValue : group.getAll())
        {
            BaseValue value = this.children.get(groupValue.getId());

            if (value != null)
            {
                value.copy(groupValue);
            }
        }

        /* The data no child here claims travels with the group, so a copy is as lossless as a
         * read followed by a save. */
        if (group instanceof ValueGroup other)
        {
            this.foreign = other.foreign == null ? null : copyForeign(other.foreign);
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        boolean equals = super.equals(obj);

        if (equals)
        {
            return equals;
        }

        if (obj instanceof ValueGroup group)
        {
            return this.children.equals(group.children);
        }

        return false;
    }

    @Override
    public BaseType toData()
    {
        MapType data = new MapType();

        for (BaseValue value : this.children.values())
        {
            if (this.canPersist(value))
            {
                data.put(value.getId(), value.toData());
            }
        }

        if (this.foreign != null)
        {
            for (Map.Entry<String, BaseType> entry : this.foreign.entrySet())
            {
                /* A child written above wins: the day this build gains the value that writes a
                 * preserved key, the preserved copy stops being written with no change here. */
                if (!data.has(entry.getKey()))
                {
                    data.put(entry.getKey(), entry.getValue().copy());
                }
            }
        }

        return data;
    }

    protected boolean canPersist(BaseValue value)
    {
        return true;
    }

    /**
     * Unclaimed keys that carry no namespace but still have to survive a round trip, because this
     * build does not model the child that writes them.
     *
     * <p>Empty on purpose, and it has to stay the exception rather than the rule. BBS's own
     * properties never carry a namespace, which is what lets a property that was removed — from
     * the data, or from the code — disappear on save instead of being remembered forever.
     * Preserving unknown keys indiscriminately would undo exactly that, by resurrecting every
     * property a newer build used to write. So a group overrides this only for keys whose parent
     * it models and whose writer this build is known to be missing.</p>
     *
     * @see #foreign
     */
    protected Set<String> preservedUnknownKeys()
    {
        return Collections.emptySet();
    }

    private static Map<String, BaseType> copyForeign(Map<String, BaseType> foreign)
    {
        Map<String, BaseType> copy = new LinkedHashMap<>();

        for (Map.Entry<String, BaseType> entry : foreign.entrySet())
        {
            copy.put(entry.getKey(), entry.getValue().copy());
        }

        return copy;
    }

    @Override
    public void fromData(BaseType data)
    {
        if (!data.isMap())
        {
            return;
        }

        this.foreign = null;

        for (Map.Entry<String, BaseType> entry : data.asMap())
        {
            BaseValue value = this.children.get(entry.getKey());

            if (value != null)
            {
                value.setParent(this);
                value.fromData(entry.getValue());
            }
            else if (entry.getKey().indexOf(':') >= 0 || this.preservedUnknownKeys().contains(entry.getKey()))
            {
                if (this.foreign == null)
                {
                    this.foreign = new LinkedHashMap<>();
                }

                this.foreign.put(entry.getKey(), entry.getValue().copy());
            }
        }
    }
}
