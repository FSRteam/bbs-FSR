package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.utils.clips.Clips;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

public class ActionRecorder
{
    private Film film;
    private ServerPlayer entity;
    private Clips clips = new Clips("...", BBSMod.getFactoryActionClips());
    private int tick;
    private int countdown;
    private int initialTick;
    private boolean targetedAttackRecordedThisTick;
    private Clips terminalClips;
    private boolean terminalPrepared;
    private boolean terminalForced;
    private boolean terminalTeardownComplete;
    private boolean terminalDelivered;

    public ActionRecorder(Film film, ServerPlayer entity, int tick, int countdown)
    {
        this.film = film;
        this.entity = entity;
        this.tick = tick;
        this.countdown = countdown;
        this.initialTick = tick;
    }

    public Film getFilm()
    {
        return this.film;
    }

    public Clips getClips()
    {
        return this.clips;
    }

    public int getInitialTick()
    {
        return this.initialTick;
    }

    public Clips composeClips()
    {
        this.terminalPrepared = true;

        if (this.terminalClips == null)
        {
            this.clips.sortLayers();
            this.terminalClips = this.clips;
        }

        return this.terminalClips;
    }

    public void prepareTerminal(boolean forced)
    {
        if (!this.terminalPrepared)
        {
            this.terminalPrepared = true;
            this.terminalForced = forced;
        }

        this.composeClips();
    }

    public boolean isTerminalForced()
    {
        return this.terminalForced;
    }

    public boolean isTerminalTeardownComplete()
    {
        return this.terminalTeardownComplete;
    }

    public void markTerminalTeardownComplete()
    {
        this.terminalTeardownComplete = true;
    }

    public boolean isTerminalDelivered()
    {
        return this.terminalDelivered;
    }

    public void markTerminalDelivered()
    {
        this.terminalDelivered = true;
    }

    public void add(ActionClip clip)
    {
        if (this.terminalPrepared || this.countdown > 0)
        {
            return;
        }

        if (clip instanceof AttackActionClip attack && attack.target.isPresent())
        {
            this.targetedAttackRecordedThisTick = true;
        }

        clip.tick.set(this.tick);
        clip.duration.set(1);

        this.clips.addClip(clip);
    }

    public void tick(ServerPlayer player)
    {
        if (this.terminalPrepared)
        {
            return;
        }

        if (this.countdown > 0)
        {
            this.countdown -= 1;

            return;
        }

        boolean swingStarted = player.swingTime == -1;

        if (swingStarted)
        {
            SwipeActionClip swipe = new SwipeActionClip();

            swipe.hand.set(player.swingingArm != InteractionHand.OFF_HAND);
            this.add(swipe);

            if (BBSSettings.recordingSwipeDamage.get()
                && shouldRecordFallbackAttack(swingStarted, this.targetedAttackRecordedThisTick))
            {
                AttackActionClip clip = new AttackActionClip();

                clip.damage.set(2F);
                this.add(clip);
            }
        }

        this.targetedAttackRecordedThisTick = false;
        this.tick += 1;
    }

    static boolean shouldRecordFallbackAttack(boolean swingStarted, boolean targetedAttackRecorded)
    {
        return swingStarted && !targetedAttackRecorded;
    }
}
