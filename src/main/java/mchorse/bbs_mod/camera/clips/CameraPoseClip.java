package mchorse.bbs_mod.camera.clips;

/**
 * Opt-in contract for extension clips used by off-screen camera evaluation.
 * Implementations may change only the supplied Position and context-local
 * scratch data. They must not advance playback, manage audio, mutate the
 * world, or retain camera state outside the supplied context.
 */
public interface CameraPoseClip
{}
