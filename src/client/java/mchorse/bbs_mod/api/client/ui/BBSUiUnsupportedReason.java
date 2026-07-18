package mchorse.bbs_mod.api.client.ui;

/** Stable, browser-neutral reasons why a native draw could not be mirrored. */
public enum BBSUiUnsupportedReason
{
    FRAME_COMMAND_LIMIT,
    RAW_TEXTURE,
    CUSTOM_SHADER,
    ASSET_UNAVAILABLE,
    DIRECT_DRAW
}
