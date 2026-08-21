package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.film.Film;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Behavioral regressions for relative-time classification and ISO timestamp parsing. */
public final class RelativeTimeTest
{
    private RelativeTimeTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("RelativeTimeTest passed");
    }

    public static void runAll()
    {
        Instant now = Instant.now();

        check(RelativeTime.describe(null, now) == null, "a null timestamp must classify as null");

        checkDescribe(now, now, RelativeTime.Code.NOW, 0L, "the present");
        checkDescribe(now.minusSeconds(59L), now, RelativeTime.Code.NOW, 0L, "just now");

        checkDescribe(now.minusSeconds(60L), now, RelativeTime.Code.MINUTES, 1L, "one minute ago");
        checkDescribe(now.minusSeconds(5L * 60L + 30L), now, RelativeTime.Code.MINUTES, 5L, "five and a half minutes ago");
        checkDescribe(now.minusSeconds(59L * 60L + 59L), now, RelativeTime.Code.MINUTES, 59L, "just under an hour ago");

        checkDescribe(now.minusSeconds(60L * 60L), now, RelativeTime.Code.HOURS, 1L, "one hour ago");
        checkDescribe(now.minusSeconds(5L * 60L * 60L), now, RelativeTime.Code.HOURS, 5L, "five hours ago");
        checkDescribe(now.minusSeconds(23L * 60L * 60L + 59L * 60L), now, RelativeTime.Code.HOURS, 23L, "just under a day ago");

        checkDescribe(now.minusSeconds(7L * 24L * 60L * 60L - 1L), now, RelativeTime.Code.DAYS, 6L, "just under a week ago");
        checkDescribe(now.minusSeconds(3L * 24L * 60L * 60L), now, RelativeTime.Code.DAYS, 3L, "three days ago");

        checkDescribe(now.minusSeconds(7L * 24L * 60L * 60L), now, RelativeTime.Code.ABSOLUTE, 0L, "exactly a week ago");
        checkDescribe(now.minusSeconds(90L * 24L * 60L * 60L), now, RelativeTime.Code.ABSOLUTE, 0L, "ninety days ago");

        testYesterdayBoundary(now);
        testFallbackFormatting(now);
        testParseTimestamp();
    }

    private static void testYesterdayBoundary(Instant now)
    {
        /* Yesterday is a calendar-day comparison in the system zone, not a bare
         * 24h delta: anchor the fixtures at local midnights so the classification
         * is deterministic regardless of the local time of day. */
        ZonedDateTime reference = now.atZone(ZoneId.systemDefault());
        Instant yesterdayStart = reference.toLocalDate().minusDays(1L).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant twoDaysAgoStart = reference.toLocalDate().minusDays(2L).atStartOfDay(ZoneId.systemDefault()).toInstant();

        checkDescribe(yesterdayStart, now, RelativeTime.Code.YESTERDAY, 0L, "yesterday midnight");
        checkNotCode(twoDaysAgoStart, now, RelativeTime.Code.YESTERDAY, "two days ago midnight");
    }

    private static void testFallbackFormatting(Instant now)
    {
        check(RelativeTime.formatFallback(null, Locale.ENGLISH) == null, "a null result must format as null");

        check("just now".equals(RelativeTime.formatFallback(RelativeTime.describe(now, now), Locale.ENGLISH)),
            "the NOW fallback drifted");
        check("5 minutes ago".equals(RelativeTime.formatFallback(RelativeTime.describe(now.minusSeconds(5L * 60L), now), Locale.ENGLISH)),
            "the MINUTES fallback drifted");
        check("1 minute ago".equals(RelativeTime.formatFallback(RelativeTime.describe(now.minusSeconds(60L), now), Locale.ENGLISH)),
            "the singular MINUTES fallback drifted");
        check("2 hours ago".equals(RelativeTime.formatFallback(RelativeTime.describe(now.minusSeconds(2L * 60L * 60L), now), Locale.ENGLISH)),
            "the HOURS fallback drifted");
        check("yesterday".equals(RelativeTime.formatFallback(RelativeTime.describe(now.minusSeconds(36L * 60L * 60L), now), Locale.ENGLISH))
                || "1 day ago".equals(RelativeTime.formatFallback(RelativeTime.describe(now.minusSeconds(36L * 60L * 60L), now), Locale.ENGLISH)),
            "the 36h fallback drifted (expected yesterday or 1 day ago)");
        check("3 days ago".equals(RelativeTime.formatFallback(RelativeTime.describe(now.minusSeconds(3L * 24L * 60L * 60L), now), Locale.ENGLISH)),
            "the DAYS fallback drifted");

        Instant weekAgo = now.minusSeconds(14L * 24L * 60L * 60L);
        String absolute = RelativeTime.formatFallback(RelativeTime.describe(weekAgo, now), Locale.ENGLISH);

        check(absolute != null && !absolute.isBlank() && !absolute.equals("just now"),
            "the ABSOLUTE fallback must render a localized date-time, got: " + absolute);
    }

    private static void testParseTimestamp()
    {
        check(Film.parseTimestamp(null) == null, "a null ISO timestamp must parse as null");
        check(Film.parseTimestamp("") == null, "an empty ISO timestamp must parse as null");
        check(Film.parseTimestamp("not-a-timestamp") == null, "a malformed ISO timestamp must parse as null");
        check(Film.parseTimestamp("2026-08-19T10:15:30Z") != null, "a valid ISO timestamp failed to parse");
        check(Instant.parse("2026-08-19T10:15:30Z").equals(Film.parseTimestamp("2026-08-19T10:15:30Z")),
            "a valid ISO timestamp parsed to the wrong instant");
    }

    private static void checkDescribe(Instant t, Instant now, RelativeTime.Code code, long amount, String label)
    {
        RelativeTime.Result result = RelativeTime.describe(t, now);

        check(result != null, label + " classified as null");
        check(result != null && result.code == code, label + " classified as " + (result == null ? "null" : result.code) + ", expected " + code);
        check(result != null && result.amount == amount, label + " measured " + (result == null ? "?" : result.amount) + ", expected " + amount);
        check(result != null && result.instant.equals(t), label + " lost its original instant");
    }

    private static void checkNotCode(Instant t, Instant now, RelativeTime.Code code, String label)
    {
        RelativeTime.Result result = RelativeTime.describe(t, now);

        check(result == null || result.code != code, label + " was classified as " + code);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
