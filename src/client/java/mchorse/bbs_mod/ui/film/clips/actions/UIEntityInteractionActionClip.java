package mchorse.bbs_mod.ui.film.clips.actions;

import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIItemStack;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;

public class UIEntityInteractionActionClip extends UIActionClip<EntityInteractionActionClip>
{
    public UIToggle hand;
    public UIItemStack itemStack;

    public UIEntityInteractionActionClip(EntityInteractionActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.hand = new UIToggle(UIKeys.ACTIONS_ITEM_MAIN_HAND, (button) ->
            this.editor.editMultiple(this.clip.hand, (hand) -> hand.set(button.getValue())));
        this.itemStack = new UIItemStack((stack) ->
            this.editor.editMultiple(this.clip.itemStack, (itemStack) -> itemStack.set(stack)));
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(UIKeys.ACTIONS_ITEM_MAIN_HAND, this.hand));
        this.panels.add(this.section(UIKeys.ACTIONS_ITEM_STACK, this.itemStack));
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.hand.setValue(this.clip.hand.get());
        this.itemStack.setStack(this.clip.itemStack.get());
    }
}
