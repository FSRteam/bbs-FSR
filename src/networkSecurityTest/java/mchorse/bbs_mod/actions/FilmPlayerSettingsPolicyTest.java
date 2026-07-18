package mchorse.bbs_mod.actions;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.food.FoodData;

public final class FilmPlayerSettingsPolicyTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("FilmPlayerSettingsPolicyTest passed");
    }

    public static void runAll()
    {
        testValidSettingsAndBoundaries();
        testNonFiniteFilmValues();
        testOutOfRangeFilmValues();
        structuredFoodStateRoundTrips();
    }

    private static void testValidSettingsAndBoundaries()
    {
        check(allowed(20F, 20F, 20F, 30, 0.5F), "ordinary player settings were rejected");
        check(allowed(0.5F, 20F, 0F, 0, 0F), "lower player-setting boundaries were rejected");
        check(allowed(20F, 20F, FilmPlayerSettingsPolicy.MAX_HUNGER, FilmPlayerSettingsPolicy.MAX_XP_LEVEL, 1F),
            "upper player-setting boundaries were rejected");
    }

    private static void testNonFiniteFilmValues()
    {
        check(!allowed(Float.NaN, 20F, 20F, 0, 0F), "NaN film health was accepted");
        check(!allowed(Float.POSITIVE_INFINITY, 20F, 20F, 0, 0F), "infinite film health was accepted");
        check(!allowed(20F, Float.NaN, 20F, 0, 0F), "NaN maximum health was accepted");
        check(!allowed(20F, Float.POSITIVE_INFINITY, 20F, 0, 0F), "infinite maximum health was accepted");
        check(!allowed(20F, 20F, Float.NaN, 0, 0F), "NaN film hunger was accepted");
        check(!allowed(20F, 20F, Float.NEGATIVE_INFINITY, 0, 0F), "infinite film hunger was accepted");
        check(!allowed(20F, 20F, 20F, 0, Float.NaN), "NaN film XP progress was accepted");
        check(!allowed(20F, 20F, 20F, 0, Float.POSITIVE_INFINITY), "infinite film XP progress was accepted");
    }

    private static void testOutOfRangeFilmValues()
    {
        check(!allowed(-1F, 20F, 20F, 0, 0F), "negative film health was accepted");
        check(!allowed(0F, 20F, 20F, 0, 0F), "zero film health was accepted");
        check(!allowed(21F, 20F, 20F, 0, 0F), "film health above the player's maximum was accepted");
        check(!allowed(0F, 0F, 20F, 0, 0F), "a non-positive maximum health was accepted");
        check(!allowed(20F, 20F, -1F, 0, 0F), "negative film hunger was accepted");
        check(!allowed(20F, 20F, 21F, 0, 0F), "film hunger above the vanilla maximum was accepted");
        check(!allowed(20F, 20F, 20F, -1, 0F), "negative film XP level was accepted");
        check(!allowed(20F, 20F, 20F, FilmPlayerSettingsPolicy.MAX_XP_LEVEL + 1, 0F), "excessive film XP level was accepted");
        check(!allowed(20F, 20F, 20F, 0, -0.01F), "negative film XP progress was accepted");
        check(!allowed(20F, 20F, 20F, 0, 1.01F), "film XP progress above one was accepted");
    }

    private static void structuredFoodStateRoundTrips()
    {
        CompoundTag expected = foodState(17, 37, 3.5F, 2.25F);
        CompoundTag mutated = foodState(2, 4, 0.25F, 19F);
        FoodData foodData = new FoodData();

        foodData.readAdditionalSaveData(expected);

        CompoundTag snapshot = new CompoundTag();

        foodData.addAdditionalSaveData(snapshot);
        foodData.readAdditionalSaveData(mutated);
        foodData.readAdditionalSaveData(snapshot.copy());

        CompoundTag restored = new CompoundTag();

        foodData.addAdditionalSaveData(restored);

        check(expected.equals(snapshot), "structured food snapshot lost food, saturation, exhaustion, or tick timer");
        check(expected.equals(restored), "structured food restore did not recover the complete snapshot");
    }

    private static CompoundTag foodState(int food, int tickTimer, float saturation, float exhaustion)
    {
        CompoundTag state = new CompoundTag();

        state.putInt("foodLevel", food);
        state.putInt("foodTickTimer", tickTimer);
        state.putFloat("foodSaturationLevel", saturation);
        state.putFloat("foodExhaustionLevel", exhaustion);

        return state;
    }

    private static boolean allowed(float hp, float maxHp, float hunger, int xpLevel, float xpProgress)
    {
        return FilmPlayerSettingsPolicy.isAllowed(hp, maxHp, hunger, xpLevel, xpProgress);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
