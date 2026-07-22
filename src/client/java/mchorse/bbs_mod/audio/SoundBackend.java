package mchorse.bbs_mod.audio;

/** Injectable OpenAL playback seam used to verify source/buffer ownership without hardware. */
public interface SoundBackend
{
    int createBuffer(Wave wave);

    void deleteBuffer(int buffer);

    int createSource();

    void setSourceBuffer(int source, int buffer);

    void setSourceMaxDistance(int source, float distance);

    void setSourceVolume(int source, float volume);

    void setSourcePitch(int source, float pitch);

    void setSourceRelative(int source, boolean relative);

    void setSourceLooping(int source, boolean looping);

    void setSourcePosition(int source, float x, float y, float z);

    void setSourceVelocity(int source, float x, float y, float z);

    void playSource(int source);

    void pauseSource(int source);

    void stopSource(int source);

    boolean isSourcePlaying(int source);

    boolean isSourcePaused(int source);

    boolean isSourceStopped(int source);

    float getSourcePosition(int source);

    void setSourcePositionSeconds(int source, float seconds);

    void deleteSource(int source);
}
