package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.resources.Link;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable source description for one world export generation.
 *
 * <p>The snapshot deliberately contains value records rather than Film or
 * AudioClip instances.  Consumers can therefore retain it after the editor
 * has been closed without observing later edits on the client thread.</p>
 */
public record WorldVideoExportSnapshot(
    UUID sessionId,
    long generation,
    Kind kind,
    String sourceId,
    double sourceStart,
    double sourceEnd,
    boolean openEnd,
    ChannelLayout layout,
    String sequenceName,
    List<AudioClipSnapshot> audioClips,
    int width,
    int height,
    double captureFrameRate,
    double outputFrameRate,
    int motionBlurPasses)
{
    public enum Kind
    {
        LIVE_WORLD_F4,
        FILM_F6
    }

    public WorldVideoExportSnapshot
    {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(layout, "layout");

        sourceId = sourceId == null ? "" : sourceId;
        sequenceName = sequenceName == null ? "" : sequenceName;
        audioClips = audioClips == null ? List.of() : List.copyOf(audioClips);

        if (!Double.isFinite(sourceStart) || sourceStart < 0D
            || (!openEnd && (!Double.isFinite(sourceEnd) || sourceEnd <= sourceStart)))
        {
            throw new IllegalArgumentException("World export snapshot range is invalid");
        }

        if (width <= 0 || height <= 0 || !Double.isFinite(captureFrameRate)
            || captureFrameRate <= 0D || !Double.isFinite(outputFrameRate)
            || outputFrameRate <= 0D || motionBlurPasses < 0)
        {
            throw new IllegalArgumentException("World export snapshot capture settings are invalid");
        }

        if (openEnd && !audioClips.isEmpty())
        {
            throw new IllegalArgumentException("Open-ended live-world snapshots cannot carry film clips");
        }
    }

    public boolean isFilm()
    {
        return this.kind == Kind.FILM_F6;
    }

    /** Value-only copy of one enabled AudioClip at capture time. */
    public record AudioClipSnapshot(
        int index,
        String identity,
        Link audio,
        long tick,
        long duration,
        long sourceOffset,
        float volume)
    {
        public AudioClipSnapshot
        {
            identity = identity == null ? "" : identity;
            audio = copyLink(audio);

            if (index < 0 || tick < 0L || duration <= 0L || !Float.isFinite(volume))
            {
                throw new IllegalArgumentException("Audio clip snapshot value is invalid");
            }
        }

        private static Link copyLink(Link link)
        {
            return link == null ? null : new Link(link.source, link.path);
        }
    }
}
