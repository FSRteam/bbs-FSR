package mchorse.bbs_mod.api.client.ui;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BBSUiInputBatch
{
    private final long sessionId;
    private final long sequence;
    private final BBSUiRemoteInputState state;
    private final List<BBSUiInputEvent> events;

    public BBSUiInputBatch(long sessionId, long sequence, BBSUiRemoteInputState state, List<BBSUiInputEvent> events)
    {
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.state = Objects.requireNonNull(state, "state");
        this.events = Collections.unmodifiableList(List.copyOf(Objects.requireNonNull(events, "events")));
    }

    public long sessionId()
    {
        return this.sessionId;
    }

    public long sequence()
    {
        return this.sequence;
    }

    public BBSUiRemoteInputState state()
    {
        return this.state;
    }

    public List<BBSUiInputEvent> events()
    {
        return this.events;
    }
}
