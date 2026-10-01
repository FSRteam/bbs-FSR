package mchorse.bbs_mod.forms.structure;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.structures.UIStructureSaveMenu;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.renderers.InputRenderer;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The structure wand: a plain item whose whole behaviour lives here, on the client. Holding it
 * turns the mouse into a region tool, and the vanilla break/place/attack the clicks would have
 * done is swallowed before the game sees them.
 *
 * <p>The left button places corner A, the right one corner B — on the block under the crosshair,
 * or on the block at arm's length when there is none, so a region can start in the air. A corner
 * is re-placed by clicking again, nothing has to be undone first. Once both are down the box can
 * be reshaped without touching them: with the crosshair on one of its faces the wheel pushes that
 * face in and out, and with Alt held it slides the whole box that way instead. Alt + left drops the
 * selection, Alt + right opens the save dialog. Alt is the modifier throughout on purpose: sneak
 * would drop a flying player out of the air and Ctrl would break their sprint, and both happen
 * exactly while lining a region up. The hint above the hotbar always names what the buttons do
 * right now.</p>
 *
 * <p>The save it leads to writes into BBS's {@code structures} assets folder, so a build captured
 * here is a form in every world, not only in this one. Only useful in singleplayer all the same:
 * the region is read by the server, and a dedicated one would write the file onto its own disk,
 * where this client cannot see it.</p>
 *
 * <p>Platform note (upstream runs on Fabric): the clicks themselves are taken by a client mixin at
 * {@code Minecraft.startAttack/startUseItem} — the same place upstream took them — because the
 * game's own event sits behind early returns (survival's {@code missTime} after every whiffed
 * swing, busy hands) that would silently eat wand clicks. What that mixin cannot reach is the
 * attack key held down on a block, so the game keeps trying to break it every tick; the cancellable
 * {@link PlayerInteractEvent.LeftClickBlock}/{@code RightClickBlock} here stop that without a swing
 * or crack particles, and cover the integrated server under the same registration, which is where
 * upstream's common-side callbacks used to stand.</p>
 */
public class StructureWand
{
    public static final int COLOR_A = 0x4fb4ff;
    public static final int COLOR_B = 0xffb04a;

    /** Alpha of the face under the crosshair. The others are left empty so it reads as the one. */
    private static final float FACE_HOVER = 0.28F;
    private static final float CORNER_FILL = 0.3F;
    private static final float GHOST = 0.35F;

    /** Blocks one notch of the wheel is worth. A bigger jump is a click: re-placing a corner. */
    private static final int STEP = 1;

    private static final Color COLOR = new Color();

    /** The inventory tooltip, line by line, out of the item's own language file. */
    private static final String[] TOOLTIP = {
        "item.bbs.structure_wand.tooltip.corners",
        "item.bbs.structure_wand.tooltip.faces",
        "item.bbs.structure_wand.tooltip.save"
    };

    /* HUD */

    private static final int ROW = 20;

    /** Between the two columns. Wide enough that a row reads as a pair, not as four loose things. */
    private static final int COLUMN_GAP = 18;

    /** Prefilled into the next save dialog, so re-saving the same structure is Alt + right and Enter. */
    private static String lastName = "";

    /** Structure id whose form goes to "Recent" once the server confirms the file is written. */
    private static String pendingRecent;

    /** The face of the box under the crosshair this frame, null when there is none: what the wheel acts on. */
    private static Direction face;

    /** The block the next click lands on, refreshed every frame for the ghost. */
    private static BlockPos pick;

