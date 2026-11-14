package mchorse.bbs_mod.ui.framework.elements.input.keyframes;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.DoubleClick;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.overlays.UIKeyframeLoopOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoops;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Timeline loop actions shared by all keyframe editors. */
public final class UIKeyframeLoops
{
    private final UIKeyframes view;
    private final MouseGestureOwnership ownership = new MouseGestureOwnership();
    private final DoubleClick<String> doubleClick = new DoubleClick<>(false);
    private final Map<KeyframeChannel<?>, BaseType> before = new LinkedHashMap<>();
    private KeyframeChannel<?> selectedChannel;
    private String selectedId;
    private float pressTick;
    private float originalEnd;
    private boolean resizing;

    public UIKeyframeLoops(UIKeyframes view)
    {
        this.view = view;
    }

    /** Adds loop create/remove actions to the timeline context menu. */
    public void menu(ContextMenuManager menu, UIContext context)
    {
        List<KeyframeChannel<?>> channels = new ArrayList<>();
        float start = Float.POSITIVE_INFINITY;
        float end = Float.NEGATIVE_INFINITY;

        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (!sheet.selection.hasAny()) continue;
            channels.add(sheet.channel);
            for (Keyframe<?> keyframe : sheet.selection.getSelected())
            {
                start = Math.min(start, keyframe.getTick());
                end = Math.max(end, keyframe.getTick());
            }
        }

