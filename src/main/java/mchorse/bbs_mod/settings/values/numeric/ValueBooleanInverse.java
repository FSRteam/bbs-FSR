package mchorse.bbs_mod.settings.values.numeric;

import mchorse.bbs_mod.data.types.BaseType;

/** Compatibility view for an option that has been replaced by its positive form. */
public final class ValueBooleanInverse extends ValueBoolean
{
    private final ValueBoolean target;

    public ValueBooleanInverse(String id, ValueBoolean target)
    {
        super(id, !target.getOriginalValue());
        this.target = target;
        this.invisible();
    }

    @Override
    public Boolean get()
    {
        return !this.target.get();
    }

    @Override
    public Boolean getOriginalValue()
    {
        return !this.target.getOriginalValue();
    }

    @Override
    public Boolean getRuntimeValue()
    {
        Boolean runtime = this.target.getRuntimeValue();

        return runtime == null ? null : !runtime;
    }

    @Override
    public void set(Boolean value, int flag)
    {
        this.target.set(!value, flag);
    }

    @Override
    public void setRuntimeValue(Boolean value)
    {
        this.target.setRuntimeValue(value == null ? null : !value);
    }

    @Override
    public BaseType toData()
    {
        return this.getFactory().toData(this.getOriginalValue());
    }

    @Override
    public void fromData(BaseType data)
    {
        this.target.fromData(this.target.getFactory().toData(!this.getFactory().fromData(data)));
    }

    @Override
    public String toString()
    {
        return Boolean.toString(this.get());
    }

    @Override
    public boolean equals(Object other)
    {
        return other instanceof ValueBoolean value && this.getOriginalValue().equals(value.getOriginalValue());
    }
}
