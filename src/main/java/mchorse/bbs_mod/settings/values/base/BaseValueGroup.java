package mchorse.bbs_mod.settings.values.base;

import mchorse.bbs_mod.utils.DataPath;

import java.util.List;

public abstract class BaseValueGroup extends BaseValue
{
    public BaseValueGroup(String id)
    {
        super(id);
    }

    public abstract List<BaseValue> getAll();

    public abstract BaseValue get(String key);

    public BaseValue findRecursively(DataPath path)
    {
        if (path == null || path.strings.isEmpty())
        {
            return null;
        }

        BaseValue value = this;
        int index = path.strings.get(0).equals(this.getId()) ? 1 : 0;

        while (value instanceof BaseValueGroup group && index < path.size())
        {
            value = group.get(path.strings.get(index));
            index += 1;
        }

        return index == path.size() ? value : null;
    }

    public BaseValue getRecursively(DataPath path)
    {
        BaseValue value = this.findRecursively(path);

        if (value == null)
        {
            throw new IllegalStateException("Property by path " + path + " can't be found!");
        }

        return value;
    }

    public abstract void copy(BaseValueGroup group);
}
