package mchorse.bbs_mod.audio;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Channel layouts currently understood by import, mix, and export code. */
public enum ChannelLayout
{
    MONO("mono", 1, true),
    STEREO("stereo", 2, true),
    SURROUND_5_1("5.1", 6, false);

    private final String id;
    private final int channels;
    private final boolean supported;

    ChannelLayout(String id, int channels, boolean supported)
    {
        this.id = id;
        this.channels = channels;
        this.supported = supported;
    }

    public String id()
    {
        return this.id;
    }

    public int channels()
    {
        return this.channels;
    }

    public boolean supported()
    {
        return this.supported;
    }

    public static ChannelLayout fromChannelCount(int channels)
    {
        for (ChannelLayout layout : values())
        {
            if (layout.channels == channels)
            {
                if (!layout.supported)
                {
                    throw new IllegalArgumentException("Unsupported channel layout: " + layout.id);
                }

                return layout;
            }
        }

        throw new IllegalArgumentException("Unsupported channel count: " + channels);
    }

    public static ChannelLayout fromId(String id)
    {
        if (id == null)
        {
            return null;
        }

        String normalized = id.trim().toLowerCase(Locale.ROOT);

        for (ChannelLayout layout : values())
        {
            if (layout.id.equals(normalized))
            {
                return layout;
            }
        }

        return null;
    }

    /** Resolve persisted export settings without exposing unsupported layouts. */
    public static ChannelLayout normalizeExport(String id)
    {
        return normalizeExport(id, (message) -> {});
    }

    /** Resolve a persisted export setting and report one bounded migration diagnostic. */
    public static ChannelLayout normalizeExport(String id, Consumer<String> diagnostic)
    {
        Objects.requireNonNull(diagnostic, "diagnostic");
        ChannelLayout layout = fromId(id);

        if (layout == STEREO)
        {
            return STEREO;
        }

        if (layout != MONO)
        {
            String value = id == null || id.isBlank() ? "<missing>" : id;

            diagnostic.accept("Unsupported audio channel layout '" + value + "'; using mono");
        }

        return MONO;
    }
}
