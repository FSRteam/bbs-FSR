package mchorse.bbs_mod.actions;

/**
 * Fail-closed numeric boundary for film-owned mutations of a real player's
 * health, hunger, and experience state.
 */
public final class FilmPlayerSettingsPolicy
{
    public static final int MAX_XP_LEVEL = 1_000_000;
    public static final float MAX_HUNGER = 20F;

    public static boolean isAllowed(float hp, float maxHp, float hunger, int xpLevel, float xpProgress)
    {
        return Float.isFinite(hp)
            && Float.isFinite(maxHp)
            && Float.isFinite(hunger)
            && Float.isFinite(xpProgress)
            && maxHp > 0F
            && hp > 0F
            && hp <= maxHp
            && hunger >= 0F
            && hunger <= MAX_HUNGER
            && xpLevel >= 0
            && xpLevel <= MAX_XP_LEVEL
            && xpProgress >= 0F
            && xpProgress <= 1F;
    }

    private FilmPlayerSettingsPolicy()
    {}
}