    public static void register()
    {
        /* The clicks themselves are taken at Minecraft.startAttack/startUseItem (see
         * MinecraftMixin), before the game decides what a click on a block means. What that
         * leaves is the attack key HELD on a block: the game keeps trying to break it every tick,
         * and cancelling these interact events is what stops that without a swing or crack
         * particles. Their server side is a net under all of it. */
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) ->
        {
            if (isHolding(event.getEntity()))
            {
                event.setCanceled(true);
            }
        });

        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) ->
        {
            if (isHolding(event.getEntity()))
            {
                event.setCanceled(true);
            }
        });

        /* The item stays a plain Item: the tooltip is the one thing it would need a class for, and
         * it belongs on the client with the rest of the wand anyway */
        NeoForge.EVENT_BUS.addListener((ItemTooltipEvent event) ->
        {
            if (event.getItemStack().is(BBSMod.STRUCTURE_WAND_ITEM.get()))
            {
                for (String line : TOOLTIP)
                {
                    event.getToolTip().add(Component.translatable(line).withStyle(ChatFormatting.GRAY));
                }
            }
        });
    }

    /* Input */

    /** Left button: corner A, or with Alt the whole selection dropped. True when the click was the wand's. */
    public static boolean onAttack()
    {
        Minecraft mc = Minecraft.getInstance();

        if (!isActive(mc))
        {
            return false;
        }

        if (Window.isAltPressed())
        {
            StructureSelection.clear();
        }
        else
        {
            StructureSelection.setA(pick(mc));
        }

        mc.player.swing(getHand(mc.player));

        return true;
    }

    /** Right button: corner B, or with Alt the finished box off to the save dialog. */
    public static boolean onUse()
    {
        Minecraft mc = Minecraft.getInstance();

        if (!isActive(mc))
        {
            return false;
        }

        if (Window.isAltPressed())
        {
            if (StructureSelection.isReady())
            {
                openSave();
            }
        }
        else
        {
            StructureSelection.setB(pick(mc));
            mc.player.swing(getHand(mc.player));
        }

        return true;
    }

    /**
     * The wheel over a face of the box: pushes it, or slides the box with Alt held. True when the
     * notch was taken, in which case the hotbar must not get it.
     */
    public static boolean onScroll(double vertical)
    {
        Minecraft mc = Minecraft.getInstance();

        if (!isActive(mc) || face == null || vertical == 0 || !StructureSelection.isReady())
        {
            return false;
        }

        /* Wheel away from the user pulls the face towards them: the box follows the hand, not
         * the normal it happens to be drawn on */
        int amount = vertical > 0 ? -STEP : STEP;

        if (Window.isAltPressed())
        {
            StructureSelection.move(face, amount);
        }
        else
        {
            StructureSelection.push(face, amount);
        }

        return true;
    }

    /** Whether the wand is in the player's hands with the world in front of them, not a screen. */
    private static boolean isActive(Minecraft mc)
    {
        return mc.player != null && mc.screen == null && isHolding(mc.player);
    }

    /**
     * Where a click lands: the block under the crosshair, or the block at arm's length when the
     * crosshair is on nothing — a region can start in the air, above a build.
     */
    private static BlockPos pick(Minecraft mc)
    {
        HitResult hit = mc.hitResult;

        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK)
        {
            return blockHit.getBlockPos();
        }

        float tickDelta = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(tickDelta);
        Vec3 look = mc.player.getViewVector(tickDelta);
        /* Since 1.21.1 the reach is an attribute of the player, not the interaction manager */
        double reach = mc.player.blockInteractionRange();

        return BlockPos.containing(eye.add(look.scale(reach)));
    }

    private static boolean isHolding(Player player, InteractionHand hand)
    {
        return player.getItemInHand(hand).is(BBSMod.STRUCTURE_WAND_ITEM.get());
    }

    private static boolean isHolding(Player player)
    {
        return isHolding(player, InteractionHand.MAIN_HAND) || isHolding(player, InteractionHand.OFF_HAND);
    }

    /** Whether the local player is holding the wand in either hand. */
    public static boolean isHolding()
    {
        Player player = Minecraft.getInstance().player;

        return player != null && isHolding(player);
    }

    private static InteractionHand getHand(Player player)
    {
        return isHolding(player, InteractionHand.MAIN_HAND) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    /* Saving */

    private static void openSave()
    {
        UIScreen.open(new UIStructureSaveMenu(lastName, StructureWand::save));
    }

    /**
     * From the dialog: hand the box to the server, which writes it into BBS's structures folder.
     * The selection stays — the same structure can be re-saved after a tweak — and the reply ends
     * the job, see {@link #onSaved}.
     *
     * @param name path under the structures folder, without the extension
     */
    public static void save(String name, boolean toRecent)
    {
        if (name.isEmpty() || !StructureSelection.isReady())
        {
            return;
        }

        lastName = name;
        pendingRecent = toRecent ? name : null;

        ClientNetwork.sendSaveStructure(name, StructureSelection.getMin(), StructureSelection.getMax());
    }

    /**
     * The server's word on the save. The structure cache is dropped either way: a re-saved file
     * must reach every form already pointing at that name. The form for "Recent" is only made now,
     * once the file is really there — made earlier it would show the old structure, or nothing.
     */
    public static void onSaved(boolean ok, String name)
    {
        Minecraft mc = Minecraft.getInstance();

        StructureManager.invalidate();

        if (mc.player != null)
        {
            mc.player.displayClientMessage(Component.literal((ok ? UIKeys.STRUCTURE_WAND_SAVED : UIKeys.STRUCTURE_WAND_SAVE_FAILED).format(name).get()), true);
        }

        if (ok && name.equals(pendingRecent))
        {
            addRecentForm(name);
        }

        pendingRecent = null;
    }

    private static void addRecentForm(String path)
    {
        StructureForm form = new StructureForm();

        /* No name of its own: the form is named after the structure it holds, and a name set here
         * would stick to it even after the structure was swapped for another. */
        form.structure.set(StructureManager.assetId(path));

        BBSModClient.getFormCategories().getRecentForms().getCategories().get(0).addForm(form);
    }

    /* World */

    /**
     * Draw the box, its two corners and a ghost of the block the next click would take. Depth
     * testing is off on purpose: a region is picked around a build, so its far edge is behind the
     * build almost every time. The face under the crosshair is found here too — it is a property
     * of this frame's view, and the wheel reads it.
     */
    public static void renderWorld(IBbsWorldRenderContext context)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || !isHolding(mc.player))
        {
            face = null;
            pick = null;

            return;
        }

        float tickDelta = context.tickDelta();
        Vec3 camera = context.camera().getPosition();
        AABB box = StructureSelection.getBox();

        pick = pick(mc);
        face = box == null ? null : findFace(mc.player.getEyePosition(tickDelta), mc.player.getViewVector(tickDelta), box);

        PoseStack stack = context.matrixStack();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        if (box != null)
        {
            renderBox(stack, camera, box);
        }

        BlockPos a = StructureSelection.getA();
        BlockPos b = StructureSelection.getB();

        if (a != null)
        {
            renderCorner(stack, camera, a, COLOR_A);
        }

        if (b != null)
        {
            renderCorner(stack, camera, b, COLOR_B);
        }

        if (!pick.equals(a) && !pick.equals(b))
        {
            renderGhost(stack, camera, pick);
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * The face of the box the ray goes through first — or, from inside the box, the one it leaves
     * through, which is the face the user is looking at either way. Null when the ray misses.
     */
    private static Direction findFace(Vec3 origin, Vec3 direction, AABB box)
    {
        double tNear = Double.NEGATIVE_INFINITY;
        double tFar = Double.POSITIVE_INFINITY;
        Direction near = null;
        Direction far = null;

        for (Direction.Axis axis : Direction.Axis.values())
        {
            double o = origin.get(axis);
            double d = direction.get(axis);
            double lo = box.min(axis);
            double hi = box.max(axis);

            if (Math.abs(d) < 1E-9)
            {
                if (o < lo || o > hi)
                {
                    return null;
                }

                continue;
            }

            double tLo = (lo - o) / d;
            double tHi = (hi - o) / d;
            Direction loFace = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            Direction hiFace = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            double tEnter = Math.min(tLo, tHi);
            double tExit = Math.max(tLo, tHi);

            if (tEnter > tNear)
            {
                tNear = tEnter;
                near = tLo < tHi ? loFace : hiFace;
            }

            if (tExit < tFar)
            {
                tFar = tExit;
                far = tLo < tHi ? hiFace : loFace;
            }
        }

        if (tNear > tFar || tFar < 0)
        {
            return null;
        }

        return tNear > 0 ? near : far;
    }

    private static void renderBox(PoseStack stack, Vec3 camera, AABB box)
    {
        float w = (float) box.getXsize();
        float h = (float) box.getYsize();
        float d = (float) box.getZsize();

        COLOR.set(BBSSettings.primaryColor.get());

        stack.pushPose();
        stack.translate(box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z);

        /* Only the hovered face is filled — an empty box lets the build inside it be seen, and
         * makes the one filled face unmistakable */
        if (face != null)
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            fillFace(builder, stack, face, w, h, d, COLOR.r, COLOR.g, COLOR.b, FACE_HOVER);

            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        Draw.renderBox(stack, 0, 0, 0, w, h, d, COLOR.r, COLOR.g, COLOR.b, 1F);

        /* The face the wheel would push gets a white rim: a flat box, its thickness along the normal zero */
        if (face != null)
        {
            boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            Direction.Axis axis = face.getAxis();
            float x = axis == Direction.Axis.X && positive ? w : 0;
            float y = axis == Direction.Axis.Y && positive ? h : 0;
            float z = axis == Direction.Axis.Z && positive ? d : 0;

            Draw.renderBox(stack, x, y, z, axis == Direction.Axis.X ? 0 : w, axis == Direction.Axis.Y ? 0 : h, axis == Direction.Axis.Z ? 0 : d, 1F, 1F, 1F, 0.9F);
        }

        stack.popPose();
    }

    /** One face of a box standing at the origin, as two triangles. */
    private static void fillFace(BufferBuilder builder, PoseStack stack, Direction side, float w, float h, float d, float r, float g, float b, float a)
    {
        switch (side)
        {
            case WEST -> Draw.fillQuad(builder, stack, 0, 0, 0, 0, 0, d, 0, h, d, 0, h, 0, r, g, b, a);
            case EAST -> Draw.fillQuad(builder, stack, w, 0, 0, w, 0, d, w, h, d, w, h, 0, r, g, b, a);
            case DOWN -> Draw.fillQuad(builder, stack, 0, 0, 0, w, 0, 0, w, 0, d, 0, 0, d, r, g, b, a);
            case UP -> Draw.fillQuad(builder, stack, 0, h, 0, w, h, 0, w, h, d, 0, h, d, r, g, b, a);
            case NORTH -> Draw.fillQuad(builder, stack, 0, 0, 0, w, 0, 0, w, h, 0, 0, h, 0, r, g, b, a);
            case SOUTH -> Draw.fillQuad(builder, stack, 0, 0, d, w, 0, d, w, h, d, 0, h, d, r, g, b, a);
        }
    }

    /** A corner block: filled in its own color, so A and B are told apart at a glance. */
    private static void renderCorner(PoseStack stack, Vec3 camera, BlockPos pos, int color)
    {
        float r = Colors.getR(color);
        float g = Colors.getG(color);
        float b = Colors.getB(color);

        stack.pushPose();
        stack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        Draw.fillBox(builder, stack, 0, 0, 0, 1, 1, 1, r, g, b, CORNER_FILL);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        Draw.renderBox(stack, 0, 0, 0, 1, 1, 1, r, g, b, 1F);

        stack.popPose();
    }

    /** The block the next click would take, as a faint white frame. */
    private static void renderGhost(PoseStack stack, Vec3 camera, BlockPos pos)
    {
        stack.pushPose();
        stack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        Draw.renderBox(stack, 0, 0, 0, 1, 1, 1, 1F, 1F, 1F, GHOST);
        stack.popPose();
    }

    /* HUD */

    private enum Glyph
    {
        LMB, RMB, WHEEL, ALT
    }

    /** One entry of the hint row: what to press, and what it does. */
    private record Hint(String label, int color, Glyph... glyphs)
    {
        private static final int JOIN = 9;
        private static final int AFTER = 5;

        public int width(FontRenderer font)
        {
            int width = 0;

            for (int i = 0; i < this.glyphs.length; i++)
            {
                width += glyphWidth(font, this.glyphs[i]) + (i > 0 ? JOIN : 0);
            }

            return width + AFTER + font.getWidth(this.label);
        }

        public void render(Batcher2D batcher, int x, int y)
        {
            FontRenderer font = batcher.getFont();

            for (int i = 0; i < this.glyphs.length; i++)
            {
                if (i > 0)
                {
                    batcher.textShadow("+", x + 2, y + (ROW - font.getHeight()) / 2F, Colors.LIGHTER_GRAY);
                    x += JOIN;
                }

                x += renderGlyph(batcher, this.glyphs[i], x, y);
            }

            batcher.textShadow(this.label, x + AFTER, y + (ROW - font.getHeight()) / 2F, this.color);
        }

        private static int glyphWidth(FontRenderer font, Glyph glyph)
        {
            return glyph == Glyph.ALT ? 16 + font.getWidth("Alt") : InputRenderer.MOUSE_WIDTH;
        }

        private static int renderGlyph(Batcher2D batcher, Glyph glyph, int x, int y)
        {
            if (glyph == Glyph.ALT)
            {
                int width = glyphWidth(batcher.getFont(), glyph);

                batcher.icon(Icons.KEY_CAP_LEFT, x, y);
                batcher.iconArea(Icons.KEY_CAP_REPEATABLE, x + 4, y, width - 8, ROW);
                batcher.icon(Icons.KEY_CAP_RIGHT, x + width, y, 1F, 0F);
                batcher.text("Alt", x + 8, y + 5, Colors.A100);

                return width;
            }

            InputRenderer.renderMouseButtons(batcher, x, y + (ROW - InputRenderer.MOUSE_HEIGHT) / 2, 0, glyph == Glyph.LMB, glyph == Glyph.RMB, glyph == Glyph.WHEEL, false);

            return InputRenderer.MOUSE_WIDTH;
        }
    }

    /**
     * The hint above the hotbar. Two columns: the bare gesture on the left, the same gesture with
     * Alt on the right, so a row reads across as "this button, and this button with Alt" and the
     * whole thing is half as wide as one long line. No plate behind it — the shadow on the text
     * carries it, and a slab of black over the world was worse than what it was protecting.
     *
     * <p>Wheel entries appear only while a face is under the crosshair, which is also what tells
     * the user the wheel is about to reshape the box rather than switch the slot.</p>
     */
    public static void renderHud(Batcher2D batcher)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.screen != null || mc.options.hideGui || !isHolding(mc.player))
        {
            return;
        }

        FontRenderer font = batcher.getFont();
        boolean ready = StructureSelection.isReady();
        List<Hint> plain = new ArrayList<>();
        List<Hint> alt = new ArrayList<>();

        plain.add(new Hint(UIKeys.STRUCTURE_WAND_CORNER_A.get(), COLOR_A, Glyph.LMB));
        plain.add(new Hint(UIKeys.STRUCTURE_WAND_CORNER_B.get(), COLOR_B, Glyph.RMB));

        if (ready)
        {
            alt.add(new Hint(UIKeys.STRUCTURE_WAND_CLEAR.get(), Colors.WHITE, Glyph.ALT, Glyph.LMB));
            alt.add(new Hint(UIKeys.STRUCTURE_WAND_SAVE.get(), Colors.WHITE, Glyph.ALT, Glyph.RMB));

            if (face != null)
            {
                plain.add(new Hint(UIKeys.STRUCTURE_WAND_PUSH.get(), Colors.WHITE, Glyph.WHEEL));
                alt.add(new Hint(UIKeys.STRUCTURE_WAND_MOVE.get(), Colors.WHITE, Glyph.ALT, Glyph.WHEEL));
            }
        }

        int leftWidth = columnWidth(font, plain);
        int rightWidth = columnWidth(font, alt);
        int total = leftWidth + (rightWidth == 0 ? 0 : COLUMN_GAP + rightWidth);
        int rows = Math.max(plain.size(), alt.size());

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        /* Bottom edge clear of the hotbar and the health rows; the block grows upwards */
        int y = height - 54 - rows * ROW;
        int x = (width - total) / 2;

        renderStatus(batcher, font, ready, width, y - 13);

        for (int i = 0; i < rows; i++)
        {
            if (i < plain.size())
            {
                plain.get(i).render(batcher, x, y + i * ROW);
            }

            if (i < alt.size())
            {
                alt.get(i).render(batcher, x + leftWidth + COLUMN_GAP, y + i * ROW);
            }
        }
    }

    private static int columnWidth(FontRenderer font, List<Hint> hints)
    {
        int width = 0;

        for (Hint hint : hints)
        {
            width = Math.max(width, hint.width(font));
        }

        return width;
    }

    /** The line above the columns: the box's size once there is one, the lone corner before that. */
    private static void renderStatus(Batcher2D batcher, FontRenderer font, boolean ready, int width, int y)
    {
        String status;
        int statusColor = Colors.WHITE;
        String detail = null;

        if (ready)
        {
            BlockPos size = StructureSelection.getSize();

            status = size.getX() + " × " + size.getY() + " × " + size.getZ();
            detail = UIKeys.STRUCTURE_WAND_BLOCKS.format(String.valueOf(StructureSelection.getVolume())).get();
        }
        else if (!StructureSelection.isEmpty())
        {
            BlockPos corner = StructureSelection.getA() != null ? StructureSelection.getA() : StructureSelection.getB();

            status = (StructureSelection.getA() != null ? "A" : "B") + "  " + corner.getX() + "  " + corner.getY() + "  " + corner.getZ();
            statusColor = StructureSelection.getA() != null ? COLOR_A : COLOR_B;
        }
        else
        {
            return;
        }

        String line = detail == null ? status : status + "   ·   " + detail;
        int lineX = (width - font.getWidth(line)) / 2;

        batcher.textShadow(status, lineX, y, statusColor);

        if (detail != null)
        {
            batcher.textShadow("   ·   " + detail, lineX + font.getWidth(status), y, Colors.LIGHTER_GRAY);
        }
    }
}
