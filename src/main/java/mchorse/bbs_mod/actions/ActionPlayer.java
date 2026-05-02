package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ActionPlayer
{
    public Film film;
    public int tick;
    public boolean playing = true;
    public int countdown;
    public int exception;
    public PlayerType type;

    public boolean syncing;
    public boolean stopDamage = true;

    private ServerPlayer serverPlayer;
    private ServerLevel level;
    private int duration;

    private Map<String, LivingEntity> actors = new HashMap<>();

    private List<ItemStack> cachedInventory = new ArrayList<>();
    private Form cachedForm;

    private float cacheHp;
    private int cacheHunger;
    private int cacheXpLevel;
    private float cacheXpProgress;
    private boolean currentTickActionApplied;

    public ActionPlayer(ServerPlayer serverPlayer, ServerLevel level, Film film, int tick, int countdown, int exception, PlayerType type)
    {
        this.level = level;
        this.film = film;
        this.tick = tick;
        this.countdown = countdown;
        this.exception = exception;
        this.type = type;

        this.serverPlayer = serverPlayer;
        this.duration = film.camera.calculateDuration();

        this.updateReplayEntities();

        Replay fpReplay = film.getFirstPersonReplay();

        if (this.type == PlayerType.NORMAL && this.serverPlayer != null && fpReplay != null)
        {
            for (int i = 0; i < this.serverPlayer.getInventory().getContainerSize(); i++)
            {
                this.cachedInventory.add(serverPlayer.getInventory().getItem(i).copy());
                this.serverPlayer.getInventory().setItem(i, CollectionUtils.getSafe(this.film.inventory.getStacks(), i, ItemStack.EMPTY));
            }

            Morph morph = Morph.getMorph(this.serverPlayer);

            if (morph != null)
            {
                this.cachedForm = FormUtils.copy(morph.getForm());
            }

            ServerNetwork.sendMorphToTracked(this.serverPlayer, fpReplay.form.get());

            this.cacheHp = this.serverPlayer.getHealth();
            this.cacheHunger = this.serverPlayer.getFoodData().getFoodLevel();
            this.cacheXpLevel = this.serverPlayer.experienceLevel;
            this.cacheXpProgress = this.serverPlayer.experienceProgress;

            applyFilmPlayerSettingsTo(this.serverPlayer, this.film.hp.get(), this.film.hunger.get(), this.film.xpLevel.get(), this.film.xpProgress.get());
        }
    }

    public static void applyFilmPlayerSettingsTo(ServerPlayer player, float hp, float hunger, int xpLevel, float xpProgress)
    {
        player.setHealth(hp);
        player.getFoodData().setFoodLevel((int) hunger);
        player.setExperienceLevels(xpLevel);
        player.experienceProgress = xpProgress;
    }

    public void updateReplayEntities()
    {
        for (LivingEntity entity : this.actors.values())
        {
            if (!(entity instanceof Player))
            {
                entity.discard();
            }
        }

        this.actors.clear();

        List<Replay> list = this.film.replays.getList();

        for (int i = 0; i < list.size(); i++)
        {
            Replay replay = list.get(i);
            boolean isActor = replay.actor.get() || replay.fp.get();

            if (i == this.exception || !isActor || !replay.enabled.get())
            {
                continue;
            }

            if (replay.fp.get() && this.serverPlayer != null)
            {
                if (this.type == PlayerType.NORMAL)
                {
                    this.actors.put(replay.getId(), this.serverPlayer);
                }
            }
            else
            {
                ActorEntity actor = new ActorEntity(BBSMod.ACTOR_ENTITY.get(), this.level);

                actor.setForm(FormUtils.copy(replay.form.get()));

                this.apply(actor, replay, this.tick, false);
                this.actors.put(replay.getId(), actor);
                this.level.addFreshEntity(actor);
            }
        }

        for (ServerPlayer player : this.level.getPlayers((player) -> true))
        {
            ServerNetwork.sendActors(player, this.film.getId(), this.actors);
        }
    }

    public ServerLevel getLevel()
    {
        return this.level;
    }

    public void apply(LivingEntity actor, Replay replay, float tick, boolean ticking)
    {
        double x = replay.keyframes.x.interpolate(tick);
        double y = replay.keyframes.y.interpolate(tick);
        double z = replay.keyframes.z.interpolate(tick);
        float yawHead = replay.keyframes.headYaw.interpolate(tick).floatValue();
        float yawBody = replay.keyframes.bodyYaw.interpolate(tick).floatValue();
        float pitch = replay.keyframes.pitch.interpolate(tick).floatValue();

        Vec3 pos = actor.position();

        if (ticking)
        {
            actor.move(MoverType.SELF, new Vec3(x - pos.x, y - pos.y, z - pos.z));
        }

        actor.setPos(x, y, z);
        actor.setYRot(yawHead);
        actor.setYHeadRot(yawHead);
        actor.setXRot(pitch);
        actor.setYBodyRot(yawBody);
        actor.setShiftKeyDown(replay.keyframes.sneaking.interpolate(tick) > 0);
        actor.setOnGround(replay.keyframes.grounded.interpolate(tick) > 0);
        actor.setItemSlot(EquipmentSlot.OFFHAND, replay.keyframes.offHand.interpolate(tick, ItemStack.EMPTY));
        actor.setItemSlot(EquipmentSlot.HEAD, replay.keyframes.armorHead.interpolate(tick, ItemStack.EMPTY));
        actor.setItemSlot(EquipmentSlot.CHEST, replay.keyframes.armorChest.interpolate(tick, ItemStack.EMPTY));
        actor.setItemSlot(EquipmentSlot.LEGS, replay.keyframes.armorLegs.interpolate(tick, ItemStack.EMPTY));
        actor.setItemSlot(EquipmentSlot.FEET, replay.keyframes.armorFeet.interpolate(tick, ItemStack.EMPTY));

        if (actor instanceof ServerPlayer player)
        {
            int selectedSlot = player.getInventory().selected;
            int slot = MathUtils.clamp(replay.keyframes.selectedSlot.interpolate(this.tick), 0, 8);

            if (selectedSlot != slot)
            {
                ServerNetwork.sendSelectedSlot(player, slot);
            }

            actor.setItemSlot(EquipmentSlot.MAINHAND, replay.keyframes.mainHand.interpolate(tick, ItemStack.EMPTY));
        }
        else
        {
            actor.setItemSlot(EquipmentSlot.MAINHAND, replay.keyframes.mainHand.interpolate(tick, ItemStack.EMPTY));
        }

        double vx = x - replay.keyframes.x.interpolate(tick - 1);
        double vy = y - replay.keyframes.y.interpolate(tick - 1);
        double vz = z - replay.keyframes.z.interpolate(tick - 1);

        if (vy == 0D)
        {
            vy = -0.0784;
        }

        actor.setDeltaMovement(vx, vy, vz);

        actor.fallDistance = replay.keyframes.fall.interpolate(tick).floatValue();
    }

    public boolean tick()
    {
        if (this.countdown > 0)
        {
            this.countdown -= 1;

            return false;
        }

        for (Map.Entry<String, LivingEntity> entry : this.actors.entrySet())
        {
            Replay replay = (Replay) this.film.replays.get(entry.getKey());

            if (replay != null)
            {
                this.apply(entry.getValue(), replay, this.tick, true);
            }
        }

        if (!this.playing)
        {
            return false;
        }

        if (this.tick >= 0)
        {
            if (this.currentTickActionApplied)
            {
                this.currentTickActionApplied = false;
            }
            else
            {
                this.applyAction();
            }
        }

        this.tick += 1;

        return !this.syncing && this.tick >= this.duration;
    }

    private void applyAction()
    {
        SuperFakePlayer fakePlayer = SuperFakePlayer.get(this.level);
        List<Replay> list = this.film.replays.getList();

        for (int i = 0; i < list.size(); i++)
        {
            if (i == this.exception)
            {
                continue;
            }

            Replay replay = list.get(i);

            if (!replay.enabled.get())
            {
                continue;
            }

            LivingEntity actor = this.actors.get(replay.getId());

            replay.applyActions(actor, fakePlayer, this.film, this.tick);
        }
    }

    public void syncData(DataPath key, BaseType data)
    {
        BaseValue baseValue = this.film.getRecursively(key);

        if (baseValue != null)
        {
            baseValue.fromData(data);

            if (baseValue.getId().equals("actor") || baseValue.getId().equals("enabled") || baseValue.getId().equals("replays"))
            {
                this.updateReplayEntities();
            }
        }
    }

    public void goTo(int tick)
    {
        this.goTo(this.tick, tick);
    }

    public void goTo(int from, int tick)
    {
        for (Map.Entry<String, LivingEntity> entry : this.actors.entrySet())
        {
            Replay replay = (Replay) this.film.replays.get(entry.getKey());

            if (replay != null)
            {
                this.apply(entry.getValue(), replay, this.tick, false);
            }
        }

        if (from != tick)
        {
            this.tick = from;

            while (this.tick != tick)
            {
                this.tick += this.tick > tick ? -1 : 1;

                this.applyAction();
            }

            this.currentTickActionApplied = this.tick >= 0;
        }
    }

    public void stop()
    {
        for (LivingEntity value : this.actors.values())
        {
            if (!(value instanceof Player))
            {
                value.discard();
            }
        }

        if (this.type == PlayerType.NORMAL && this.serverPlayer != null && this.film.getFirstPersonReplay() != null)
        {
            for (int i = 0; i < this.serverPlayer.getInventory().getContainerSize(); i++)
            {
                this.serverPlayer.getInventory().setItem(i, this.cachedInventory.get(i));
            }

            ServerNetwork.sendMorphToTracked(this.serverPlayer, this.cachedForm);

            this.serverPlayer.setHealth(this.cacheHp);
            this.serverPlayer.getFoodData().setFoodLevel(this.cacheHunger);
            this.serverPlayer.experienceProgress = this.cacheXpProgress;
            this.serverPlayer.setExperienceLevels(this.cacheXpLevel);
        }
    }

    public void toggle()
    {
        this.playing = !this.playing;
    }
}
