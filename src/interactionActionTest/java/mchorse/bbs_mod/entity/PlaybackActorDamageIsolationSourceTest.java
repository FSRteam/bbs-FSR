package mchorse.bbs_mod.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source and state regressions for private actor damage isolation. */
public final class PlaybackActorDamageIsolationSourceTest
{
    private static final Path ACTOR_ENTITY = Path.of(
        "src/main/java/mchorse/bbs_mod/entity/ActorEntity.java"
    );
    private static final Path ATTACK_ACTION = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/types/AttackActionClip.java"
    );

    private PlaybackActorDamageIsolationSourceTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("PlaybackActorDamageIsolationSourceTest passed");
    }

    public static void runAll() throws IOException
    {
        PlaybackActorDamageProjectionTest.runAll();

        Path root = findProjectRoot();
        String actor = compact(Files.readString(root.resolve(ACTOR_ENTITY)));
        String attack = compact(Files.readString(root.resolve(ATTACK_ACTION)));
        int hurt = actor.indexOf("public boolean hurt(DamageSource source, float amount)");
        int projection = actor.indexOf("private boolean hurtPlaybackProjection(", hurt);
        int die = actor.indexOf("public void die(DamageSource source)", projection);
        int deathProjection = actor.indexOf("private void diePlaybackProjection()", projection);
        int sound = actor.indexOf("public void playSound(SoundEvent sound, float volume, float pitch)", deathProjection);

        check(hurt >= 0 && projection > hurt && die > projection
                && deathProjection > die && sound > deathProjection,
            "private actor visual hurt/death path is missing");

        String hurtGate = actor.substring(hurt, projection);
        String deathGate = actor.substring(die, deathProjection);
        String isolatedPath = actor.substring(projection, sound);

        check(hurtGate.contains("if (!this.isPlaybackWorldIsolated()) { return super.hurt(source, amount); }")
                && hurtGate.contains("if (source == null || !this.isPlaybackActionAuthorized()")
                && hurtGate.contains("return this.hurtPlaybackProjection(source, amount);"),
            "ordinary/authorized private ActorEntity damage routing changed");
        check(deathGate.contains("if (!this.isPlaybackWorldIsolated()) { super.die(source); return; }")
                && deathGate.contains(
                    "if (this.level().isClientSide || this.isPlaybackActionAuthorized()) "
                        + "{ this.diePlaybackProjection(); }"
                ),
            "ordinary/authorized private ActorEntity death routing changed");
        check(isolatedPath.contains("PlaybackActorDamageProjection.apply(")
                && isolatedPath.contains("this.level().broadcastDamageEvent(this, source);")
                && isolatedPath.contains("this.level().broadcastEntityEvent(this, (byte) 3);")
                && isolatedPath.contains("this.setPose(Pose.DYING);"),
            "private actor hurt/death no longer publishes the tracked visual state");

        String[] forbidden = {
            "CommonHooks",
            "onLiving",
            "gameEvent(",
            "dropAllDeathLoot(",
            "dropExperience(",
            "awardKillScore(",
            "knockback("
        };

        for (String token : forbidden)
        {
            check(!isolatedPath.contains(token),
                "private actor damage re-entered shared-world side effect " + token);
        }

        check(actor.contains("return this.isPlaybackVisibleTo(player) && super.broadcastToPlayer(player);")
                && actor.contains("ServerPlayer player = level.getServer().getPlayerList().getPlayer(audience);")
                && actor.contains("if (player == null || player.serverLevel() != level) { return; }")
                && actor.contains("player.connection.send(new ClientboundSoundEntityPacket(")
                && actor.contains("public void gameEvent(Holder<GameEvent> event, @Nullable Entity source)")
                && actor.contains("super.setRemainingFireTicks(this.isPlaybackWorldIsolated() ? 0 : ticks);"),
            "private actor packets, sounds, vibrations, or fire escaped the audience/world boundary");

        int tick = actor.indexOf("public void tick()");
        int projectionTick = actor.indexOf("private void tickPlaybackProjectionServer()", tick);
        int visualTick = actor.indexOf("private void tickActorVisualState()", projectionTick);
        int superTick = actor.indexOf("super.tick();", tick);
        String tickGate = actor.substring(tick, superTick);
        String projectionTickBody = actor.substring(projectionTick, visualTick);

        check(tick >= 0 && superTick > tick && projectionTick > superTick && visualTick > projectionTick,
            "private actor logical-server tick isolation path is missing");
        check(tickGate.contains(
                    "if (!this.level().isClientSide && this.isPlaybackWorldIsolated()) "
                        + "{ this.tickPlaybackProjectionServer(); return; }"
                ),
            "private actor reaches LivingEntity.tick on the logical server");
        check(projectionTickBody.contains("this.oAttackAnim = this.attackAnim;")
                && projectionTickBody.contains("this.firstTick = false;")
                && projectionTickBody.contains("if (this.hurtTime > 0) { this.hurtTime -= 1; }")
                && projectionTickBody.contains("if (this.invulnerableTime > 0) { this.invulnerableTime -= 1; }")
                && projectionTickBody.contains("this.level().shouldTickDeath(this)")
                && projectionTickBody.contains("this.tickDeath();")
                && projectionTickBody.contains("this.tickActorVisualState();"),
            "private actor server tick dropped required visual hurt/death state");

        String[] tickForbidden = {
            "super.tick()",
            "baseTick(",
            "aiStep(",
            "EnchantmentHelper",
            "detectEquipmentUpdates",
            "onLivingBreathe"
        };

        for (String token : tickForbidden)
        {
            check(!projectionTickBody.contains(token),
                "private actor server tick re-entered shared-world path " + token);
        }

        int privateAttackGate = attack.indexOf(
            "boolean privatePlaybackActor = entity instanceof ActorEntity actorEntity "
                + "&& actorEntity.isPlaybackWorldIsolated();"
        );
        int publicHook = attack.indexOf(
            "!privatePlaybackActor && !CommonHooks.onPlayerAttackTarget(player, entity)",
            privateAttackGate
        );
        int scopedAttackable = attack.indexOf("!entity.isAttackable()", publicHook);
        int scopedHurt = attack.indexOf("entity.hurt(", scopedAttackable);

        check(privateAttackGate >= 0
                && publicHook > privateAttackGate
                && scopedAttackable > publicHook
                && scopedHurt > scopedAttackable,
            "private actor attack did not skip only the public hook before scoped attack/damage gates");
        check(attack.contains("import mchorse.bbs_mod.entity.ActorEntity;")
                && attack.contains("CommonHooks.onPlayerAttackTarget(player, entity)"),
            "ordinary entity attacks no longer retain the NeoForge public attack hook");
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(ACTOR_ENTITY)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(ACTOR_ENTITY)))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
