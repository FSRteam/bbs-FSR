package mchorse.bbs_mod.film.markers;

import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueStableList;
import mchorse.bbs_mod.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Film-wide notes addressed by the shared stable-list identity contract. */
public class FilmMarkers extends ValueStableList<FilmMarker>
{
    public FilmMarkers(String id)
    {
        super(id);
    }

    public FilmMarker addMarker(int tick)
    {
        FilmMarker marker = new FilmMarker("");

        marker.tick.set(Math.max(0, tick));
        marker.color.set(FilmMarker.randomBrightColor());

        this.preNotify();
        this.add(marker);
        this.postNotify();

        return marker;
    }

    public FilmMarker getById(String id)
    {
        return (FilmMarker) this.get(id);
    }

    public void remove(FilmMarker marker)
    {
        int index = CollectionUtils.getIndex(this.list, marker);

        if (CollectionUtils.inRange(this.list, index))
        {
            this.preNotify();
            this.list.remove(index);
            this.postNotify();
        }
    }

    /** Compatibility entry point used by the FSR marker overlay and timeline menus. */
    public void removeMarker(FilmMarker marker)
    {
        this.remove(marker);
    }

    /** Compatibility with callers that held a marker as a generic value. */
    public void remove(BaseValue value)
    {
        if (value instanceof FilmMarker marker)
        {
            this.remove(marker);
        }
    }

    /** Compatibility with the former group-backed container. */
    public void removeAll()
    {
        this.list.clear();
    }

    @Override
    public void copy(BaseValueGroup group)
    {
        this.fromData(group.toData());
    }

    public FilmMarker getAt(int tick)
    {
        for (FilmMarker marker : this.list)
        {
            if (marker.tick.get() == tick)
            {
                return marker;
            }
        }

        return null;
    }

    public List<FilmMarker> getSorted()
    {
        List<FilmMarker> sorted = new ArrayList<>(this.list);

        sorted.sort(Comparator.comparingInt((marker) -> marker.tick.get()));

        return sorted;
    }

    public int findNextTick(int tick)
    {
        int output = Integer.MAX_VALUE;

        for (FilmMarker marker : this.list)
        {
            if (marker.tick.get() > tick)
            {
                output = Math.min(output, marker.tick.get());
            }
        }

        return output == Integer.MAX_VALUE ? tick : output;
    }

    public int findPreviousTick(int tick)
    {
        int output = Integer.MIN_VALUE;

        for (FilmMarker marker : this.list)
        {
            if (marker.tick.get() < tick)
            {
                output = Math.max(output, marker.tick.get());
            }
        }

        return output == Integer.MIN_VALUE ? tick : output;
    }

    @Override
    protected FilmMarker create(String id)
    {
        return new FilmMarker(id);
    }
}
