package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.audio.ChannelLayout;


/**
 * One delivery contract shared by direct recording and the post-recording
 * mux.  PCM/WAV remains the master format; this profile is only for the final
 * lossy stream.
 */
public final class VideoExportAudioProfile
{
    public static final int SAMPLE_RATE = 48000;
    public static final int BITRATE = 192000;
    public static final String BITRATE_ARGUMENT = "192k";
    public static final String CODEC = "aac";
    public static final String PROFILE = "aac_low";

    public static final String DEFAULT_DIRECT_ARGUMENTS =
        "-f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - "
            + "-i %AUDIO_TRACK% -map 0:v:0 -map 1:a:0 -vf %FILTERS% "
            + "-c:v libx264 -preset ultrafast -tune zerolatency -qp 18 "
            + "-pix_fmt yuv420p -c:a aac -profile:a aac_low "
            + "-ar %AUDIO_SAMPLE_RATE% -ac %AUDIO_CHANNELS% "
            + "-channel_layout %AUDIO_LAYOUT% -b:a 192k %OUTPUT%";

    public static final String DEFAULT_VIDEO_ARGUMENTS =
        "-nostdin -n -f rawvideo -pix_fmt bgr24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - "
            + "-vf %FILTERS% -c:v libx264 -preset ultrafast -tune zerolatency "
            + "-qp 18 -pix_fmt yuv420p %OUTPUT%";

    public static final String DEFAULT_MUX_ARGUMENTS =
        "-nostdin -n -i %VIDEO% -i %AUDIO_TRACK% -map 0:v:0 -map 1:a:0 "
            + "-c:v copy -c:a aac -profile:a aac_low "
            + "-ar %AUDIO_SAMPLE_RATE% -ac %AUDIO_CHANNELS% "
            + "-channel_layout %AUDIO_LAYOUT% -b:a 192k %OUTPUT%";

    private VideoExportAudioProfile()
    {}

    public static int channels(ChannelLayout layout)
    {
        if (layout == null || !layout.supported()
            || (layout != ChannelLayout.MONO && layout != ChannelLayout.STEREO))
        {
            throw new IllegalArgumentException("Unsupported video export channel layout: " + layout);
        }

        return layout.channels();
    }

    /**
     * Validate the placeholders and the no-overwrite contract before a child
     * process is launched.  User templates may use the legacy %NAME% output
     * placeholder, but the caller must then supply a known extension.
     */
    public static void validateTemplate(String template, boolean audio)
    {
        validateTemplate(template, audio, false);
    }

