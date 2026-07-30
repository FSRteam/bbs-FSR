package mchorse.bbs_mod.forms.forms.sound;

/**
 * Image-source reflection math.
 *
 * <p>Reflecting surfaces are passed in rather than queried here, which keeps
 * this class free of world access and therefore testable on a bare JVM. The
 * client side is responsible for gathering nearby surfaces.</p>
 *
 * <p>A reflection is simply the same clip arriving late and quieter: mirror the
 * source across the surface, and the mirrored point's distance gives both the
 * delay and the attenuation.</p>
 */
public final class SoundReflections
{
    private SoundReflections()
    {}

    /**
     * A reflecting plane, as a point on it plus a unit normal.
     *
     * <p>Mutable and pooled, for the same hot-path reason as
     * {@link SoundVoice}.</p>
     */
    public static class Surface
    {
        public static final int BLOCK = 0;
        public static final int ENTITY = 1;

        public float px;
        public float py;
        public float pz;
        public float nx;
        public float ny;
        public float nz;
        public int type;

        public void set(float px, float py, float pz, float nx, float ny, float nz)
        {
            this.set(px, py, pz, nx, ny, nz, BLOCK);
        }

        public void set(float px, float py, float pz, float nx, float ny, float nz, int type)
        {
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.type = type;
        }

        /** Signed distance from a point to this plane; positive is the front face. */
        public float signedDistance(float x, float y, float z)
        {
            return (x - this.px) * this.nx + (y - this.py) * this.ny + (z - this.pz) * this.nz;
        }
    }

    /**
     * Collect audible reflections, strongest first, into {@code out}.
     *
     * <p>Higher orders are approximated by repeatedly mirroring across the same
     * surface rather than enumerating every surface permutation: true
     * higher-order image sources grow combinatorially, which no hot path can
     * afford. The visualization reports what this actually computes, so the
     * approximation never silently disagrees with what is heard.</p>
     *
     * @param out       pooled voices to fill; never reallocated
     * @param maxVoices upper bound on reflections, also bounded by out.length
     * @return the number of voices written
     */
    public static int collect(
        float sourceX, float sourceY, float sourceZ,
        float listenerX, float listenerY, float listenerZ,
        Surface[] surfaces, int surfaceCount,
        SoundFalloff falloff, float refDistance, float maxDistance, float rolloff, float airAbsorption,
        int maxOrder, float decay, float baseSeconds, float baseGain,
        SoundVoice[] out, int maxVoices)
    {
        return collect(
            sourceX, sourceY, sourceZ, listenerX, listenerY, listenerZ,
            surfaces, surfaceCount, falloff, refDistance, maxDistance, rolloff, airAbsorption,
            true, true, false, false,
            maxOrder, decay, baseSeconds, baseGain, out, maxVoices);
    }

    public static int collect(
        float sourceX, float sourceY, float sourceZ,
        float listenerX, float listenerY, float listenerZ,
        Surface[] surfaces, int surfaceCount,
        SoundFalloff falloff, float refDistance, float maxDistance, float rolloff, float airAbsorption,
        boolean blockReflections, boolean entityReflections,
        boolean passThroughBlocks, boolean passThroughEntities,
        int maxOrder, float decay, float baseSeconds, float baseGain,
        SoundVoice[] out, int maxVoices)
    {
        int limit = Math.min(maxVoices, out.length);

        if (limit <= 0 || maxOrder <= 0 || surfaceCount <= 0 || decay <= 0F || baseGain <= 0F)
        {
            return 0;
        }

        int count = 0;
        float directDistance = distance(sourceX, sourceY, sourceZ, listenerX, listenerY, listenerZ);

        for (int i = 0; i < surfaceCount && i < surfaces.length; i++)
        {
            Surface surface = surfaces[i];

            if (!canReflect(surface.type, blockReflections, entityReflections,
                passThroughBlocks, passThroughEntities))
            {
                continue;
            }

            float offset = surface.signedDistance(sourceX, sourceY, sourceZ);

            /* A surface the source sits on or behind reflects nothing back */
            if (offset <= 0F)
            {
                continue;
            }

            for (int order = 1; order <= maxOrder; order++)
            {
                /* Mirroring n times across the same plane moves the image
                 * 2 * n * offset along the inverse normal */
                float shift = 2F * order * offset;
                float imageX = sourceX - surface.nx * shift;
                float imageY = sourceY - surface.ny * shift;
                float imageZ = sourceZ - surface.nz * shift;

                float dx = listenerX - imageX;
                float dy = listenerY - imageY;
                float dz = listenerZ - imageZ;
                float pathLength = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (pathLength >= maxDistance)
                {
                    /* Longer bounces off this surface are only ever farther */
                    break;
                }

                float gain = SoundAcoustics.reflectionGain(falloff, pathLength, refDistance, maxDistance, rolloff, order, decay)
                    * SoundAcoustics.airAbsorptionGain(pathLength, airAbsorption)
                    * baseGain;

                if (gain <= 0F)
                {
                    break;
                }

                float seconds = baseSeconds - SoundAcoustics.delay(pathLength - directDistance);

                count = insert(out, count, limit, imageX, imageY, imageZ, seconds, gain, order);
            }
        }

        return count;
    }

    public static boolean canReflect(int type, boolean blockReflections, boolean entityReflections,
        boolean passThroughBlocks, boolean passThroughEntities)
    {
        if (type == Surface.ENTITY)
        {
            return entityReflections && !passThroughEntities;
        }

        return blockReflections && !passThroughBlocks;
    }

    public static boolean blocksDirect(int type, boolean passThroughBlocks, boolean passThroughEntities)
    {
        return type == Surface.ENTITY ? !passThroughEntities : !passThroughBlocks;
    }

    /**
     * Insert one reflection keeping {@code out[0..count)} sorted by descending
     * gain, dropping the weakest once the budget is full.
     *
     * <p>An insertion sort rather than sorting at the end: the budget is small,
     * and this needs no scratch allocation.</p>
     */
    private static int insert(SoundVoice[] out, int count, int limit,
        float x, float y, float z, float seconds, float gain, int order)
    {
        if (count >= limit && gain <= out[limit - 1].gain)
        {
            return count;
        }

        int position = count < limit ? count : limit - 1;

        while (position > 0 && out[position - 1].gain < gain)
        {
            SoundVoice previous = out[position - 1];

            out[position].set(previous.x, previous.y, previous.z, previous.seconds, previous.gain, previous.order);
            position--;
        }

        out[position].set(x, y, z, seconds, gain, order);

        return count < limit ? count + 1 : limit;
    }

    public static float distance(float x1, float y1, float z1, float x2, float y2, float z2)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
