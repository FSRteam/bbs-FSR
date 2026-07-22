package mchorse.bbs_mod.ui.film.audio;

/** Terminal and in-flight states owned by one microphone capture session. */
public enum CaptureState
{
    ARMED,
    OPENING,
    RECORDING,
    STOPPING,
    SUCCEEDED,
    CANCELLED,
    FAILED,
    /** Capture is finalized but cancellation still wins until client commit. */
    READY
}
