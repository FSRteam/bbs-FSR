package mchorse.bbs_mod.ui.dashboard.textures.undo;

import mchorse.bbs_mod.ui.dashboard.textures.data.Document;
import mchorse.bbs_mod.ui.dashboard.textures.data.TextureLayer;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.utils.undo.IUndo;

import java.util.HashMap;
import java.util.Map;

/**
 * Undo entry for a pixel stroke on a single document layer.
 *
 * <p>The edited layer is identified by its index (captured when the stroke starts) so undo/redo
 * resolve the live {@link TextureLayer} from the {@link Document} at apply time. Under the undo
 * manager's strict LIFO ordering the index always maps back to the same logical layer, even when
 * structural undos in between rebuild the layer list.</p>
 */
public class PixelsUndo implements IUndo<Document>
{
    /** Index of the layer this stroke edited within the document. */
    public int layerIndex;

    public Map<Integer, Pair<Color, Color>> pixels = new HashMap<>();

    public void setColor(Pixels pixels, int x, int y, Color color)
    {
        if (x < 0 || y < 0 || x >= pixels.width || y >= pixels.height)
        {
            return;
        }

        int index = pixels.toIndex(x, y);
        Pair<Color, Color> pair = this.pixels.computeIfAbsent(index, (k) -> new Pair<>(pixels.getColor(x, y).copy(), null));

        pair.b = color.copy();
        pixels.setColor(x, y, color);
    }

    public Color getOriginalColor(Pixels pixels, int x, int y)
    {
        Pair<Color, Color> pair = this.pixels.get(pixels.toIndex(x, y));

        return pair == null ? null : pair.a;
    }

    private TextureLayer getLayer(Document context)
    {
        return context != null && this.layerIndex >= 0 && this.layerIndex < context.layers.size()
            ? context.layers.get(this.layerIndex)
            : null;
    }

    @Override
    public IUndo<Document> noMerging()
    {
        return this;
    }

    @Override
    public boolean isMergeable(IUndo<Document> undo)
    {
        return false;
    }

    @Override
    public void merge(IUndo<Document> undo)
    {}

    @Override
    public void undo(Document context)
    {
        this.apply(context, true);
    }

    @Override
    public void redo(Document context)
    {
        this.apply(context, false);
    }

    private void apply(Document context, boolean undo)
    {
        TextureLayer layer = this.getLayer(context);

        if (layer == null || layer.pixels == null)
        {
            return;
        }

        Pixels pixels = layer.pixels;

        for (Map.Entry<Integer, Pair<Color, Color>> entry : this.pixels.entrySet())
        {
            int index = entry.getKey();
            Color color = undo ? entry.getValue().a : entry.getValue().b;

            pixels.setColor(pixels.toX(index), pixels.toY(index), color);
        }

        layer.updateTexture();
    }
}
