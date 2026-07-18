package mchorse.bbs_mod.actions;

public enum PlayerType
{
    NORMAL, FILM_EDITOR, RECORDING, TARGETED_COMMAND;

    public boolean appliesFirstPersonState()
    {
        return this == NORMAL || this == TARGETED_COMMAND;
    }

    public boolean isTargetedDelivery()
    {
        return this == TARGETED_COMMAND;
    }

    public boolean shouldSendStopNotification(boolean forced)
    {
        return this == NORMAL
            || this == TARGETED_COMMAND
            || (this == FILM_EDITOR && forced);
    }
}
