package mchorse.bbs_mod.api.client.ui;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BBSUiFrame
{
    private final long sessionId;
    private final long sequence;
    private final long capturedAtNanos;
    private final int width;
    private final int height;
    private final BBSUiCursor cursor;
    private final List<BBSUiDrawCommand> commands;
    private final boolean truncated;

    public BBSUiFrame(
        long sessionId,
        long sequence,
        long capturedAtNanos,
        int width,
        int height,
        BBSUiCursor cursor,
        List<BBSUiDrawCommand> commands,
        boolean truncated
    )
    {
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.capturedAtNanos = capturedAtNanos;
        this.width = width;
        this.height = height;
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.commands = Collections.unmodifiableList(List.copyOf(Objects.requireNonNull(commands, "commands")));
        this.truncated = truncated;
    }

    public long sessionId()
    {
        return this.sessionId;
    }

    public long sequence()
    {
        return this.sequence;
    }

    public long capturedAtNanos()
    {
        return this.capturedAtNanos;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }

    public BBSUiCursor cursor()
    {
        return this.cursor;
    }

    public List<BBSUiDrawCommand> commands()
    {
        return this.commands;
    }

    public boolean truncated()
    {
        return this.truncated;
    }
}
