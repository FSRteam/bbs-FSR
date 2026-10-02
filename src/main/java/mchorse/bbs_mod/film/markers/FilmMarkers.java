package mchorse.bbs_mod.film.markers;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The film's markers, in authoring order — {@link #getSorted()} is what drawing and navigation ask
 * for.
 *
 * <p>Stable ids rather than positions: undo addresses a marker's own fields by path
 * ({@code markers/&lt;id&gt;/title}), and a positional id would point that path at a neighbour the
 * moment another marker is inserted before it. FSR has no stable-id list type, so the markers are
 * children of this group keyed by that id while the saved shape stays the plain list of marker maps
 * — each map carries its own {@code id}, which keeps the file readable by any reader that keys
 * elements by id.
 */
public class FilmMarkers extends ValueGroup
{
    private final List<FilmMarker> markers = new ArrayList<>();

    public FilmMarkers(String id)
    {
        super(id);
    }

    public FilmMarker addMarker(int tick)
    {
        FilmMarker marker = new FilmMarker(this.nextId());

        marker.tick.set(Math.max(0, tick));
        marker.color.set(FilmMarker.randomBrightColor());

        this.preNotify();
        this.attach(marker);
        this.postNotify();

        return marker;
    }

    public FilmMarker getById(String id)
    {
        return (FilmMarker) this.get(id);
    }

    public void removeMarker(FilmMarker marker)
    {
        if (marker == null || !this.markers.contains(marker))
        {
            return;
        }

        this.preNotify();
        this.markers.remove(marker);
        super.remove(marker);
        this.postNotify();
    }

    /**
     * @return The marker sitting exactly on the given tick, or {@code null}.
     */
    public FilmMarker getAt(int tick)
    {
        for (FilmMarker marker : this.markers)
        {
            if (marker.tick.get() == tick)
            {
                return marker;
            }
        }

        return null;
    }

    /** The markers without letting callers restructure the container behind its back. */
    public List<FilmMarker> getList()
    {
        return Collections.unmodifiableList(this.markers);
    }

    public List<FilmMarker> getSorted()
    {
        List<FilmMarker> sorted = new ArrayList<>(this.markers);

        sorted.sort(Comparator.comparingInt((marker) -> marker.tick.get()));

        return sorted;
    }

    /**
     * @return The nearest marker's tick after the given one, or the given one when there is none —
     * the same "stay put at the edge" contract as {@link mchorse.bbs_mod.utils.clips.Clips#findNextTick(int)}.
     */
    public int findNextTick(int tick)
    {
        int output = Integer.MAX_VALUE;

        for (FilmMarker marker : this.markers)
        {
            int markerTick = marker.tick.get();

            if (markerTick > tick)
            {
                output = Math.min(output, markerTick);
            }
        }

        return output == Integer.MAX_VALUE ? tick : output;
    }

    public int findPreviousTick(int tick)
    {
        int output = Integer.MIN_VALUE;

        for (FilmMarker marker : this.markers)
        {
            int markerTick = marker.tick.get();

            if (markerTick < tick)
            {
                output = Math.max(output, markerTick);
            }
        }

        return output == Integer.MIN_VALUE ? tick : output;
    }

    private void attach(FilmMarker marker)
    {
        this.markers.add(marker);
        this.add(marker);
    }

    /**
     * A stable id that can never be mistaken for a list index: eight hex characters with at least
     * one letter, the same shape the camera tracks' ids use.
     */
    private String nextId()
    {
        String id;

        do
        {
            id = String.format("%08x", ThreadLocalRandom.current().nextInt());
        }
        while (!hasLetter(id) || this.get(id) != null);

        return id;
    }

    private static boolean hasLetter(String id)
    {
        for (int i = 0; i < id.length(); i++)
        {
            char c = id.charAt(i);

            if (c >= 'a' && c <= 'f')
            {
                return true;
            }
        }

        return false;
    }

    /** An id read from disk is kept as-is as long as it is present and not already taken. */
    private boolean isUsableId(String id)
    {
        return id != null && !id.isBlank() && this.get(id) == null;
    }

    @Override
    public void removeAll()
    {
        this.markers.clear();
        super.removeAll();
    }

    /**
     * The saved shape is a list of marker maps, each carrying its own {@code id}: that keeps the
     * marker's identity in the file rather than in its position, so a load reconstructs the very
     * ids the paths on the other side of the wire refer to.
     */
    @Override
    public void fromData(BaseType data)
    {
        this.removeAll();

        if (!data.isList())
        {
            return;
        }

        for (BaseType item : data.asList())
        {
            String stored = item.isMap() ? item.asMap().getString("id", "") : "";
            String id = this.isUsableId(stored) ? stored : this.nextId();
            FilmMarker marker = new FilmMarker(id);

            marker.fromData(item);
            marker.id.set(id);
            this.attach(marker);
        }
    }

    @Override
    public BaseType toData()
    {
        ListType data = new ListType();

        for (FilmMarker marker : this.markers)
        {
            data.add(marker.toData());
        }

        return data;
    }

    @Override
    public void copy(BaseValueGroup group)
    {
        this.removeAll();

        for (BaseValue value : group.getAll())
        {
            String id = this.isUsableId(value.getId()) ? value.getId() : this.nextId();
            FilmMarker marker = new FilmMarker(id);

            marker.fromData(value.toData());
            marker.id.set(id);
            this.attach(marker);
        }
    }
}
