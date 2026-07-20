package mchorse.bbs_mod.ui.particles.utils;

import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.particles.components.appearance.colors.Gradient;
import mchorse.bbs_mod.particles.components.appearance.colors.Solid;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeSection;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

public class UIGradientEditor extends UIElement
{
    private UIParticleSchemeSection section;
    private UIColor color;

    private Gradient gradient;

    private Gradient.ColorStop current;
    private final MouseGestureOwnership dragOwnership = new MouseGestureOwnership();
    private long dragGeneration;
    private int dragging = -1;
    private int lastX;
    private boolean dragSnapshotActive;
    private Gradient dragSnapshotGradient;
    private Gradient.ColorStop dragSnapshotCurrent;
    private List<Gradient.ColorStop> dragSnapshotOrder;
    private List<Float> dragSnapshotPositions;

    private Area a = new Area();
    private Area b = new Area();
    private Color c = new Color();

    public UIGradientEditor(UIParticleSchemeSection section, UIColor color)
    {
        super();

        this.section = section;
        this.color = color;

        this.context((menu) ->
        {
            menu.action(Icons.ADD, UIKeys.SNOWSTORM_LIGHTING_CONTEXT_ADD_STOP, this::addColorStop);

            if (this.gradient.stops.size() > 1)
            {
                menu.action(Icons.REMOVE, UIKeys.SNOWSTORM_LIGHTING_CONTEXT_REMOVE_STOP, this::removeColorStop);
            }
        });

        this.h(UIConstants.CONTROL_HEIGHT);
    }

    private Color fillColor(Solid solid)
    {
        this.c.r = (float) solid.r.get();
        this.c.g = (float) solid.g.get();
        this.c.b = (float) solid.b.get();
        this.c.a = (float) solid.a.get();

        return this.c;
    }

    private Area fillBound(Gradient.ColorStop stop)
    {
        int x = this.a.x(stop.stop / this.gradient.range);

        this.b.set(x - 3, this.a.ey() - 7, 6, 10);

        return this.b;
    }

    private void fillStop(Gradient.ColorStop stop)
    {
        this.current = stop;
        this.color.setColor(this.fillColor(stop.color).getARGBColor());
    }

    public void setColor(int color)
    {
        this.c.set(color);

        ((MolangValue) this.current.color.r).expression.set(this.c.r);
        ((MolangValue) this.current.color.g).expression.set(this.c.g);
        ((MolangValue) this.current.color.b).expression.set(this.c.b);
        ((MolangValue) this.current.color.a).expression.set(this.c.a);
    }

    public void setGradient(Gradient gradient)
    {
        this.gradient = gradient;

        if (this.gradient.stops.isEmpty())
        {
            this.gradient.stops.add(new Gradient.ColorStop(0, new Solid()));
        }

        this.fillStop(this.gradient.stops.get(0));
        this.color.setColor(this.fillColor(this.current.color).getARGBColor());
    }

    private void addColorStop()
    {
        float x = (this.getContext().mouseX - this.area.x) / (float) this.area.w * this.gradient.range;

        x = MathUtils.clamp(x, 0, this.gradient.range);

        Solid color = new Solid();
        Gradient.ColorStop stop = new Gradient.ColorStop(x, color);

        color.r = new MolangValue(null, new Constant(1F));
        color.g = new MolangValue(null, new Constant(1F));
        color.b = new MolangValue(null, new Constant(1F));
        color.a = new MolangValue(null, new Constant(1F));

        this.gradient.stops.add(stop);
        this.gradient.sort();

        this.fillStop(stop);
    }

    private void removeColorStop()
    {
        int index = this.gradient.stops.indexOf(this.current);

        this.gradient.stops.remove(index);

        index = MathUtils.clamp(index, 0, this.gradient.stops.size() - 1);

        this.fillStop(this.gradient.stops.get(index));
    }

