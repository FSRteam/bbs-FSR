package mchorse.bbs_mod.client.film.collaboration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Time-bounded, latest-wins path accumulator for local Film edits. */
final class BBSFilmLocalBatchBuffer<T>
{
    private final int maximumEntries;
    private final long intervalNanos;
    private final Map<List<String>, T> targets = new LinkedHashMap<>();
    private long openedAtNanos;
    private boolean open;
    private boolean overflowed;

    BBSFilmLocalBatchBuffer(int maximumEntries, long intervalNanos)
    {
        if (maximumEntries <= 0 || intervalNanos <= 0L)
        {
            throw new IllegalArgumentException("coalescing bounds must be positive");
        }

        this.maximumEntries = maximumEntries;
        this.intervalNanos = intervalNanos;
    }

    void offer(List<String> path, T value, long nowNanos)
    {
        List<String> safePath = List.copyOf(Objects.requireNonNull(path, "path"));

        Objects.requireNonNull(value, "value");

        if (!this.open)
        {
            this.openedAtNanos = nowNanos;
            this.open = true;
        }

        if (this.targets.containsKey(safePath))
        {
            this.targets.put(safePath, value);
            return;
        }

        for (List<String> existing : this.targets.keySet())
        {
            if (isPrefix(existing, safePath))
            {
                /* The stored ancestor is a live BaseValue in production and
                 * therefore already serializes the newest descendant state. */
                return;
            }
        }

        this.targets.keySet().removeIf((existing) -> isPrefix(safePath, existing));

        if (this.targets.size() >= this.maximumEntries)
        {
            this.overflowed = true;
            return;
        }

        this.targets.put(safePath, value);
    }

    boolean isDue(long nowNanos)
    {
        return this.open && nowNanos - this.openedAtNanos >= this.intervalNanos;
    }

    boolean isEmpty()
    {
        return !this.open;
    }

    Batch<T> drain()
    {
        if (!this.open)
        {
            return new Batch<>(List.of(), false);
        }

        List<Target<T>> drained = new ArrayList<>(this.targets.size());

        for (Map.Entry<List<String>, T> entry : this.targets.entrySet())
        {
            drained.add(new Target<>(entry.getKey(), entry.getValue()));
        }

        drained.sort(Comparator
            .comparingInt((Target<T> target) -> target.path().size())
            .thenComparing(Target::path, BBSFilmLocalBatchBuffer::comparePaths));

        boolean wasOverflowed = this.overflowed;

        this.targets.clear();
        this.open = false;
        this.overflowed = false;

        return new Batch<>(drained, wasOverflowed);
    }

    private static boolean isPrefix(List<String> ancestor, List<String> child)
    {
        if (ancestor.size() > child.size())
        {
            return false;
        }

        for (int i = 0; i < ancestor.size(); i++)
        {
            if (!ancestor.get(i).equals(child.get(i)))
            {
                return false;
            }
        }

        return true;
    }

    private static int comparePaths(List<String> first, List<String> second)
    {
        int count = Math.min(first.size(), second.size());

        for (int i = 0; i < count; i++)
        {
            int compared = first.get(i).compareTo(second.get(i));

            if (compared != 0)
            {
                return compared;
            }
        }

        return Integer.compare(first.size(), second.size());
    }

    record Target<T>(List<String> path, T value)
    {
        Target
        {
            path = List.copyOf(path);
            Objects.requireNonNull(value, "value");
        }
    }

    record Batch<T>(List<Target<T>> targets, boolean overflowed)
    {
        Batch
        {
            targets = List.copyOf(targets);
        }
    }
}