    /** Mux templates additionally have to copy, rather than re-encode, video. */
    public static void validateTemplate(String template, boolean audio, boolean requireVideoCopy)
    {
        if (template == null || template.isBlank())
        {
            throw new IllegalArgumentException("FFmpeg template is empty");
        }

        java.util.List<String> arguments = VideoExportUtils.tokenizeArguments(template);
        validatePlaceholders(template);
        validatePlaceholderBindings(arguments, audio, requireVideoCopy);

        long outputCount = arguments.stream().filter("%OUTPUT%"::equals).count()
            + arguments.stream().filter("%NAME%.mp4"::equals).count();

        if (outputCount != 1L)
        {
            throw new IllegalArgumentException("FFmpeg template must contain one standalone %OUTPUT% or legacy %NAME%.mp4");
        }

        int outputIndex = -1;
        for (int i = 0; i < arguments.size(); i++)
        {
            if ("%OUTPUT%".equals(arguments.get(i)) || "%NAME%.mp4".equals(arguments.get(i)))
            {
                outputIndex = i;
                break;
            }
        }

        if (outputIndex != arguments.size() - 1)
        {
            throw new IllegalArgumentException("FFmpeg output binding must be the final argument");
        }

        validateSingleOutputOperand(arguments);

        if (arguments.stream().anyMatch(value -> value.contains("%OUTPUT%") && !value.equals("%OUTPUT%"))
            || arguments.stream().anyMatch(value -> value.contains("%NAME%") && !value.equals("%NAME%.mp4")))
        {
            throw new IllegalArgumentException("FFmpeg output placeholder must name the declared artifact exactly");
        }

        for (String placeholder : new String[]{"%OUTPUT%", "%NAME%", "%VIDEO%", "%AUDIO_TRACK%",
            "%AUDIO_SAMPLE_RATE%", "%AUDIO_CHANNELS%", "%AUDIO_LAYOUT%", "%WIDTH%", "%HEIGHT%",
            "%FPS%", "%FILTERS%"})
        {
            if (countPlaceholder(arguments, placeholder) > 1)
            {
                throw new IllegalArgumentException("FFmpeg placeholder is bound more than once: " + placeholder);
            }
        }

        if (containsToken(template, "-y"))
        {
            throw new IllegalArgumentException("FFmpeg template cannot replace an existing output (-y)");
        }

        if (!audio)
        {
            requireUniqueOption(arguments, "-f", "rawvideo");
            requireUniqueOption(arguments, "-s", "%WIDTH%x%HEIGHT%");
            requireUniqueOption(arguments, "-r", "%FPS%");
            requireUniqueOption(arguments, "-vf", "%FILTERS%");

            if (inputIndex(arguments, "-") != 0)
            {
                throw new IllegalArgumentException("FFmpeg video template must read raw video from stdin");
            }

            requireInputCount(arguments, 1);

            return;
        }

        String[] required = {
            "%AUDIO_TRACK%", "%AUDIO_SAMPLE_RATE%", "%AUDIO_CHANNELS%", "%AUDIO_LAYOUT%"
        };

        for (String placeholder : required)
        {
            if (!template.contains(placeholder))
            {
                throw new IllegalArgumentException("FFmpeg audio template is missing " + placeholder);
            }
        }

        requireInput(arguments, "%AUDIO_TRACK%");
        requireInputCount(arguments, requireVideoCopy ? 2 : 2);
        requireExactMapPair(arguments);
        requireUniqueOption(arguments, "-ar", "%AUDIO_SAMPLE_RATE%");
        requireUniqueOption(arguments, "-ac", "%AUDIO_CHANNELS%");
        requireUniqueOption(arguments, "-channel_layout", "%AUDIO_LAYOUT%");

        if (containsToken(template, "-shortest"))
        {
            throw new IllegalArgumentException("FFmpeg audio template cannot use -shortest to mask a duration mismatch");
        }

        int audioCodecOptions = countOption(arguments, "-c:a") + countOption(arguments, "-codec:a");
        if (audioCodecOptions != 1
            || (!hasOption(arguments, "-c:a", CODEC) && !hasOption(arguments, "-codec:a", CODEC)))
        {
            throw new IllegalArgumentException("FFmpeg audio template must encode AAC exactly once");
        }

        if (countOption(arguments, "-profile:a") != 1 || !hasOption(arguments, "-profile:a", PROFILE)
            || countOption(arguments, "-b:a") != 1 || !hasOption(arguments, "-b:a", BITRATE_ARGUMENT))
        {
            throw new IllegalArgumentException("FFmpeg audio template must use AAC-LC at 192k");
        }

        if (requireVideoCopy && !hasOption(arguments, "-c:v", "copy")
            && !hasOption(arguments, "-codec:v", "copy"))
        {
            throw new IllegalArgumentException("FFmpeg mux template must copy the video stream");
        }

        if (requireVideoCopy
            && countOption(arguments, "-c:v") + countOption(arguments, "-codec:v") != 1)
        {
            throw new IllegalArgumentException("FFmpeg mux template must bind one video codec option");
        }

        if (requireVideoCopy)
        {
            requireInput(arguments, "%VIDEO%");

            if (inputIndex(arguments, "%VIDEO%") != 0 || inputIndex(arguments, "%AUDIO_TRACK%") != 1)
            {
                throw new IllegalArgumentException("FFmpeg mux template inputs must be %VIDEO% then %AUDIO_TRACK%");
            }
        }
        else
        {
            requireUniqueOption(arguments, "-f", "rawvideo");
            requireUniqueOption(arguments, "-s", "%WIDTH%x%HEIGHT%");
            requireUniqueOption(arguments, "-r", "%FPS%");
            requireUniqueOption(arguments, "-vf", "%FILTERS%");

            int audioInput = inputIndex(arguments, "%AUDIO_TRACK%");
            if (inputIndex(arguments, "-") != 0 || audioInput != 1)
            {
                throw new IllegalArgumentException("FFmpeg direct-audio template inputs must be raw video then %AUDIO_TRACK%");
            }
        }
    }