    @Override
    public void resize()
    {
        super.resize();

        this.a.copy(this.area);
        this.a.offset(-1);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        Area outside = new Area();

        outside.copy(this.area);
        outside.offset(5);

        if (outside.isInside(context) && context.mouseButton == 0)
        {
            for (Gradient.ColorStop stop : this.gradient.stops)
            {
                Area area = this.fillBound(stop);

                if (area.isInside(context))
                {
                    long generation = this.dragOwnership.acquireToken(context.mouseButton);

                    if (generation == 0L)
                    {
                        return true;
                    }

                    this.dragGeneration = generation;
                    boolean started = false;

                    try
                    {
                        this.captureDragSnapshot();
                        this.dragging = 0;
                        this.lastX = context.mouseX;
                        this.fillStop(stop);
                        started = true;

                        return true;
                    }
                    finally
                    {
                        if (!started && this.dragOwnership.release(context.mouseButton, generation))
                        {
                            this.dragGeneration = 0L;
                            this.dragging = -1;
                            this.clearDragSnapshot();
                        }
                    }
                }
            }

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        long generation = this.dragGeneration;

        if (!this.dragOwnership.release(context.mouseButton, generation))
        {
            return super.subMouseReleased(context);
        }

        boolean wasDragging = this.dragging != -1;

        this.dragGeneration = 0L;
        this.dragging = -1;
        this.clearDragSnapshot();

        if (wasDragging)
        {
            this.section.dirty();
        }

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        long generation = this.dragGeneration;

        if (this.dragOwnership.release(context.mouseButton, generation))
        {
            this.restoreDragSnapshot();
            this.dragGeneration = 0L;
            this.dragging = -1;
            this.clearDragSnapshot();
        }

        super.subMouseCanceled(context);
    }

    private void captureDragSnapshot()
    {
        this.dragSnapshotActive = this.gradient != null;
        this.dragSnapshotGradient = this.gradient;

        if (!this.dragSnapshotActive)
        {
            return;
        }

        this.dragSnapshotCurrent = this.current;
        this.dragSnapshotOrder = new ArrayList<>(this.gradient.stops);
        this.dragSnapshotPositions = new ArrayList<>(this.gradient.stops.size());

        for (Gradient.ColorStop stop : this.gradient.stops)
        {
            this.dragSnapshotPositions.add(stop.stop);
        }
    }

    private void restoreDragSnapshot()
    {
        if (!this.dragSnapshotActive || this.gradient == null || this.gradient != this.dragSnapshotGradient)
        {
            return;
        }

        for (int i = 0; i < this.dragSnapshotOrder.size(); i++)
        {
            this.dragSnapshotOrder.get(i).stop = this.dragSnapshotPositions.get(i);
        }

        this.gradient.stops.clear();
        this.gradient.stops.addAll(this.dragSnapshotOrder);
        this.current = this.dragSnapshotCurrent;

        if (this.current != null)
        {
            this.color.setColor(this.fillColor(this.current.color).getARGBColor());
        }
    }

    private void clearDragSnapshot()
    {
        this.dragSnapshotActive = false;
        this.dragSnapshotGradient = null;
        this.dragSnapshotCurrent = null;
        this.dragSnapshotOrder = null;
        this.dragSnapshotPositions = null;
    }

    @Override
    public void render(UIContext context)
    {
        if (this.dragging == 0 && Math.abs(context.mouseX - this.lastX) > 3)
        {
            this.dragging = 1;
        }
        else if (this.dragging == 1)
        {
            float x = (context.mouseX - this.area.x) / (float) this.area.w * this.gradient.range;

            this.current.stop = MathUtils.clamp(x, 0, this.gradient.range);
            this.gradient.sort();
        }

        this.area.render(context.batcher, Colors.A100);

        int size = this.gradient.stops.size();

        context.batcher.iconArea(Icons.CHECKBOARD, this.a.x, this.a.y, this.a.w, this.a.h);

        Gradient.ColorStop first = this.gradient.stops.get(0);

        if (first.stop > 0)
        {
            int x1 = this.a.x(first.stop / this.gradient.range);
            int rgba1 = this.fillColor(first.color).getARGBColor();

            context.batcher.box(this.a.x, this.a.y, x1, this.a.ey(), rgba1);
        }

        for (int i = 0; i < size; i++)
        {
            Gradient.ColorStop stop = this.gradient.stops.get(i);
            Gradient.ColorStop next = i + 1 < size ? this.gradient.stops.get(i + 1) : stop;

            int x1 = this.a.x(stop.stop / this.gradient.range);
            int x2 = this.a.x((next == stop ? this.gradient.range : next.stop) / this.gradient.range);

            int rgba1 = this.fillColor(stop.color).getARGBColor();
            int rgba2 = this.fillColor(next.color).getARGBColor();

            context.batcher.gradientHBox(x1, this.a.y, x2, this.a.ey(), rgba1, rgba2);
        }

        for (int i = 0; i < size; i++)
        {
            Gradient.ColorStop stop = this.gradient.stops.get(i);
            Area area = this.fillBound(stop);
            int handleColor = this.fillColor(stop.color).getARGBColor();

            context.batcher.box(area.x, area.y, area.ex(), area.ey(), this.current == stop ? Colors.WHITE : Colors.A100);
            context.batcher.box(area.x + 1, area.y + 1, area.ex() - 1, area.ey() - 1, handleColor);
        }

        super.render(context);
    }
}
