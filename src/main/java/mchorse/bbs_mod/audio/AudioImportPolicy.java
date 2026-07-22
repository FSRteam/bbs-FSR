package mchorse.bbs_mod.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit channel policy for imported audio. SOURCE never inserts an -ac option. */
public enum AudioImportPolicy
{
    SOURCE(null),
    MONO(ChannelLayout.MONO),
    STEREO(ChannelLayout.STEREO);

    private final ChannelLayout targetLayout;

    AudioImportPolicy(ChannelLayout targetLayout)
    {
        this.targetLayout = targetLayout;
    }

    public ChannelLayout targetLayout()
    {
        return this.targetLayout;
    }

    public List<String> buildFfmpegArguments(String input, String output)
    {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        List<String> arguments = new ArrayList<>();

        arguments.add("-y");
        arguments.add("-i");
        arguments.add(input);
        arguments.add("-map");
        arguments.add("0:a:0");
        arguments.add("-map_metadata");
        arguments.add("0");
        arguments.add("-vn");
        arguments.add("-c:a");
        arguments.add("pcm_s16le");

        if (this.targetLayout != null)
        {
            arguments.add("-ac");
            arguments.add(Integer.toString(this.targetLayout.channels()));
        }

        arguments.add(output);

        return List.copyOf(arguments);
    }
}
