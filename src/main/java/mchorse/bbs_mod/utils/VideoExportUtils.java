package mchorse.bbs_mod.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure helpers shared by the client-side video export sessions. */
public final class VideoExportUtils
{
    private VideoExportUtils()
    {}

    /**
     * Split a user-authored ffmpeg argument template without losing quoted spaces, then
     * resolve placeholders inside each complete argument. Quotes group arguments and are
     * not passed to {@link ProcessBuilder}; backslashes are preserved except when escaping
     * a quote.
     */
    public static List<String> resolveArguments(String template, Map<String, String> replacements)
    {
        List<String> arguments = tokenizeArguments(template);

        for (int i = 0; i < arguments.size(); i++)
        {
            arguments.set(i, resolveArgument(arguments.get(i), replacements));
        }

        return arguments;
    }

    private static String resolveArgument(String argument, Map<String, String> replacements)
    {
        StringBuilder resolved = new StringBuilder(argument.length());

        for (int i = 0; i < argument.length();)
        {
            boolean replaced = false;

            for (Map.Entry<String, String> entry : replacements.entrySet())
            {
                String placeholder = entry.getKey();

                if (argument.startsWith(placeholder, i))
                {
                    resolved.append(entry.getValue());
                    i += placeholder.length();
                    replaced = true;

                    break;
                }
            }

            if (!replaced)
            {
                resolved.append(argument.charAt(i));
                i += 1;
            }
        }

        return resolved.toString();
    }

    static List<String> tokenizeArguments(String input)
    {
        List<String> arguments = new ArrayList<>();
        StringBuilder argument = new StringBuilder();
        char quote = 0;
        boolean started = false;

        for (int i = 0; i < input.length(); i++)
        {
            char character = input.charAt(i);

            if (character == '\\' && i + 1 < input.length())
            {
                char next = input.charAt(i + 1);

                if (next == quote || (quote == 0 && (next == '\"' || next == '\'')))
                {
                    argument.append(next);
                    started = true;
                    i += 1;

                    continue;
                }
            }

            if (character == '\"' || character == '\'')
            {
                if (quote == 0)
                {
                    quote = character;
                    started = true;

                    continue;
                }
                else if (quote == character)
                {
                    quote = 0;

                    continue;
                }
            }

            if (Character.isWhitespace(character) && quote == 0)
            {
                if (started)
                {
                    arguments.add(argument.toString());
                    argument.setLength(0);
                    started = false;
                }

                continue;
            }

            argument.append(character);
            started = true;
        }

        if (quote != 0)
        {
            throw new IllegalArgumentException("Unclosed quote in ffmpeg arguments");
        }

        if (started)
        {
            arguments.add(argument.toString());
        }

        return arguments;
    }

    /** Create a uniquely owned WAV path so cleanup can never target an existing user file. */
    public static File createTemporaryAudioFile(File folder) throws IOException
    {
        Files.createDirectories(folder.toPath());

        return Files.createTempFile(folder.toPath(), "bbs-export-", ".wav").toFile();
    }

    public static void deleteTemporaryFile(File file)
    {
        if (file != null && file.exists() && !file.delete())
        {
            file.deleteOnExit();
        }
    }
}
