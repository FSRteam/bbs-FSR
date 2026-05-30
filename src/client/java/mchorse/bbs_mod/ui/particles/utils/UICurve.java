package mchorse.bbs_mod.ui.particles.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.graphics.line.LineBuilder;
import mchorse.bbs_mod.graphics.line.SolidColorLineRenderer;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.particles.ParticleCurve;
import mchorse.bbs_mod.particles.ParticleCurve.BezierChainNode;
import mchorse.bbs_mod.particles.ParticleCurveType;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeSection;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.MathUtils;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector2d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UICurve extends UIElement
{
    private static final double MIN_VIEW_ZOOM = 0.1D;
    private static final double MAX_VIEW_ZOOM = 100D;

    private UIParticleSchemeSection section;
    private UITrackpad value;

    private Area graph = new Area();
    private ParticleCurve curve;
    private int index = -1;
    private boolean dragging;
    private boolean moving;
    private int lastX;
    private int lastY;
    private boolean panning;
    private double viewOffsetX;
    private double viewOffsetY;
    private double viewZoomX = 1D;
    private double viewZoomY = 1D;
    private double panOffsetX;
    private double panOffsetY;

    /* Bezier chain: selected node key (-1 = none, -2 = cp_out of prev, -3 = cp_in of next) */
    private float chainSelectedKey = -1;
    private boolean chainDraggingCPOut;
    private boolean chainDraggingCPIn;

    /* x = min, y = max */
    private Vector2d range = new Vector2d();

    public UICurve(UIParticleSchemeSection section)
    {
        this.section = section;

        this.value = new UITrackpad((v) ->
        {
            this.curve.nodes.set(this.index, new MolangValue(null, new Constant(v)));
            this.section.dirty();
            this.updateRange();
        });
        this.value.relative(this).y(1F, -20).w(1F);

        this.add(this.value);

        this.context((menu) ->
        {
            menu.action(Icons.ADD, UIKeys.SNOWSTORM_CURVES_CONTEXT_ADD, this::addPoint);

            if (this.index >= 0)
            {
                menu.action(Icons.REMOVE, UIKeys.SNOWSTORM_CURVES_CONTEXT_REMOVE, this::removePoint);
            }
        });
    }

    private void addPoint()
    {
        if (this.curve.type == ParticleCurveType.BEZIER)
        {
            /* Bezier always has exactly 4 nodes — cannot add more */
            return;
        }

        int index = this.index + 1;

        if (index < this.curve.nodes.size())
        {
            this.curve.nodes.add(index, MolangParser.ZERO);
            this.setIndex(index);
        }
        else
        {
            this.curve.nodes.add(MolangParser.ZERO);
            this.setIndex(this.curve.nodes.size() - 1);
        }

        this.section.dirty();
    }

    private void removePoint()
    {
        if (this.index < 0)
        {
            return;
        }

        if (this.curve.type == ParticleCurveType.BEZIER)
        {
            /* Bezier always has exactly 4 nodes — cannot remove */
            return;
        }

        this.curve.nodes.remove(this.index);
        this.setIndex(this.index - 1);
        this.section.dirty();
    }

    public void fill(ParticleCurve curve)
    {
        this.curve = curve;

        this.setIndex(-1);
        this.updateRange();
    }

    private void setIndex(int i)
    {
        this.index = i;

        boolean isValid = i >= 0 && i < this.curve.nodes.size();

        this.value.setVisible(true);
        this.value.setEnabled(isValid);

        if (isValid)
        {
            this.value.setValue(this.curve.nodes.get(i).get());
        }
    }

    private Vector2d getVector(int index, double min, double max)
    {
        index = MathUtils.clamp(index, 0, this.curve.nodes.size() - 1);

        MolangExpression expression = this.curve.nodes.get(index);
        double value = expression.get();
        double x = index / (float) (this.curve.nodes.size() - 1);

        return this.toGraph(x, value);
    }

    private Vector2d toGraph(double x, double value)
    {
        double factor = (value - this.range.x) / this.getRangeSize();
        int graphX = this.graph.x + (int) Math.round((x - this.viewOffsetX) * this.viewZoomX * this.graph.w);
        int graphY = this.graph.y + (int) Math.round((1 - (factor - this.viewOffsetY) * this.viewZoomY) * this.graph.h);

        return new Vector2d(graphX, graphY);
    }

    private double fromGraphX(int mouseX)
    {
        if (this.graph.w <= 0)
        {
            return 0D;
        }

        return (mouseX - this.graph.x) / (double) this.graph.w / this.viewZoomX + this.viewOffsetX;
    }

    private double fromGraphYFactor(int mouseY)
    {
        if (this.graph.h <= 0)
        {
            return 0D;
        }

        return (1 - (mouseY - this.graph.y) / (double) this.graph.h) / this.viewZoomY + this.viewOffsetY;
    }

    private double fromGraphY(int mouseY)
    {
        return this.range.x + this.fromGraphYFactor(mouseY) * this.getRangeSize();
    }

    private double getRangeSize()
    {
        double size = this.range.y - this.range.x;

        return Math.abs(size) < 0.000001D ? 1D : size;
    }

    /**
     * Get the screen position of a bezier control point.
     * For BEZIER curves, the 4 nodes map to P0(start), P1(cp1), P2(cp2), P3(end).
     * P0 is at x=0, P3 is at x=1 (full graph width).
     * P1 and P2 have freely draggable X positions stored in bezierCP1X/bezierCP2X (0..1).
     */
    private Vector2d getBezierPoint(int i, double min, double max)
    {
        double value = this.curve.nodes.get(i).get();
        double factor = 1 - (value - min) / (max - min);

        double xPos;
        if (i == 0) xPos = 0;
        else if (i == 3) xPos = 1;
        else if (i == 1) xPos = this.curve.bezierCP1X;
        else xPos = this.curve.bezierCP2X;

        return this.toGraph(xPos, value);
    }

    private void updateRange()
    {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        if (this.curve.type == ParticleCurveType.BEZIER_CHAIN)
        {
            for (BezierChainNode node : this.curve.bezierChainNodes.values())
            {
                min = Math.min(min, Math.min(node.leftValue, node.rightValue));
                max = Math.max(max, Math.max(node.leftValue, node.rightValue));
            }
        }
        else
        {
            for (int i = 0; i < this.curve.nodes.size(); i++)
            {
                MolangExpression expression = this.curve.nodes.get(i);
                double value = expression.get();

                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        if (min == Double.POSITIVE_INFINITY) { min = 0; max = 1; }
        else if (min == max) { min -= 1; max += 1; }

        this.range.set(min, max);
    }

    @Override
    public void resize()
    {
        super.resize();

        this.graph.copy(this.area);
        this.graph.x += 10;
        this.graph.y += 10;
        this.graph.w -= 20;
        this.graph.h -= 40;
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.graph.isInside(context) && context.mouseButton == 2)
        {
            this.panning = true;
            this.lastX = context.mouseX;
            this.lastY = context.mouseY;
            this.panOffsetX = this.viewOffsetX;
            this.panOffsetY = this.viewOffsetY;

            return true;
        }

        if (this.area.isInside(context) && context.mouseButton == 0)
        {
            boolean ctrl = Window.isCtrlPressed();

            if (this.curve.type == ParticleCurveType.BEZIER)
            {
                /* Bezier mode: ctrl+click to create/delete control points */
                return this.bezierMouseClicked(context, ctrl);
            }
            else if (this.curve.type == ParticleCurveType.BEZIER_CHAIN)
            {
                return this.bezierChainMouseClicked(context, ctrl);
            }

            for (int i = 0; i < this.curve.nodes.size(); i++)
            {
                Vector2d point = this.getVector(i, this.range.x, this.range.y);

                double dx = point.x - context.mouseX;
                double dy = point.y - context.mouseY;
                double d = dx * dx + dy * dy;

                if (d <= 25)
                {
                    this.setIndex(i);

                    this.dragging = true;
                    this.lastX = context.mouseX;
                    this.lastY = context.mouseY;

                    return true;
                }
            }

            this.setIndex(-1);

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.graph.isInside(context) && !this.dragging && !this.panning && context.mouseWheel != 0D)
        {
            if (this.graph.w <= 0 || this.graph.h <= 0)
            {
                return true;
            }

            double x = this.fromGraphX(context.mouseX);
            double y = this.fromGraphYFactor(context.mouseY);
            double factor = context.mouseWheel > 0 ? 1.1D : 1D / 1.1D;
            double anchorX = (context.mouseX - this.graph.x) / (double) this.graph.w;
            double anchorY = 1D - (context.mouseY - this.graph.y) / (double) this.graph.h;

            this.viewZoomX = MathUtils.clamp(this.viewZoomX * factor, MIN_VIEW_ZOOM, MAX_VIEW_ZOOM);
            this.viewZoomY = MathUtils.clamp(this.viewZoomY * factor, MIN_VIEW_ZOOM, MAX_VIEW_ZOOM);
            this.viewOffsetX = x - anchorX / this.viewZoomX;
            this.viewOffsetY = y - anchorY / this.viewZoomY;

            return true;
        }

        return super.subMouseScrolled(context);
    }

    /**
     * Handle mouse clicks for bezier curve mode.
     * Ctrl+click on a control point: remove it (if more than 4 nodes)
     * Regular click: select and drag
     */
    private boolean bezierMouseClicked(UIContext context, boolean ctrl)
    {
        int c = this.curve.nodes.size();

        /* Check if clicking on an existing point */
        for (int i = 0; i < c; i++)
        {
            Vector2d point = this.getBezierPoint(i, this.range.x, this.range.y);

            double dx = point.x - context.mouseX;
            double dy = point.y - context.mouseY;
            double d = dx * dx + dy * dy;

            if (d <= 36)
            {
                if (ctrl && i != 0 && i != 3 && c > 4)
                {
                    /* Ctrl+click on a control point to remove it */
                    this.curve.nodes.remove(i);

                    /* Re-index remaining points: first and last are always endpoints */
                    this.setIndex(-1);
                    this.section.dirty();
                    this.updateRange();

                    return true;
                }

                this.setIndex(i);

                this.dragging = true;
                this.lastX = context.mouseX;
                this.lastY = context.mouseY;

                return true;
            }
        }

        this.setIndex(-1);

        return true;
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (this.moving)
        {
            this.updateRange();
        }

        this.dragging = false;
        this.moving = false;
        this.panning = false;

        return super.subMouseReleased(context);
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, Colors.A50);

        if (this.curve != null)
        {
            this.handleDragging(context);

            context.batcher.clip(this.area, context);
            this.drawGraph(context);
            context.batcher.unclip(context);
        }

        super.render(context);
    }

    private void handleDragging(UIContext context)
    {
        if (this.panning)
        {
            if (this.graph.w > 0 && this.graph.h > 0)
            {
                this.viewOffsetX = this.panOffsetX - (context.mouseX - this.lastX) / (double) this.graph.w / this.viewZoomX;
                this.viewOffsetY = this.panOffsetY + (context.mouseY - this.lastY) / (double) this.graph.h / this.viewZoomY;
            }

            return;
        }

        if (this.dragging && !this.moving)
        {
            int dx = context.mouseX - this.lastX;
            int dy = context.mouseY - this.lastY;
            int d = dx * dx + dy * dy;

            if (d > 9)
            {
                this.moving = true;
            }
        }

        if (this.moving && this.index >= 0)
        {
            if (this.curve.type == ParticleCurveType.BEZIER)
            {
                this.handleBezierDragging(context);
            }
            else
            {
                double value = this.fromGraphY(context.mouseY);

                this.curve.nodes.set(this.index, new MolangValue(null, new Constant(value)));
                this.value.setValue(value);
                this.section.dirty();
            }
        }
        else if (this.moving && this.chainSelectedKey >= 0 && this.curve.type == ParticleCurveType.BEZIER_CHAIN)
        {
            this.handleBezierChainDragging(context);
        }
    }

    /**
     * Handle dragging for bezier control points.
     * For P1/P2 (control points), dragging changes both X position and Y value.
     * For P0/P3 (endpoints), dragging only changes Y value.
     */
    private void handleBezierDragging(UIContext context)
    {
        double value = this.fromGraphY(context.mouseY);

        this.curve.nodes.set(this.index, new MolangValue(null, new Constant(value)));

        /* For control points (P1, P2), also update X position */
        if (this.index == 1)
        {
            this.curve.bezierCP1X = (float) MathUtils.clamp(
                this.fromGraphX(context.mouseX), 0.01, 0.99);
        }
        else if (this.index == 2)
        {
            this.curve.bezierCP2X = (float) MathUtils.clamp(
                this.fromGraphX(context.mouseX), 0.01, 0.99);
        }

        this.value.setValue(value);
        this.section.dirty();
    }

    private void drawGraph(UIContext context)
    {
        Matrix4f matrix = context.batcher.getContext().pose().last().pose();
        int c = this.curve.nodes.size();

        BufferBuilder builder;

        builder = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        /* Top and bottom */
        builder.addVertex(matrix, this.area.x, this.graph.y, 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);
        builder.addVertex(matrix, this.area.ex(), this.graph.y, 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);

        builder.addVertex(matrix, this.area.x, this.graph.ey(), 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);
        builder.addVertex(matrix, this.area.ex(), this.graph.ey(), 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);

        /* Left and right */
        builder.addVertex(matrix, this.graph.x, this.area.y, 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);
        builder.addVertex(matrix, this.graph.x, this.area.ey(), 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);

        builder.addVertex(matrix, this.graph.ex(), this.area.y, 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);
        builder.addVertex(matrix, this.graph.ex(), this.area.ey(), 0F).setColor(0.5F, 0.5F, 0.5F, 0.5F);

        if (this.curve.type == ParticleCurveType.HERMITE && c >= 4)
        {
            Vector2d first = this.getVector(1, this.range.x, this.range.y);
            Vector2d last = this.getVector(c - 2, this.range.x, this.range.y);

            /* Hermite bounds */
            builder.addVertex(matrix, (float) first.x, this.graph.y, 0F).setColor(0.25F, 0.25F, 0.25F, 0.5F);
            builder.addVertex(matrix, (float) first.x, this.graph.ey(), 0F).setColor(0.25F, 0.25F, 0.25F, 0.5F);

            builder.addVertex(matrix, (float) last.x, this.graph.y, 0F).setColor(0.25F, 0.25F, 0.25F, 0.5F);
            builder.addVertex(matrix, (float) last.x, this.graph.ey(), 0F).setColor(0.25F, 0.25F, 0.25F, 0.5F);
        }

        BufferUploader.drawWithShader(builder.buildOrThrow());

        Color color = Colors.COLOR;
        LineBuilder line = new LineBuilder(0.75F);

        color.set(BBSSettings.primaryColor.get(), false);

        if (this.curve.type == ParticleCurveType.BEZIER && c >= 4)
        {
            this.drawBezierCurve(line);
        }
        else if (this.curve.type == ParticleCurveType.BEZIER_CHAIN && !this.curve.bezierChainNodes.isEmpty())
        {
            this.drawBezierChainCurve(line);
        }
        else
        {
            for (int i = 0; i < c; i++)
            {
                Vector2d v1 = this.getVector(i, this.range.x, this.range.y);
                Vector2d v2 = this.getVector(i + 1, this.range.x, this.range.y);
                boolean last = i == c - 1;

                if (this.curve.type == ParticleCurveType.LINEAR)
                {
                    line.add((float) v1.x, (float) v1.y);

                    if (last)
                    {
                        line.add((float) v2.x, (float) v2.y);
                    }
                }
                else
                {
                    Vector2d v0 = this.getVector(i - 1, this.range.x, this.range.y);
                    Vector2d v3 = this.getVector(i + 2, this.range.x, this.range.y);
                    final double d = 5;

                    for (int j = 0; j < d; j++)
                    {
                        int x1 = (int) Lerps.lerp(v1.x, v2.x, j / d);
                        int vy1 = (int) Lerps.cubicHermite(v0.y, v1.y, v2.y, v3.y, j / d);

                        line.add(x1, vy1);

                        if (last)
                        {
                            int x2 = (int) Lerps.lerp(v1.x, v2.x, (j + 1) / d);
                            int vy2 = (int) Lerps.cubicHermite(v0.y, v1.y, v2.y, v3.y, (j + 1) / d);

                            line.add(x2, vy2);
                        }
                    }
                }
            }
        }

        line.render(context.batcher, SolidColorLineRenderer.get(color.r, color.g, color.b, 1F));

        /* Draw control point handles */
        if (this.curve.type == ParticleCurveType.BEZIER && c >= 4)
        {
            this.drawBezierHandles(context, matrix);
        }
        else if (this.curve.type == ParticleCurveType.BEZIER_CHAIN && !this.curve.bezierChainNodes.isEmpty())
        {
            this.drawBezierChainHandles(context, matrix);
        }
        else
        {
            for (int i = 0; i < c; i++)
            {
                Vector2d vector = this.getVector(i, this.range.x, this.range.y);
                int x = (int) vector.x;
                int y = (int) vector.y;

                context.batcher.box(x - 3, y - 3, x + 3, y + 3, this.index == i ? Colors.setA(Colors.ACTIVE, 1F) : Colors.WHITE);
                context.batcher.box(x - 2, y - 2, x + 2, y + 2, Colors.A100);
            }
        }
    }

    /**
     * Draw the cubic bezier curve using all segments.
     * Each segment is defined by 4 consecutive nodes: [P0, P1, P2, P3].
     * P0 and P3 are endpoints (fixed X), P1 and P2 are control points (free X).
     */
    private void drawBezierCurve(LineBuilder line)
    {
        int segments = this.curve.nodes.size() / 4;

        for (int seg = 0; seg < segments; seg++)
        {
            int base = seg * 4;
            Vector2d p0 = this.getBezierPoint(base, this.range.x, this.range.y);
            Vector2d p1 = this.getBezierPoint(base + 1, this.range.x, this.range.y);
            Vector2d p2 = this.getBezierPoint(base + 2, this.range.x, this.range.y);
            Vector2d p3 = this.getBezierPoint(base + 3, this.range.x, this.range.y);

            final int steps = 20;

            for (int j = 0; j <= steps; j++)
            {
                float t = (float) j / steps;
                int x = (int) Lerps.bezier(p0.x, p1.x, p2.x, p3.x, t);
                int y = (int) Lerps.bezier(p0.y, p1.y, p2.y, p3.y, t);

                line.add(x, y);
            }
        }
    }

    /**
     * Draw bezier control point handles: endpoints as filled squares,
     * control points as hollow diamonds with lines to their anchor endpoint.
     */
    private void drawBezierHandles(UIContext context, Matrix4f matrix)
    {
        int segments = this.curve.nodes.size() / 4;

        for (int seg = 0; seg < segments; seg++)
        {
            int base = seg * 4;

            for (int i = 0; i < 4; i++)
            {
                int nodeIdx = base + i;
                Vector2d pt = this.getBezierPoint(nodeIdx, this.range.x, this.range.y);
                int x = (int) pt.x;
                int y = (int) pt.y;
                boolean selected = this.index == nodeIdx;

                if (i == 1 || i == 2)
                {
                    /* Control points: diamond shape with connecting line to endpoint */
                    int anchorIdx = (i == 1) ? base : base + 3;
                    Vector2d anchor = this.getBezierPoint(anchorIdx, this.range.x, this.range.y);

                    /* Draw connecting line from endpoint to control point */
                    BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                    builder.addVertex(matrix, (float) anchor.x, (float) anchor.y, 0F).setColor(0.6F, 0.6F, 0.6F, 0.7F);
                    builder.addVertex(matrix, x, y, 0F).setColor(0.6F, 0.6F, 0.6F, 0.7F);
                    BufferUploader.drawWithShader(builder.buildOrThrow());

                    /* Diamond: rotated square for control points */
                    int s = 4;
                    int color = selected ? Colors.setA(Colors.ACTIVE, 1F) : Colors.GRAY;
                    context.batcher.box(x - s, y - 1, x + s, y + 1, color);
                    context.batcher.box(x - 1, y - s, x + 1, y + s, color);
                }
                else
                {
                    /* Endpoints (P0, P3): filled squares */
                    int color = selected ? Colors.setA(Colors.ACTIVE, 1F) : Colors.WHITE;
                    context.batcher.box(x - 4, y - 4, x + 4, y + 4, color);
                    context.batcher.box(x - 3, y - 3, x + 3, y + 3, Colors.A100);
                }
            }
        }
    }

    /* ==================== Bezier Chain Methods ==================== */

    private static final int HANDLE_OFFSET = 30;

    /**
     * Get the screen position of a bezier chain anchor node.
     */
    private Vector2d getChainAnchorPos(float time, float value, double min, double max)
    {
        return this.toGraph(time, value);
    }

    /**
     * Get screen position of an outgoing control handle (right side of a node).
     * Snowstorm: offset * cos(atan(right_slope)) in x, -offset * sin(atan(right_slope)) in y
     */
    private Vector2d getChainRightHandlePos(float time, float value, float slope, double min, double max)
    {
        Vector2d anchor = this.getChainAnchorPos(time, value, min, max);
        double angle = Math.atan(slope);
        double dx = HANDLE_OFFSET * Math.cos(angle);
        double dy = -HANDLE_OFFSET * Math.sin(angle);
        return new Vector2d(anchor.x + dx, anchor.y + dy);
    }

    /**
     * Get screen position of an incoming control handle (left side of a node).
     * Snowstorm: -offset * cos(atan(left_slope)) in x, offset * sin(atan(left_slope)) in y
     */
    private Vector2d getChainLeftHandlePos(float time, float value, float slope, double min, double max)
    {
        Vector2d anchor = this.getChainAnchorPos(time, value, min, max);
        double angle = Math.atan(slope);
        double dx = -HANDLE_OFFSET * Math.cos(angle);
        double dy = HANDLE_OFFSET * Math.sin(angle);
        return new Vector2d(anchor.x + dx, anchor.y + dy);
    }

    /**
     * Draw bezier chain curve using piecewise cubic bezier between sorted nodes.
     */
    private void drawBezierChainCurve(LineBuilder line)
    {
        List<Map.Entry<Float, BezierChainNode>> entries = new ArrayList<>(this.curve.bezierChainNodes.entrySet());
        if (entries.size() < 2) return;

        final int steps = 20;

        for (int i = 0; i < entries.size() - 1; i++)
        {
            Map.Entry<Float, BezierChainNode> prev = entries.get(i);
            Map.Entry<Float, BezierChainNode> next = entries.get(i + 1);

            float t0 = prev.getKey();
            float t1 = next.getKey();
            BezierChainNode n0 = prev.getValue();
            BezierChainNode n1 = next.getValue();

            double y0 = n0.rightValue;
            double y1 = n1.leftValue;
            double cp0 = y0 + n0.rightSlope * (t1 - t0);
            double cp1 = y1 - n1.leftSlope * (t1 - t0);

            for (int j = 0; j <= steps; j++)
            {
                float t = (float) j / steps;
                double globalT = t0 + t * (t1 - t0);
                double val = Lerps.bezier(y0, cp0, cp1, y1, t);
                Vector2d point = this.toGraph(globalT, val);
                line.add((int) point.x, (int) point.y);
            }
        }
    }

    /**
     * Draw bezier chain node handles following Snowstorm's design:
     * - Anchor points as filled squares at (time, value)
     * - Left handle: line from anchor going at angle atan(left_slope) to the left
     * - Right handle: line from anchor going at angle atan(right_slope) to the right
     * - Handle endpoints shown as small circles
     */
    private void drawBezierChainHandles(UIContext context, Matrix4f matrix)
    {
        List<Map.Entry<Float, BezierChainNode>> entries = new ArrayList<>(this.curve.bezierChainNodes.entrySet());

        for (int i = 0; i < entries.size(); i++)
        {
            Map.Entry<Float, BezierChainNode> entry = entries.get(i);
            float time = entry.getKey();
            BezierChainNode node = entry.getValue();
            boolean selected = this.chainSelectedKey == time;

            /* Anchor point: use left_value (or average if left != right for display) */
            float anchorVal = node.leftValue;
            Vector2d anchor = this.getChainAnchorPos(time, anchorVal, this.range.x, this.range.y);
            int ax = (int) anchor.x;
            int ay = (int) anchor.y;

            /* Draw anchor square */
            int color = selected ? Colors.setA(Colors.ACTIVE, 1F) : Colors.WHITE;
            context.batcher.box(ax - 4, ay - 4, ax + 4, ay + 4, color);
            context.batcher.box(ax - 3, ay - 3, ax + 3, ay + 3, Colors.A100);

            /* If left_value != right_value, draw a second anchor for right_value */
            if (node.leftValue != node.rightValue)
            {
                Vector2d rightAnchor = this.getChainAnchorPos(time, node.rightValue, this.range.x, this.range.y);
                int rx = (int) rightAnchor.x;
                int ry = (int) rightAnchor.y;
                context.batcher.box(rx - 4, ry - 4, rx + 4, ry + 4, selected ? Colors.setA(Colors.ACTIVE, 0.7F) : Colors.setA(Colors.WHITE, 0.7F));
                context.batcher.box(rx - 3, ry - 3, rx + 3, ry + 3, Colors.A100);
            }

            /* Left handle (only if there's a node before this one) */
            if (i > 0)
            {
                Vector2d leftHandle = this.getChainLeftHandlePos(time, anchorVal, node.leftSlope, this.range.x, this.range.y);
                int lx = (int) leftHandle.x;
                int ly = (int) leftHandle.y;

                /* Line from left handle to anchor */
                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                builder.addVertex(matrix, lx, ly, 0F).setColor(0.6F, 0.6F, 0.6F, 0.7F);
                builder.addVertex(matrix, ax, ay, 0F).setColor(0.6F, 0.6F, 0.6F, 0.7F);
                BufferUploader.drawWithShader(builder.buildOrThrow());

                /* Handle endpoint circle */
                int cpColor = (selected && this.chainDraggingCPIn) ? Colors.setA(Colors.ACTIVE, 1F) : Colors.GRAY;
                context.batcher.box(lx - 3, ly - 3, lx + 3, ly + 3, cpColor);
                context.batcher.box(lx - 2, ly - 2, lx + 2, ly + 2, Colors.A100);
            }

            /* Right handle (only if there's a node after this one) */
            if (i < entries.size() - 1)
            {
                Vector2d rightHandle = this.getChainRightHandlePos(time, anchorVal, node.rightSlope, this.range.x, this.range.y);
                int rx = (int) rightHandle.x;
                int ry = (int) rightHandle.y;

                /* Line from anchor to right handle */
                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                builder.addVertex(matrix, ax, ay, 0F).setColor(0.6F, 0.6F, 0.6F, 0.7F);
                builder.addVertex(matrix, rx, ry, 0F).setColor(0.6F, 0.6F, 0.6F, 0.7F);
                BufferUploader.drawWithShader(builder.buildOrThrow());

                /* Handle endpoint circle */
                int cpColor = (selected && this.chainDraggingCPOut) ? Colors.setA(Colors.ACTIVE, 1F) : Colors.GRAY;
                context.batcher.box(rx - 3, ry - 3, rx + 3, ry + 3, cpColor);
                context.batcher.box(rx - 2, ry - 2, rx + 2, ry + 2, Colors.A100);
            }
        }
    }

    /**
     * Handle bezier chain mouse clicks — Snowstorm style.
     */
    private boolean bezierChainMouseClicked(UIContext context, boolean ctrl)
    {
        List<Map.Entry<Float, BezierChainNode>> entries = new ArrayList<>(this.curve.bezierChainNodes.entrySet());

        for (int i = 0; i < entries.size(); i++)
        {
            Map.Entry<Float, BezierChainNode> entry = entries.get(i);
            float time = entry.getKey();
            BezierChainNode node = entry.getValue();
            float anchorVal = node.leftValue;

            /* Check anchor point */
            Vector2d anchor = this.getChainAnchorPos(time, anchorVal, this.range.x, this.range.y);
            if (dist(anchor, context) <= 36)
            {
                if (ctrl && entries.size() > 2)
                {
                    this.curve.bezierChainNodes.remove(time);
                    this.chainSelectedKey = -1;
                    this.section.dirty();
                    this.updateRange();
                    return true;
                }

                this.chainSelectedKey = time;
                this.chainDraggingCPOut = false;
                this.chainDraggingCPIn = false;
                this.dragging = true;
                this.lastX = context.mouseX;
                this.lastY = context.mouseY;
                return true;
            }

            /* Check left handle */
            if (i > 0)
            {
                Vector2d leftHandle = this.getChainLeftHandlePos(time, anchorVal, node.leftSlope, this.range.x, this.range.y);
                if (dist(leftHandle, context) <= 25)
                {
                    this.chainSelectedKey = time;
                    this.chainDraggingCPOut = false;
                    this.chainDraggingCPIn = true;
                    this.dragging = true;
                    this.lastX = context.mouseX;
                    this.lastY = context.mouseY;
                    return true;
                }
            }

            /* Check right handle */
            if (i < entries.size() - 1)
            {
                Vector2d rightHandle = this.getChainRightHandlePos(time, anchorVal, node.rightSlope, this.range.x, this.range.y);
                if (dist(rightHandle, context) <= 25)
                {
                    this.chainSelectedKey = time;
                    this.chainDraggingCPOut = true;
                    this.chainDraggingCPIn = false;
                    this.dragging = true;
                    this.lastX = context.mouseX;
                    this.lastY = context.mouseY;
                    return true;
                }
            }
        }

        /* Ctrl+click on empty space: add a new node */
        if (ctrl && this.graph.isInside(context))
        {
            double time = this.fromGraphX(context.mouseX);
            double value = this.fromGraphY(context.mouseY);
            float t = (float) MathUtils.clamp(time, 0, 1);
            float v = (float) value;

            this.curve.bezierChainNodes.put(t, new BezierChainNode(v, v, 0, 0));
            this.chainSelectedKey = t;
            this.section.dirty();
            this.updateRange();
            return true;
        }

        this.chainSelectedKey = -1;
        return true;
    }

    /**
     * Handle bezier chain dragging — Snowstorm style.
     * Dragging a handle changes the slope; dragging an anchor changes value and time.
     */
    private void handleBezierChainDragging(UIContext context)
    {
        if (this.chainSelectedKey < 0) return;

        BezierChainNode node = this.curve.bezierChainNodes.get(this.chainSelectedKey);
        if (node == null) return;

        float time = this.chainSelectedKey;

        if (this.chainDraggingCPIn)
        {
            /* Dragging left handle: adjust left_slope from mouse delta relative to anchor */
            Vector2d anchor = this.getChainAnchorPos(time, node.leftValue, this.range.x, this.range.y);
            double dx = context.mouseX - anchor.x;
            double dy = context.mouseY - anchor.y;
            if (Math.abs(dx) > 1)
            {
                node.leftSlope = (float) (dy / Math.abs(dx));
                this.section.dirty();
            }
        }
        else if (this.chainDraggingCPOut)
        {
            /* Dragging right handle: adjust right_slope from mouse delta relative to anchor */
            Vector2d anchor = this.getChainAnchorPos(time, node.leftValue, this.range.x, this.range.y);
            double dx = context.mouseX - anchor.x;
            double dy = -(context.mouseY - anchor.y);
            if (Math.abs(dx) > 1)
            {
                node.rightSlope = (float) (dy / Math.abs(dx));
                this.section.dirty();
            }
        }
        else
        {
            /* Dragging anchor: move value and time position */
            double newTime = this.fromGraphX(context.mouseX);
            double newValue = this.fromGraphY(context.mouseY);

            float oldKey = this.chainSelectedKey;
            float newKey = (float) MathUtils.clamp(newTime, 0, 1);
            float v = (float) newValue;

            this.curve.bezierChainNodes.remove(oldKey);
            node.leftValue = v;
            node.rightValue = v;
            this.curve.bezierChainNodes.put(newKey, node);
            this.chainSelectedKey = newKey;
            this.section.dirty();
        }
    }

    private double dist(Vector2d pt, UIContext context)
    {
        double dx = pt.x - context.mouseX;
        double dy = pt.y - context.mouseY;
        return dx * dx + dy * dy;
    }
}