        boolean complete = true;
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (!sheet.selection.hasAny()) continue;
            for (Object entry : sheet.channel.getKeyframes())
            {
                Keyframe<?> keyframe = (Keyframe<?>) entry;
                if (keyframe.getTick() >= start && keyframe.getTick() <= end && !sheet.selection.has(keyframe)) complete = false;
            }
        }

        if (complete && KeyframeLoops.canCreate(channels, start, end))
        {
            float sourceStart = start;
            float sourceEnd = end;
            menu.action(Icons.REFRESH, L10n.lang("bbs.ui.keyframes.loop.create"), () ->
            {
                this.edit(channels, () -> KeyframeLoops.create(channels, sourceStart, sourceEnd), true);
            });
        }

        UIKeyframeSheet hovered = this.view.getGraph().getSheet(context.mouseY);
        if (hovered != null)
        {
            for (Object entry : hovered.channel.getLoops())
            {
                KeyframeLoop loop = (KeyframeLoop) entry;
                String id = loop.id();
                menu.action(Icons.EDIT, L10n.lang("bbs.ui.keyframes.loop.edit"), () -> this.open(hovered.channel, id));
                menu.action(Icons.REMOVE, L10n.lang("bbs.ui.keyframes.loop.remove"), () ->
                {
                    List<KeyframeChannel<?>> members = this.members(hovered.channel, id);
                    this.edit(members, () -> KeyframeLoops.remove(members, id), true);
                });
            }
        }
    }

    public void open(KeyframeChannel<?> channel, String id)
    {
        UIOverlay.addOverlay(this.view.getContext(), new UIKeyframeLoopOverlay(this, channel, id), 240, 164);
    }

    public void setEnd(KeyframeChannel<?> channel, String id, float end)
    {
        List<KeyframeChannel<?>> members = this.members(channel, id);
        this.edit(members, () -> KeyframeLoops.resize(members, id, end), false);
    }

    private List<KeyframeChannel<?>> members(KeyframeChannel<?> channel, String id)
    {
        List<KeyframeChannel<?>> members = KeyframeLoops.members(channel, id);

        /* Bound channels already have an owner scope; identical IDs in copied owners stay independent. */
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (channel != null && channel.getParent() == null && sheet.channel.getParent() == null
                && sheet.channel.getLoop(id) != null && members.stream().noneMatch(member -> member == sheet.channel))
            {
                members.add(sheet.channel);
            }
        }

        if (members.isEmpty() && channel != null && channel.getLoop(id) != null)
        {
            members.add(channel);
        }

        return members;
    }

    private void edit(List<KeyframeChannel<?>> channels, Runnable action, boolean discrete)
    {
        for (KeyframeChannel<?> channel : channels) channel.preNotify(discrete ? IValueListener.FLAG_UNMERGEABLE : 0);
        action.run();
        for (KeyframeChannel<?> channel : channels) channel.postNotify();
        this.view.triggerChange();
    }

    public boolean isDragging()
    {
        return !this.before.isEmpty();
    }

    private int rowY(UIKeyframeSheet sheet)
    {
        return this.view.getGraph() == this.view.getDopeSheet()
            ? this.view.getDopeSheet().getDopeSheetY(sheet) : this.view.area.y + IUIKeyframeGraph.TOP_MARGIN;
    }

    private int rowHeight()
    {
        return this.view.getGraph() == this.view.getDopeSheet() ? (int) this.view.getDopeSheet().getTrackHeight() : 16;
    }

    public boolean mouseClicked(UIContext context)
    {
        if (context.mouseButton != 0 || !this.view.graphArea.isInside(context)
            || context.mouseY < this.view.area.y + IUIKeyframeGraph.TOP_MARGIN) return false;
        UIKeyframeSheet sheet = this.view.getGraph().getSheet(context.mouseY);
        if (sheet == null) return false;

        for (Object entry : sheet.channel.getLoops())
        {
            KeyframeLoop loop = (KeyframeLoop) entry;
            int start = this.view.toGraphX(loop.start());
            int end = this.view.toGraphX(sheet.channel.getLoopEnd(loop));
            int y = this.rowY(sheet);
            int height = this.rowHeight();
            boolean edge = Math.abs(context.mouseX - end) <= 5;
            boolean bar = context.mouseY >= y + height - 6 && context.mouseY < y + height;
            if (context.mouseX < start || context.mouseX > end + 5) continue;
            if (!edge && !bar)
            {
                if (this.pickGhost(context, sheet, loop)) return true;
                continue;
            }

            this.view.getGraph().clearSelection();
            this.selectedChannel = sheet.channel;
            this.selectedId = loop.id();
            if (this.doubleClick.hit(loop.id()))
            {
                this.open(sheet.channel, loop.id());
                return true;
            }
            if (this.ownership.acquireToken(0) == 0L) return false;
            this.pressTick = (float) this.view.fromGraphX(context.mouseX);
            this.originalEnd = sheet.channel.getLoopEnd(loop);
            this.resizing = edge;
            try
            {
                for (KeyframeChannel<?> member : this.members(sheet.channel, loop.id()))
                {
                    this.before.put(member, member.toData());
                    member.preNotify(IValueListener.FLAG_UNMERGEABLE);
                }
            }
            catch (RuntimeException | Error exception)
            {
                this.ownership.cancel();
                this.before.clear();
                throw exception;
            }
            return true;
        }
        this.selectedId = null;
        return false;
    }

    private boolean pickGhost(UIContext context, UIKeyframeSheet sheet, KeyframeLoop loop)
    {
        float tick = (float) this.view.fromGraphX(context.mouseX);
        if (tick <= loop.sourceEnd() || tick > sheet.channel.getLoopEnd(loop)) return false;
        float local = loop.sourceTick(tick);
        for (Object entry : sheet.channel.getKeyframes())
        {
            Keyframe<?> key = (Keyframe<?>) entry;
            int y = this.view.getGraph() instanceof UIKeyframeGraph graph ? graph.toGraphY(key.getY(0)) : this.rowY(sheet) + this.rowHeight() / 2;
            if (loop.containsSource(key.getTick()) && Math.abs(this.view.toGraphX(local) - this.view.toGraphX(key.getTick())) <= 5
                && Math.abs(context.mouseY - y) <= 5)
            {
                this.view.getGraph().clearSelection();
                sheet.selection.add(key);
                this.selectedId = null;
                this.view.pickKeyframe(key);
                return true;
            }
        }
        return false;
    }

    public void update(UIContext context)
    {
        if (!this.isDragging()) return;
        float delta = (float) this.view.fromGraphX(context.mouseX) - this.pressTick;
        if (!Window.isAltPressed()) delta = Math.round(delta);
        List<KeyframeChannel<?>> channels = new ArrayList<>(this.before.keySet());
        final float amount = delta;
        this.edit(channels, () ->
        {
            for (Map.Entry<KeyframeChannel<?>, BaseType> entry : this.before.entrySet()) entry.getKey().fromData(entry.getValue());
            if (this.resizing)
            {
                float end = this.originalEnd + amount;
                KeyframeLoop loop = this.selectedChannel.getLoop(this.selectedId);
                if (loop != null && Window.isCtrlPressed()) end = loop.start() + Math.max(1, Math.round((end - loop.start()) / loop.period())) * loop.period();
                KeyframeLoops.resize(channels, this.selectedId, end);
            }
            else KeyframeLoops.move(channels, this.selectedId, amount);
        }, false);
    }

    public boolean mouseReleased(UIContext context)
    {
        if (!this.isDragging() || context.mouseButton != 0) return false;
        this.ownership.cancel();
        List<KeyframeChannel<?>> channels = new ArrayList<>(this.before.keySet());
        this.before.clear();
        for (KeyframeChannel<?> channel : channels) channel.preNotify(IValueListener.FLAG_UNMERGEABLE);
        return true;
    }

    public void cancel(UIContext context)
    {
        if (!this.isDragging() || context.mouseButton != 0) return;
        this.cancelGesture();
    }

    private void cancelGesture()
    {
        this.ownership.cancel();
        Map<KeyframeChannel<?>, BaseType> saved = new LinkedHashMap<>(this.before);
        this.before.clear();
        Throwable failure = null;
        for (KeyframeChannel<?> channel : saved.keySet())
        {
            failure = runCancelStep(failure, channel::preNotify);
        }
        for (Map.Entry<KeyframeChannel<?>, BaseType> entry : saved.entrySet())
        {
            failure = runCancelStep(failure, () -> entry.getKey().fromData(entry.getValue()));
        }
        for (KeyframeChannel<?> channel : saved.keySet())
        {
            failure = runCancelStep(failure, channel::postNotify);
        }
        failure = runCancelStep(failure, this.view::triggerChange);
        for (KeyframeChannel<?> channel : saved.keySet())
        {
            failure = runCancelStep(failure, () -> channel.preNotify(IValueListener.FLAG_UNMERGEABLE));
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private static Throwable runCancelStep(Throwable failure, Runnable action)
    {
        try
        {
            action.run();
        }
        catch (RuntimeException | Error exception)
        {
            if (failure == null) return exception;
            if (failure != exception) failure.addSuppressed(exception);
        }
        return failure;
    }

    public boolean keyPressed(UIContext context)
    {
        if (this.isDragging())
        {
            if (context.isPressed(GLFW.GLFW_KEY_ESCAPE)) this.cancelGesture();
            return true;
        }
        if (this.selectedId != null && this.selectedChannel != null && this.view.area.isInside(context)
            && this.view.getGraph().getSelected() == null && context.isPressed(GLFW.GLFW_KEY_DELETE))
        {
            List<KeyframeChannel<?>> members = this.members(this.selectedChannel, this.selectedId);
            this.edit(members, () -> KeyframeLoops.remove(members, this.selectedId), true);
            this.selectedId = null;
            return true;
        }
        return false;
    }

    public void render(UIContext context)
    {
        context.batcher.clip(this.view.graphArea, context);
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            int y = this.rowY(sheet);
            int height = this.rowHeight();
            if (this.view.getGraph() == this.view.getDopeSheet()
                && this.view.getGraph().getSheet(y + height / 2) != sheet) continue;
            for (Object entry : sheet.channel.getLoops())
            {
                KeyframeLoop loop = (KeyframeLoop) entry;
                int x = this.view.toGraphX(loop.start());
                int sourceEnd = this.view.toGraphX(loop.sourceEnd());
                int end = this.view.toGraphX(sheet.channel.getLoopEnd(loop));
                boolean selected = sheet.channel == this.selectedChannel && loop.id().equals(this.selectedId);
                int color = selected ? BBSSettings.highlightColor() : BBSSettings.primaryColor(Colors.A100);
                context.batcher.box(x, y + 1, end, y + height - 1, Colors.setA(color, 0.12F));
                context.batcher.box(x, y + height - 5, end, y + height - 1, Colors.setA(color, 0.7F));
                context.batcher.box(end - 2, y + 1, end + 2, y + height - 1, color);
                context.batcher.box(sourceEnd, y + 1, sourceEnd + 1, y + height - 1, color);
                this.renderGhosts(context, sheet, loop, y + height / 2);
            }
        }
        context.batcher.unclip(context);
    }

    private void renderGhosts(UIContext context, UIKeyframeSheet sheet, KeyframeLoop loop, int rowY)
    {
        double period = loop.period();
        double firstTick = this.view.fromGraphX(this.view.graphArea.x - 6);
        double lastTick = Math.min(sheet.channel.getLoopEnd(loop), this.view.fromGraphX(this.view.graphArea.ex() + 6));
        double pixelPeriod = Math.abs(this.view.toGraphX(loop.sourceEnd()) - this.view.toGraphX(loop.start()));
        double stride = Math.max(1D, Math.ceil(8D / Math.max(0.001D, pixelPeriod)));
        context.batcher.flush();
        Matrix4f matrix = context.batcher.getContext().pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int drawn = 0;
        for (Object entry : sheet.channel.getKeyframes())
        {
            Keyframe<?> key = (Keyframe<?>) entry;
            if (!loop.containsSource(key.getTick())) continue;
            double firstPass = Math.max(1D, Math.ceil((firstTick - key.getTick()) / period));
            int y = this.view.getGraph() instanceof UIKeyframeGraph graph ? graph.toGraphY(key.getY(0)) : rowY;
            for (double pass = firstPass; drawn < 3000; pass += stride)
            {
                double tick = key.getTick() + pass * period;
                if (tick > lastTick) break;
                if (tick <= loop.sourceEnd()) continue;
                UIKeyframeDopeSheet.renderShape(key, context, builder, matrix, this.view.toGraphX(tick), y, 3, Colors.setA(sheet.color, 0.35F));
                drawn++;
            }
        }
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    public void renderStatus(UIContext context)
    {
        if (!this.isDragging()) return;
        KeyframeLoop loop = this.selectedChannel.getLoop(this.selectedId);
        if (loop == null) return;
        context.requestCursor(GLFW.GLFW_HRESIZE_CURSOR);
        String label = L10n.lang("bbs.ui.keyframes.loop.status").format(
            String.format(Locale.ROOT, "%.2f", loop.end()),
            String.format(Locale.ROOT, "%.2f", loop.passes())).get();
        int width = context.batcher.getFont().getWidth(label);
        int height = context.batcher.getFont().getHeight();
        int x = Math.max(this.view.area.x + 4, Math.min(context.mouseX + 12, this.view.area.ex() - width - 4));
        int y = context.mouseY + 12;
        if (y + height + 4 > this.view.area.ey()) y = context.mouseY - height - 12;
        context.batcher.textCard(label, x, Math.max(this.view.area.y + 4, y), BBSSettings.textColor(), BBSSettings.raisedSurface());
    }
}
