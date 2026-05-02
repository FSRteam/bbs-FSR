package mchorse.bbs_mod.ui.forms.editors.panels.widgets;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

public class UIItemStack extends UIElement
{
    private Consumer<ItemStack> callback;
    private ItemStack stack;
    private boolean opened;

    public UIItemStack(Consumer<ItemStack> callback)
    {
        this.stack = ItemStack.EMPTY;
        this.callback = callback;

        this.context((menu) ->
        {
            menu.action(Icons.CLOSE, UIKeys.ITEM_STACK_CONTEXT_RESET, () ->
            {
                if (this.callback != null)
                {
                    this.callback.accept(ItemStack.EMPTY);
                }

                this.setStack(ItemStack.EMPTY);
            });

            if (!this.stack.isEmpty())
            {
                menu.action(Icons.PLAYER, UIKeys.ITEM_STACK_CONTEXT_GIVE, () -> giveToPlayer(this.stack));
            }
        });

        this.h(UIConstants.CONTROL_HEIGHT);
    }

    public void setStack(ItemStack stack)
    {
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    protected boolean subMouseClicked(UIContext context)
    {
        if (this.area.isInside(context) && context.mouseButton == 0)
        {
            this.opened = true;

            UIUnifiedPickOverlayPanel panel = UIUnifiedPickOverlayPanel.forItem((i) ->
            {
                if (this.callback != null)
                {
                    this.callback.accept(i);
                }

                this.setStack(i);
            }, this.stack);

            panel.onClose((a) -> this.opened = false);

            UIOverlay.addOverlay(this.getContext(), panel, 0.5F, 0.75F);
            UIUtils.playClick();

            return true;
        } else {
            return super.subMouseClicked(context);
        }
    }

    public void render(UIContext context)
    {
        int border = this.opened ? Colors.A100 | BBSSettings.primaryColor.get() : Colors.WHITE;

        context.batcher.box((float)this.area.x, (float)this.area.y, (float)this.area.ex(), (float)this.area.ey(), border);
        context.batcher.box((float)(this.area.x + 1), (float)(this.area.y + 1), (float)(this.area.ex() - 1), (float)(this.area.ey() - 1), -3750202);

        if (this.stack != null && !this.stack.isEmpty())
        {
            PoseStack matrices = context.batcher.getContext().pose();
            CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();

            matrices.pushPose();
            consumers.setUI(true);
            context.batcher.getContext().renderItem(this.stack, this.area.mx() - 8, this.area.my() - 8);
            context.batcher.getContext().renderItemDecorations(context.batcher.getFont().getRenderer(), this.stack, this.area.mx() - 8, this.area.my() - 8);
            consumers.setUI(false);
            matrices.popPose();
        }

        super.render(context);
    }

    /**
     * Delivers {@code stack} to the local player through the vanilla /give command.
     * Requires the player to have enough permission for /give.
     */
    static void giveToPlayer(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.player.connection == null || mc.level == null)
        {
            return;
        }

        HolderLookup.Provider registries = mc.level.registryAccess();
        String item = new ItemInput(stack.getItemHolder(), stack.getComponentsPatch()).serialize(registries);

        mc.player.connection.sendCommand("give @s " + item + " " + stack.getCount());
    }
}
