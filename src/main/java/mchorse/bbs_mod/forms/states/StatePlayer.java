package mchorse.bbs_mod.forms.states;

import mchorse.bbs_mod.forms.forms.Form;

public class StatePlayer
{
    private AnimationState state;
    private int tick;

    public StatePlayer(AnimationState state)
    {
        this.state = state;
    }

    public boolean canBeRemoved()
    {
        if (this.state.main.get())
        {
            return false;
        }

        return this.tick >= this.state.duration.get();
    }

    public void update()
    {
        this.tick += 1;

        if (this.state.main.get() && this.tick >= this.state.duration.get())
        {
            this.tick = 0;
        }
    }

    public void assignValues(Form form, float transition)
    {
        this.state.properties.applyProperties(form, this.tick + transition);
    }
}