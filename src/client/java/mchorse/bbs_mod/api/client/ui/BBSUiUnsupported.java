package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

/**
 * A bounded aggregate diagnostic. It occupies painter order but draws no
 * pixels; consumers expose its count instead of silently reporting success.
 */
public final class BBSUiUnsupported implements BBSUiDrawCommand
{
    private final BBSUiUnsupportedReason reason;
    private final int count;

    public BBSUiUnsupported(BBSUiUnsupportedReason reason, int count)
    {
        this.reason = Objects.requireNonNull(reason, "reason");

        if (count <= 0)
        {
            throw new IllegalArgumentException("count must be positive");
        }

        this.count = count;
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.UNSUPPORTED;
    }

    public BBSUiUnsupportedReason reason()
    {
        return this.reason;
    }

    public int count()
    {
        return this.count;
    }
}
