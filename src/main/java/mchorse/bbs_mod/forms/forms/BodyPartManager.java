package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.settings.values.core.ValueStableList;
import mchorse.bbs_mod.utils.CollectionUtils;

public class BodyPartManager extends ValueStableList<BodyPart>
{
    public BodyPartManager(String id)
    {
        super(id);
    }

    public Form getOwner()
    {
        return this.parent instanceof Form form ? form : null;
    }

    public void addBodyPart(BodyPart part)
    {
        this.preNotify();
        this.add(part);
        this.postNotify();
    }

    public void removeBodyPart(BodyPart part)
    {
        this.preNotify();
        int index = CollectionUtils.getIndex(this.list, part);
        if (index >= 0)
        {
            this.list.remove(index);
        }
        this.postNotify();
    }

    public void moveBodyPart(BodyPart part, int index)
    {
        this.preNotify();

        int current = CollectionUtils.getIndex(this.list, part);
        if (current >= 0)
        {
            this.list.remove(current);
            this.list.add(index, part);
        }

        this.postNotify();
    }

    public void update(IEntity target)
    {
        for (BodyPart part : this.list)
        {
            part.update(target);
        }
    }

    @Override
    protected BodyPart create(String id)
    {
        return new BodyPart(id);
    }
}
