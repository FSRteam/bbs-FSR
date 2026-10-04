package mchorse.bbs_mod.cubic.render;

import com.mojang.blaze3d.vertex.VertexBuffer;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The CPU half of a welded model, kept on the GPU between draws.
 *
 * <p>A welded bone's geometry is a pure function of the pose (every bone's transform, colour and
 * lighting), the draw's light/overlay/colour and the shape keys — and it is baked in the model's
 * ROOT frame, so the same buffer serves every pass of a frame (world, stencil, Iris shadow) and
 * every frame in which nothing moved, drawn with the pass's own model-view. Before, that geometry
 * was re-walked and re-tessellated on every pass, and, for translucent textures, uploaded into a
 * fresh GL buffer that was freed at the end of the frame.</p>
 *
 * <p>Several entries, not one: the translucent queue borrows a buffer until its end-of-frame
 * flush, and a frame can bake more than one pose of the same model (onion skin). A buffer lent
 * out this frame is never rebuilt this frame; the ring grows to the number of concurrent poses,
 * capped — past the cap the caller falls back to the old owned, per-frame buffer.</p>
 */
public class WeldGeometryCache
{
    /** Upstream's cap. Past it {@link #acquire(long)} returns null and the caller bakes its own buffer. */
    private static final int MAX_ENTRIES = 16;

    private final int maxEntries;
    private final List<Entry> entries = new ArrayList<>();

    public WeldGeometryCache()
    {
        this(MAX_ENTRIES);
    }

    /**
     * @param maxEntries the ring size. Exposed for the regression, which needs to reach the cap
     *                   without baking sixteen real poses.
     */
    public WeldGeometryCache(int maxEntries)
    {
        this.maxEntries = maxEntries;
    }

    public static class Entry
    {
        /**
         * The GPU buffer, allocated on the FIRST bake uploaded into this entry. Entries are
         * allocated before they are known to carry geometry (an empty bake still marks a pose as
         * done), and a GL buffer cannot be allocated outside the render thread — so it is created
         * lazily, which also keeps the ring's policy testable without a GL context.
         */
        private VertexBuffer vbo;

        /** The bake this buffer holds; {@code valid} false means nothing usable. */
        public long key;
        public boolean valid;

        /** Which groups the bake tessellated — the VAO walk must skip exactly these. */
        public Set<ModelGroup> cpuGroups;

        /** Whether the bake emitted any geometry at all (an empty upload can't be drawn). */
        public boolean hasGeometry;

        /** The frame epoch the translucent queue last borrowed this buffer in. */
        public long lentEpoch = -1L;

        /** The GPU buffer, allocated on first use. Never null after this call. */
        public VertexBuffer buffer()
        {
            if (this.vbo == null)
            {
                this.vbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }

            return this.vbo;
        }

        /** Free the GPU buffer if one was ever allocated. */
        public void close()
        {
            if (this.vbo != null)
            {
                this.vbo.close();
                this.vbo = null;
            }
        }
    }

    /** The entry holding {@code key}, or null. A valid entry with a different key is never returned. */
    public Entry find(long key)
    {
        for (Entry entry : this.entries)
        {
            if (entry.valid && entry.key == key)
            {
                return entry;
            }
        }

        return null;
    }

    /**
     * An entry to bake into: one not lent to the queue this frame, or a fresh one under the cap.
     * Null past the cap — the caller then bakes into a throwaway buffer the old way.
     */
    public Entry acquire(long epoch)
    {
        for (Entry entry : this.entries)
        {
            if (entry.lentEpoch != epoch)
            {
                return entry;
            }
        }

        if (this.entries.size() < this.maxEntries)
        {
            Entry entry = new Entry();

            this.entries.add(entry);

            return entry;
        }

        return null;
    }

    /** How many entries the ring holds — not how many are valid. */
    public int size()
    {
        return this.entries.size();
    }

    /**
     * Forget every bake (weld config changed) — buffers stay allocated for reuse, since the
     * translucent queue may still hold one until the end of the frame.
     */
    public void invalidate()
    {
        for (Entry entry : this.entries)
        {
            entry.valid = false;
            entry.cpuGroups = null;
        }
    }

    /** Free every buffer and drop the ring. Only safe when no queue command still references one. */
    public void delete()
    {
        for (Entry entry : this.entries)
        {
            entry.close();
        }

        this.entries.clear();
    }
}
