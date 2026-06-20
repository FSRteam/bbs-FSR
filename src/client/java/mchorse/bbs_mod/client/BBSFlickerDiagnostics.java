package mchorse.bbs_mod.client;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class BBSFlickerDiagnostics
{
    public static boolean ENABLED = Boolean.parseBoolean(System.getProperty("bbs.flickerDiagnostics", "false"));

    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-flicker");

    public static void log(String message, Object... args)
    {
        if (ENABLED)
        {
            LOGGER.info("[BBS-FLICKER] " + message, args);
        }
    }

    public static String callerOutside(Class<?>... ignoredClasses)
    {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();

        for (StackTraceElement element : stack)
        {
            String className = element.getClassName();

            if (className.equals(Thread.class.getName()) || className.equals(BBSFlickerDiagnostics.class.getName()))
            {
                continue;
            }

            boolean ignored = false;

            for (Class<?> ignoredClass : ignoredClasses)
            {
                if (className.equals(ignoredClass.getName()))
                {
                    ignored = true;

                    break;
                }
            }

            if (!ignored)
            {
                int index = className.lastIndexOf('.');
                String simpleName = index < 0 ? className : className.substring(index + 1);

                return simpleName + "#" + element.getMethodName() + ":" + element.getLineNumber();
            }
        }

        return "unknown";
    }

    public static String film(Film film)
    {
        return film == null ? "null" : film.getId() + "@" + System.identityHashCode(film);
    }

    public static String replay(Replay replay)
    {
        if (replay == null)
        {
            return "null";
        }

        return replay.getId()
            + "{name=" + replay.getName()
            + ",category=" + replay.category.get()
            + ",enabled=" + replay.enabled.get()
            + "}@" + System.identityHashCode(replay);
    }

    public static String replays(Collection<Replay> replays)
    {
        if (replays == null)
        {
            return "null";
        }

        StringBuilder builder = new StringBuilder("[");
        boolean first = true;

        for (Replay replay : replays)
        {
            if (!first)
            {
                builder.append(", ");
            }

            builder.append(replay(replay));
            first = false;
        }

        return builder.append(']').toString();
    }
}
