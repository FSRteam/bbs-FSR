package mchorse.bbs_mod.utils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Pure-Java relative time description for film metadata timestamps.
 *
 * <p>This lives on the common (main) side and is deliberately l10n-free: the
 * semantic result ({@link Result}) is meant to be rendered by client UI code
 * through its own localization pipeline. {@link #formatFallback(Result, Locale)}
 * exists only for non-UI diagnostics that cannot go through l10n.
 */
public class RelativeTime
{
    private static final long MINUTE_SECONDS = 60L;
    private static final long HOUR_SECONDS = 60L * MINUTE_SECONDS;
    private static final long DAY_SECONDS = 24L * HOUR_SECONDS;
    private static final long WEEK_SECONDS = 7L * DAY_SECONDS;

    /**
     * Coarse bucket describing how far away a timestamp is, plus the measured
     * amount for bucketed codes ({@link Code#MINUTES}, {@link Code#HOURS},
     * {@link Code#DAYS}) and the original instant for {@link Code#ABSOLUTE}.
     */
    public static final class Result
    {
        public final Code code;
        /** Measured unit count; 0 unless the code is MINUTES, HOURS or DAYS. */
        public final long amount;
        /** The evaluated instant; never null. */
        public final Instant instant;

        private Result(Code code, long amount, Instant instant)
        {
            this.code = code;
            this.amount = amount;
            this.instant = instant;
        }

        @Override
        public String toString()
        {
            return "RelativeTime.Result[" + this.code + " amount=" + this.amount + " instant=" + this.instant + "]";
        }
    }

    public enum Code
    {
        NOW, MINUTES, HOURS, YESTERDAY, DAYS, ABSOLUTE
    }

    private RelativeTime()
    {}

    /**
     * Classify a timestamp against {@code now} using the system default zone.
     *
     * @param t     the timestamp to describe
     * @param now   the reference "now"
     * @return the classification, or {@code null} when {@code t} is null
     */
    public static Result describe(Instant t, Instant now)
    {
        if (t == null)
        {
            return null;
        }

        long seconds = Duration.between(t, now).getSeconds();

        if (seconds < MINUTE_SECONDS)
        {
            return new Result(Code.NOW, 0L, t);
        }

        if (seconds < HOUR_SECONDS)
        {
            return new Result(Code.MINUTES, seconds / MINUTE_SECONDS, t);
        }

        if (seconds < DAY_SECONDS)
        {
            return new Result(Code.HOURS, seconds / HOUR_SECONDS, t);
        }

        if (isYesterday(t, now))
        {
            return new Result(Code.YESTERDAY, 0L, t);
        }

        if (seconds < WEEK_SECONDS)
        {
            return new Result(Code.DAYS, seconds / DAY_SECONDS, t);
        }

        return new Result(Code.ABSOLUTE, 0L, t);
    }

    /** Convenience overload using the current clock. */
    public static Result describe(Instant t)
    {
        return describe(t, Instant.now());
    }

    private static boolean isYesterday(Instant t, Instant now)
    {
        ZonedDateTime zoned = t.atZone(ZoneId.systemDefault());
        ZonedDateTime reference = now.atZone(ZoneId.systemDefault());

        return reference.toLocalDate().minusDays(1L).equals(zoned.toLocalDate());
    }

    /**
     * English-only rendering of a {@link Result} for non-UI contexts
     * (logs, server-side diagnostics). Client UI code must localize
     * {@link Result} itself instead of calling this.
     *
     * @return the formatted string, or {@code null} when {@code r} is null
     */
    public static String formatFallback(Result r, Locale locale)
    {
        if (r == null)
        {
            return null;
        }

        switch (r.code)
        {
            case NOW: return "just now";
            case MINUTES: return r.amount + (r.amount == 1L ? " minute ago" : " minutes ago");
            case HOURS: return r.amount + (r.amount == 1L ? " hour ago" : " hours ago");
            case YESTERDAY: return "yesterday";
            case DAYS: return r.amount + (r.amount == 1L ? " day ago" : " days ago");
            case ABSOLUTE:
            default:
                return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(locale == null ? Locale.ROOT : locale)
                    .format(r.instant.atZone(ZoneId.systemDefault()));
        }
    }
}
