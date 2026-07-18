package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

/** Immutable result of requesting the fixed native BBS Dashboard target. */
public record BBSUiOpenResult(BBSUiOpenStatus status, String message)
{
    public BBSUiOpenResult
    {
        Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }

    public boolean opened()
    {
        return this.status == BBSUiOpenStatus.OPENED || this.status == BBSUiOpenStatus.ALREADY_OPEN;
    }
}
