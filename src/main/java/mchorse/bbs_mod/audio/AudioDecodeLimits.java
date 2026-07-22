package mchorse.bbs_mod.audio;

/** Allocation limits used by container readers and test fixtures. */
public record AudioDecodeLimits(long maxContainerBytes, long maxChunkBytes,
                                long maxDecodedBytes, long maxFrames)
{
    public static final AudioDecodeLimits DEFAULT = new AudioDecodeLimits(
        512L * 1024L * 1024L,
        256L * 1024L * 1024L,
        256L * 1024L * 1024L,
        100_000_000L
    );

    public AudioDecodeLimits
    {
        if (maxContainerBytes <= 0 || maxChunkBytes <= 0 || maxDecodedBytes <= 0 || maxFrames <= 0)
        {
            throw new IllegalArgumentException("Audio decode limits must be positive");
        }

        if (maxChunkBytes > maxContainerBytes)
        {
            throw new IllegalArgumentException("Chunk limit cannot exceed container limit");
        }
    }
}