    private static boolean containsToken(String template, String token)
    {
        for (String argument : VideoExportUtils.tokenizeArguments(template))
        {
            if (token.equalsIgnoreCase(argument))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * FFmpeg treats every unconsumed positional argument as an output.  Walk
     * the option/value structure so the owned placeholder cannot be smuggled
     * in as metadata (or another option value), and so an earlier hard-coded
     * output cannot make FFmpeg write a second, unowned artifact.
     */
    private static void validateSingleOutputOperand(java.util.List<String> arguments)
    {
        java.util.Set<String> flags = java.util.Set.of(
            "-n", "-y", "-nostdin", "-stdin", "-hide_banner", "-stats",
            "-benchmark", "-benchmark_all", "-bitexact", "-copyts",
            "-start_at_zero", "-re", "-an", "-vn", "-sn", "-dn",
            "-shortest", "-noautorotate", "-autorotate");
        int outputs = 0;

        for (int i = 0; i < arguments.size(); i++)
        {
            String argument = arguments.get(i);

            if (argument.startsWith("-") && !"-".equals(argument))
            {
                String option = argument.toLowerCase(java.util.Locale.ROOT);
                if (flags.contains(option) || argument.indexOf('=') >= 0)
                {
                    continue;
                }

                if (i + 1 >= arguments.size())
                {
                    throw new IllegalArgumentException("FFmpeg option is missing its value: " + argument);
                }

                String value = arguments.get(++i);
                if (isOutputPlaceholder(value))
                {
                    throw new IllegalArgumentException(
                        "FFmpeg output placeholder cannot be used as an option value: " + argument);
                }

                continue;
            }

            outputs += 1;
            if (!isOutputPlaceholder(argument))
            {
                throw new IllegalArgumentException(
                    "FFmpeg template declares an unowned positional output: " + argument);
            }
        }

        if (outputs != 1)
        {
            throw new IllegalArgumentException("FFmpeg template must declare exactly one owned output operand");
        }
    }

    private static boolean isOutputPlaceholder(String argument)
    {
        return "%OUTPUT%".equals(argument) || "%NAME%.mp4".equals(argument);
    }

    private static boolean hasOption(java.util.List<String> arguments, String option, String value)
    {
        for (int i = 0; i + 1 < arguments.size(); i++)
        {
            if (option.equalsIgnoreCase(arguments.get(i)) && value.equalsIgnoreCase(arguments.get(i + 1)))
            {
                return true;
            }
        }

        return false;
    }

    private static void requireUniqueOption(java.util.List<String> arguments, String option, String value)
    {
        int occurrences = 0;
        int matches = 0;

        for (int i = 0; i < arguments.size(); i++)
        {
            if (!option.equalsIgnoreCase(arguments.get(i)))
            {
                continue;
            }

            occurrences += 1;
            if (i + 1 < arguments.size() && value.equalsIgnoreCase(arguments.get(i + 1)))
            {
                matches += 1;
            }
        }

        if (occurrences != 1 || matches != 1)
        {
            throw new IllegalArgumentException("FFmpeg option must bind exactly once: " + option + " " + value);
        }
    }

    private static int countOption(java.util.List<String> arguments, String option)
    {
        int count = 0;
        for (String argument : arguments)
        {
            if (option.equalsIgnoreCase(argument)) count += 1;
        }
        return count;
    }

    private static void requireExactMapPair(java.util.List<String> arguments)
    {
        int video = 0;
        int audio = 0;
        int total = 0;

        for (int i = 0; i < arguments.size(); i++)
        {
            if (!"-map".equalsIgnoreCase(arguments.get(i)))
            {
                continue;
            }

            total += 1;
            if (i + 1 >= arguments.size())
            {
                continue;
            }

            if ("0:v:0".equalsIgnoreCase(arguments.get(i + 1))) video += 1;
            if ("1:a:0".equalsIgnoreCase(arguments.get(i + 1))) audio += 1;
        }

        if (total != 2 || video != 1 || audio != 1)
        {
            throw new IllegalArgumentException(
                "FFmpeg audio template must map exactly 0:v:0 and 1:a:0");
        }
    }

    private static void validatePlaceholderBindings(java.util.List<String> arguments,
                                                     boolean audio, boolean requireVideoCopy)
    {
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Set.of(
            "%OUTPUT%", "%NAME%"));

        if (requireVideoCopy)
        {
            allowed.addAll(java.util.Set.of("%VIDEO%", "%AUDIO_TRACK%",
                "%AUDIO_SAMPLE_RATE%", "%AUDIO_CHANNELS%", "%AUDIO_LAYOUT%"));
        }
        else
        {
            allowed.addAll(java.util.Set.of("%WIDTH%", "%HEIGHT%", "%FPS%", "%FILTERS%"));
            if (audio)
            {
                allowed.addAll(java.util.Set.of("%AUDIO_TRACK%", "%AUDIO_SAMPLE_RATE%",
                    "%AUDIO_CHANNELS%", "%AUDIO_LAYOUT%"));
            }
        }

        java.util.Set<String> known = java.util.Set.of(
            "%WIDTH%", "%HEIGHT%", "%FPS%", "%FILTERS%", "%OUTPUT%", "%NAME%",
            "%VIDEO%", "%AUDIO_TRACK%", "%AUDIO_SAMPLE_RATE%", "%AUDIO_CHANNELS%",
            "%AUDIO_LAYOUT%");

        for (String argument : arguments)
        {
            for (String placeholder : known)
            {
                if (!argument.contains(placeholder))
                {
                    continue;
                }

                boolean exact = placeholder.equals(argument)
                    || ("%NAME%".equals(placeholder) && "%NAME%.mp4".equals(argument))
                    || ("%WIDTH%".equals(placeholder) && "%WIDTH%x%HEIGHT%".equals(argument))
                    || ("%HEIGHT%".equals(placeholder) && "%WIDTH%x%HEIGHT%".equals(argument));

                if (!allowed.contains(placeholder) || !exact)
                {
                    throw new IllegalArgumentException("FFmpeg placeholder has an invalid binding: " + argument);
                }
            }
        }
    }

    private static void requireInputCount(java.util.List<String> arguments, int expected)
    {
        int count = 0;
        for (int i = 0; i + 1 < arguments.size(); i++)
        {
            if ("-i".equalsIgnoreCase(arguments.get(i))) count += 1;
        }

        if (count != expected)
        {
            throw new IllegalArgumentException("FFmpeg template must declare exactly " + expected + " inputs");
        }
    }

    private static int countPlaceholder(java.util.List<String> arguments, String placeholder)
    {
        int count = 0;
        for (String argument : arguments)
        {
            int from = 0;
            while ((from = argument.indexOf(placeholder, from)) >= 0)
            {
                count += 1;
                from += placeholder.length();
            }
        }
        return count;
    }

    private static void requireOption(java.util.List<String> arguments, String option, String value)
    {
        if (!hasOption(arguments, option, value))
        {
            throw new IllegalArgumentException("FFmpeg audio template must contain " + option + " " + value);
        }
    }

    private static void requireInput(java.util.List<String> arguments, String value)
    {
        if (inputIndex(arguments, value) < 0)
        {
            throw new IllegalArgumentException("FFmpeg audio template must contain -i " + value);
        }
    }

    private static int inputIndex(java.util.List<String> arguments, String value)
    {
        int input = 0;

        for (int i = 0; i + 1 < arguments.size(); i++)
        {
            if ("-i".equalsIgnoreCase(arguments.get(i)))
            {
                if (value.equals(arguments.get(i + 1)))
                {
                    return input;
                }

                input += 1;
            }
        }

        return -1;
    }

    private static void validatePlaceholders(String template)
    {
        java.util.Set<String> known = java.util.Set.of(
            "%WIDTH%", "%HEIGHT%", "%FPS%", "%FILTERS%", "%OUTPUT%", "%NAME%",
            "%VIDEO%", "%AUDIO_TRACK%", "%AUDIO_SAMPLE_RATE%", "%AUDIO_CHANNELS%",
            "%AUDIO_LAYOUT%");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("%[A-Za-z0-9_]+%").matcher(template);

        while (matcher.find())
        {
            if (!known.contains(matcher.group()))
            {
                throw new IllegalArgumentException("Unknown FFmpeg placeholder: " + matcher.group());
            }
        }
    }
}
