package mchorse.bbs_mod.plugin.manager;

import java.util.concurrent.atomic.AtomicReference;

/** Host-owned event type used by the end-to-end manager fixture. */
public final class ManagerProbeEvent
{
    private final AtomicReference<String> value;

    public ManagerProbeEvent(AtomicReference<String> value)
    {
        this.value = value;
    }

    public void record(String message)
    {
        this.value.set(message);
    }
}
