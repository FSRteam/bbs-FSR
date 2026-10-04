package mchorse.bbs_mod.film.camera;

import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.core.ValueList;
import mchorse.bbs_mod.utils.CollectionUtils;

/** Ordered timeline markers used by the output camera resolver. */
public class CameraCuts extends ValueList<CameraCut>
{
    public CameraCuts(String id)
    {
        super(id);
    }

    public CameraCut setCut(int tick, String cameraId)
    {
        int markerTick = Math.max(0, tick);
        int index = this.list.size();

        for (int i = 0; i < this.list.size(); i++)
        {
            if (this.list.get(i).tick.get() == markerTick)
            {
                index = Math.min(index, i);
            }
        }

        CameraCut selected = new CameraCut(String.valueOf(index), markerTick, cameraId);

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        this.list.removeIf(cut -> cut.tick.get() == markerTick);
        this.add(index, selected);
        this.sync();
        this.postNotify(IValueListener.FLAG_UNMERGEABLE);

        return selected;
    }

    /** Remove the marker at a timeline tick, including duplicate imported entries. */
    public boolean removeCut(int tick)
    {
        if (this.list.stream().noneMatch(cut -> cut.tick.get() == tick))
        {
            return false;
        }

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        this.list.removeIf(cut -> cut.tick.get() == tick);
        this.sync();
        this.postNotify(IValueListener.FLAG_UNMERGEABLE);

        return true;
    }

    public void removeAt(int index)
    {
        if (CollectionUtils.inRange(this.list, index))
        {
            this.preNotify(IValueListener.FLAG_UNMERGEABLE);
            this.list.remove(index);
            this.sync();
            this.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }
    }

    @Override
    protected CameraCut create(String id)
    {
        return new CameraCut(id);
    }
}
