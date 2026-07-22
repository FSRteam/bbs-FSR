package mchorse.bbs_mod.ui.film.audio;

/** Distinct observable failure points in the capture lifecycle. */
public enum CaptureFailure
{
    NO_DEVICE,
    DEVICE_ENUMERATION_FAILED,
    UNSUPPORTED_MODE,
    DEVICE_OPEN_FAILED,
    DEVICE_START_FAILED,
    DEVICE_READ_FAILED,
    DEVICE_STOP_FAILED,
    DEVICE_CLOSE_FAILED,
    STORAGE_FAILED,
    CAPTURE_OVERFLOW,
    DURATION_LIMIT,
    CALLBACK_FAILED,
    COMMIT_FAILED
}
