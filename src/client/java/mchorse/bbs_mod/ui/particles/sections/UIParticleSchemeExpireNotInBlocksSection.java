package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIItemStack;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.expiration.ParticleComponentExpireNotInBlocks;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class UIParticleSchemeExpireNotInBlocksSection extends UIParticleSchemeComponentSection<ParticleComponentExpireNotInBlocks>
{
    private UIElement blockListContainer;
    private List<UIItemStack> blockItems = new ArrayList<>();
    private UIElement addBlockButton;

    public UIParticleSchemeExpireNotInBlocksSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.blockListContainer = new UIElement();
        this.blockListContainer.column(2).stretch().vertical().height(20);

        this.addBlockButton = new UIIcon(Icons.ADD, (b) ->
        {
            this.addBlockEntry();
        });
        this.addBlockButton.tooltip(UIKeys.SNOWSTORM_EXPIRATION_ADD_BLOCK);

        this.fields.add(this.blockListContainer);
        this.fields.add(this.addBlockButton);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_EXPIRATION_NOT_IN_BLOCKS;
    }

    @Override
    protected ParticleComponentExpireNotInBlocks getComponent(ParticleScheme scheme)
    {
        return scheme.getOrCreate(ParticleComponentExpireNotInBlocks.class);
    }

    @Override
    protected void fillData()
    {
        this.rebuildBlockList();
    }

    private void rebuildBlockList()
    {
        this.blockListContainer.removeAll();
        this.blockItems.clear();

        for (int i = 0; i < this.component.blocks.size(); i++)
        {
            final int index = i;
            String blockId = this.component.blocks.get(i);

            UIItemStack item = new UIItemStack((stack) ->
            {
                this.replaceBlock(index, stack);
            });

            this.blockItems.add(item);

            ItemStack displayStack = resolveBlockToItemStack(blockId);
            item.setStack(displayStack);

            this.blockListContainer.add(item);
        }

        this.resizeParent();
    }

    private void addBlockEntry()
    {
        UIItemStack item = new UIItemStack((stack) ->
        {
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem)
            {
                String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
                this.component.blocks.add(blockId);
                this.editor.dirty();
                this.rebuildBlockList();
            }
        });

        this.blockListContainer.add(item);
        this.resizeParent();
    }

    private void replaceBlock(int index, ItemStack stack)
    {
        if (stack.isEmpty())
        {
            if (index < this.component.blocks.size())
            {
                this.component.blocks.remove(index);
            }
        }
        else if (stack.getItem() instanceof BlockItem blockItem)
        {
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();

            if (index < this.component.blocks.size())
            {
                this.component.blocks.set(index, blockId);
            }
        }

        this.editor.dirty();
        this.rebuildBlockList();
    }

    private static ItemStack resolveBlockToItemStack(String blockId)
    {
        try
        {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.parse(blockId);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
            if (block != null)
            {
                return new ItemStack(block.asItem());
            }
        }
        catch (Exception e) {}

        return ItemStack.EMPTY;
    }
}
