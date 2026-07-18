package mchorse.bbs_mod.ui.film.clips.actions;

import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;

public class UISwipeActionClip extends UIActionClip<SwipeActionClip>
{
    public UIToggle hand;

    public UISwipeActionClip(SwipeActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.hand = new UIToggle(UIKeys.ACTIONS_ITEM_MAIN_HAND, (button) ->
            this.editor.editMultiple(this.clip.hand, (hand) -> hand.set(button.getValue())));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.hand);
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.hand.setValue(this.clip.hand.get());
    }
}
