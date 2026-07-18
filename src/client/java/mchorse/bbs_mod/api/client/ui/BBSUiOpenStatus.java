package mchorse.bbs_mod.api.client.ui;

/** Stable outcomes for the fixed native BBS Dashboard open request. */
public enum BBSUiOpenStatus
{
    OPENED,
    ALREADY_OPEN,
    NO_WORLD,
    BUSY,
    STALE,
    REJECTED,
    FAILED
}
