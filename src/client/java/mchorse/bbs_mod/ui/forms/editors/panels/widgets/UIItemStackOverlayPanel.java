package mchorse.bbs_mod.ui.forms.editors.panels.widgets;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.keyframes.factories.ItemStackKeyframeFactory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class UIItemStackOverlayPanel extends UIOverlayPanel
{
    private static final List<String> itemIDs = new ArrayList<>();

    public UISearchList<String> itemList;
    public UITextbox name;
    public UITrackpad count;
    public UITextarea nbt;

    private Consumer<ItemStack> callback;
    private ItemStack stack;

    static
    {
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet())
        {
            itemIDs.add(key.toString());
        }

        itemIDs.sort(String::compareToIgnoreCase);
    }

    public UIItemStackOverlayPanel(Consumer<ItemStack> callback, ItemStack stack)
    {
        super(UIKeys.ACTIONS_ITEM_STACK);

        this.callback = callback;
        this.stack = stack.copy();
        this.name = new UITextbox(1000, (v) ->
        {
            if (v.isEmpty())
            {
                this.stack.remove(DataComponents.CUSTOM_NAME);
            }
            else
            {
                this.stack.set(DataComponents.CUSTOM_NAME, Component.literal(v));
            }

            this.pickItemStack(this.stack);
            this.updateNbt();
        });
        this.name.setText(stack.getHoverName().getString());
        this.count = new UITrackpad((v) ->
        {
            this.stack.setCount(v.intValue());
            this.pickItemStack(this.stack);
            this.updateNbt();
        });
        this.count.limit(1.0, stack.getMaxStackSize(), true).setValue(stack.getCount());
        this.nbt = new UITextarea<>((v) ->
        {
            try
            {
                CompoundTag nbtCompound = TagParser.parseTag(v);
                ItemStack itemStack = ItemStack.CODEC.parse(ItemStackKeyframeFactory.currentOps(), nbtCompound).result().orElse(ItemStack.EMPTY);

                this.pickItemStack(itemStack);
                this.itemList.list.setCurrentScroll(BuiltInRegistries.ITEM.getKey(this.stack.getItem()).toString());
            }
            catch (Exception e)
            {
                this.pickItemStack(ItemStack.EMPTY);
            }

        }).background();
        this.nbt.wrap();
        this.updateNbt();
        this.itemList = new UISearchList<>(new UIStringList((l) -> this.setItem(l.get(0))));
        this.itemList.label(UIKeys.GENERAL_SEARCH).list.background();
        this.itemList.list.clear();
        this.itemList.list.add(itemIDs);
        this.itemList.list.setCurrentScroll(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());

        UIElement element = UI.column(5, 6, this.name, this.count);

        element.relative(this.content).y(1.0F).w(0.5F).anchorY(1.0F);
        this.nbt.relative(this.content).x(0.5F, 6).y(6).w(0.5F, -12).h(1.0F, -12);
        this.itemList.relative(this.content).xy(6, 6).w(0.5F, -12).hTo(element.area, 0.0F, 0);

        this.content.add(this.nbt, element, this.itemList);
    }

    private void updateNbt()
    {
        this.nbt.setText(ItemStack.CODEC.encodeStart(ItemStackKeyframeFactory.currentOps(), this.stack).result().map(Tag::getAsString).orElse("{}"));
    }

    private void pickItemStack(ItemStack itemStack)
    {
        if (this.callback != null)
        {
            this.callback.accept(itemStack);
        }
    }

    private void setItem(String s)
    {
        this.stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(s)));

        this.pickItemStack(this.stack);
        this.updateNbt();
    }
}
