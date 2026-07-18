package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiInputResult
{
    private final BBSUiInputStatus status;
    private final String message;

    public BBSUiInputResult(BBSUiInputStatus status, String message)
    {
        this.status = Objects.requireNonNull(status, "status");
        this.message = message == null ? "" : message;
    }

    public BBSUiInputStatus status()
    {
        return this.status;
    }

    public String message()
    {
        return this.message;
    }

    public boolean applied()
    {
        return this.status == BBSUiInputStatus.APPLIED;
    }
}
