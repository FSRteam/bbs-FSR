package mchorse.bbs_mod.test;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Captures a declared set of negative-path errors without muting unexpected diagnostics. */
public final class ExpectedErrorLogCapture extends AbstractAppender implements AutoCloseable
{
    private final String label;
    private final int expectedCount;
    private final Predicate<LogEvent> expected;
    private final List<LoggerState> states;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    private ExpectedErrorLogCapture(String label, int expectedCount,
                                    Predicate<LogEvent> expected, List<Logger> loggers)
    {
        super("expected-errors-" + label, null, PatternLayout.createDefaultLayout(),
            false, Property.EMPTY_ARRAY);
        this.label = label;
        this.expectedCount = expectedCount;
        this.expected = expected;
        this.states = loggers.stream().map(LoggerState::new).toList();
        this.start();

        for (LoggerState state : this.states)
        {
            for (Appender appender : state.previousAppenders().values())
            {
                state.logger().removeAppender(appender);
            }

            state.logger().setAdditive(false);
            state.logger().addAppender(this);
            state.logger().setLevel(Level.ALL);
        }
    }

    public static ExpectedErrorLogCapture install(String label, int expectedCount,
                                                  Predicate<LogEvent> expected,
                                                  String... loggerNames)
    {
        List<Logger> loggers = Arrays.stream(loggerNames)
            .map((name) -> (Logger) LogManager.getLogger(name))
            .toList();
        return new ExpectedErrorLogCapture(label, expectedCount, expected, loggers);
    }

    @Override
    public void append(LogEvent event)
    {
        this.events.add(event.toImmutable());
    }

    public void assertExpectedErrors()
    {
        List<LogEvent> errors = this.events.stream()
            .filter((event) -> event.getLevel().isMoreSpecificThan(Level.ERROR))
            .toList();

        if (errors.size() != this.expectedCount)
        {
            throw new AssertionError("Expected " + this.expectedCount + " captured "
                + this.label + " errors, got " + errors.size() + ": " + describe(errors));
        }

        List<LogEvent> unexpected = errors.stream().filter(this.expected.negate()).toList();
        if (!unexpected.isEmpty())
        {
            throw new AssertionError("Unexpected " + this.label + " errors: "
                + describe(unexpected));
        }
    }

    private static String describe(List<LogEvent> events)
    {
        return events.stream()
            .map((event) -> event.getLoggerName() + ": "
                + event.getMessage().getFormattedMessage() + " -> "
                + (event.getThrown() == null ? "no cause" : event.getThrown().getMessage()))
            .toList().toString();
    }

    @Override
    public void close()
    {
        for (LoggerState state : this.states)
        {
            state.logger().removeAppender(this);
            for (Appender appender : state.previousAppenders().values())
            {
                state.logger().addAppender(appender);
            }
            state.logger().setAdditive(state.previousAdditive());
            state.logger().setLevel(state.previousLevel());
        }

        this.stop();
    }

    private record LoggerState(Logger logger, Level previousLevel,
                               boolean previousAdditive,
                               Map<String, Appender> previousAppenders)
    {
        private LoggerState(Logger logger)
        {
            this(logger, logger.getLevel(), logger.isAdditive(),
                Map.copyOf(logger.getAppenders()));
        }
    }
}
