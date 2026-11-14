package mchorse.bbs_mod.ui.framework.elements.context;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.context.ContextAction;
import mchorse.bbs_mod.ui.utils.context.MenuIcon;
import mchorse.bbs_mod.ui.utils.context.MenuVerb;

public class UISimpleContextMenu extends UIContextMenu
{
    public static final int MIN_WIDTH = 100;
    public static final int FILTER_THRESHOLD = 12;
    public static final int FILTER_HEIGHT = 20;

    public UIList<ContextAction> actions;
    public UIContextMenuBar bar;
    public UITextbox filter;

    private boolean focusFilter = true;
    private boolean shown;

    private final MouseGestureOwnership actionOwnership = new MouseGestureOwnership();
    private long actionGeneration;
    private ContextAction action;

    public UISimpleContextMenu()
    {
        super();

        this.bar = new UIContextMenuBar(this::dismiss);
        this.bar.relative(this).w(1F).h(UIContextMenuBar.HEIGHT);

        this.filter = new UITextbox(100, (text) -> this.actions.filter(text));
        this.filter.placeholder(UIKeys.GENERAL_SEARCH);
        this.filter.relative(this).w(1F).h(FILTER_HEIGHT);
        this.filter.setVisible(false);

        this.actions = new UIActionList((action) ->
        {
            if (action.get(0).runnable != null)
            {
                UIContext context = this.getContext();
                long generation = this.actionOwnership.acquireToken(context.mouseButton);

                if (generation != 0L)
                {
                    this.actionGeneration = generation;
                    this.action = action.get(0);
                }
            }
        });

        this.actions.cancelScrollEdge().full(this);
        this.add(this.bar, this.filter, this.actions);
    }

    @Override
    public boolean isEmpty()
    {
        return this.actions.getList().isEmpty() && this.bar.isEmpty();
    }

    public void canFocusFilter(boolean focusFilter)
    {
        this.focusFilter = focusFilter;
    }

    @Override
    public void setMouse(UIContext context)
    {
        if (context.canGoBack())
        {
            this.bar.register(new MenuIcon(MenuVerb.BACK, context::backContextMenu).keepOpen());
        }

        boolean hasActions = !this.actions.getList().isEmpty();
        boolean filtering = this.actions.getList().size() > FILTER_THRESHOLD;

        this.bar.sync(hasActions);

        int top = this.bar.isVisible() ? this.bar.getTotalHeight() : 0;
        int width = hasActions ? MIN_WIDTH : this.bar.getContentWidth();

        for (ContextAction action : this.actions.getList())
        {
            width = Math.max(action.getWidth(context.batcher.getFont()), width);
        }

        width = Math.max(width, this.bar.getContentWidth());
        this.filter.setVisible(filtering);
        this.filter.y(top);

        if (filtering)
        {
            top += FILTER_HEIGHT;
        }

        this.actions.y(top).h(1F, -top);
        this.set(context.mouseX(), context.mouseY(), width, 0).h(this.actions.scroll.scrollSize + top).maxH(context.menu.height - 10).bounds(context.menu.overlay, 5);
    }

    @Override
    public void render(UIContext context)
    {
        if (!this.shown)
        {
            this.shown = true;

            if (this.focusFilter && this.filter.isVisible())
            {
                context.focus(this.filter);
            }
        }

        super.render(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        long generation = this.actionGeneration;

        if (this.actionOwnership.release(context.mouseButton, generation))
        {
            ContextAction action = this.action;

            this.actionGeneration = 0L;
            this.action = null;

            if (action != null)
            {
                action.runnable.run();
                this.dismiss();

                return true;
            }
        }

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        long generation = this.actionGeneration;

        if (this.actionOwnership.release(context.mouseButton, generation))
        {
            this.actionGeneration = 0L;
            this.action = null;
        }
    }

    public void pick(int index)
    {
        this.actions.setIndex(index);

        ContextAction action = this.actions.getCurrentFirst();

        this.actionOwnership.cancel();
        this.actionGeneration = 0L;
        this.action = null;

        if (action != null && action.runnable != null)
        {
            action.runnable.run();
            this.dismiss();
        }
    }

}
