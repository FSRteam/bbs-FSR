package mchorse.bbs_mod.entity;

/** Executable state regressions for private actor visual-only damage. */
public final class PlaybackActorDamageProjectionTest
{
    private PlaybackActorDamageProjectionTest()
    {}

    public static void main(String[] args)
    {
        runAll();
        System.out.println("PlaybackActorDamageProjectionTest passed");
    }

    public static void runAll()
    {
        PlaybackActorDamageProjection.Transition hurt = PlaybackActorDamageProjection.apply(20F, 3.5F);

        check(hurt.applied() && hurt.health() == 16.5F && !hurt.dead(),
            "private actor non-lethal damage no longer preserves projected health");

        PlaybackActorDamageProjection.Transition killed = PlaybackActorDamageProjection.apply(2F, 10F);

        check(killed.applied() && killed.health() == 0F && killed.dead(),
            "private actor lethal damage no longer clamps into its projected death state");

        float[] invalidAmounts = {0F, -1F, Float.NaN, Float.POSITIVE_INFINITY};

        for (float amount : invalidAmounts)
        {
            PlaybackActorDamageProjection.Transition rejected = PlaybackActorDamageProjection.apply(20F, amount);

            check(!rejected.applied() && rejected.health() == 20F && !rejected.dead(),
                "private actor projection accepted invalid damage " + amount);
        }

        float[] invalidHealth = {0F, -1F, Float.NaN, Float.POSITIVE_INFINITY};

        for (float health : invalidHealth)
        {
            check(!PlaybackActorDamageProjection.apply(health, 1F).applied(),
                "private actor projection revived or mutated invalid/dead health " + health);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
