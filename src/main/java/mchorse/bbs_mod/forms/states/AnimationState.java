package mchorse.bbs_mod.forms.states;

import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;

public class AnimationState extends ValueGroup
{
    public final FormProperties properties = new FormProperties("properties");
    public final ValueBoolean main = new ValueBoolean("main", false);

    public AnimationState(String id)
    {
        super(id);

        this.add(this.properties);
        this.add(this.main);
    }
}