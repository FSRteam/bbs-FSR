package mchorse.bbs_mod.entity;

/**
 * Pure state transition for authorized damage to a private playback actor.
 * World side effects deliberately stay outside this reducer; its caller may
 * publish only audience-tracked visual state.
 */
public final class PlaybackActorDamageProjection
{
    private PlaybackActorDamageProjection()
    {}

    public static Transition apply(float health, float amount)
    {
        if (!Float.isFinite(health)
            || health <= 0F
            || !Float.isFinite(amount)
            || amount <= 0F)
        {
            return new Transition(false, health, false);
        }

        float remaining = Math.max(0F, health - amount);

        return new Transition(true, remaining, remaining <= 0F);
    }

    public record Transition(boolean applied, float health, boolean dead)
    {}
}
