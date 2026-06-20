package mchorse.bbs_mod.ui.dashboard.textures.undo;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.dashboard.textures.data.Document;
import mchorse.bbs_mod.utils.undo.IUndo;

public class LayerStateUndo implements IUndo<Document>
{
    private final MapType before;
    private MapType after;
    private final String mergeTag;
    private boolean merging = true;

    public LayerStateUndo(MapType before, MapType after)
    {
        this(before, after, null);
    }

    public LayerStateUndo(MapType before, MapType after, String mergeTag)
    {
        this.before = before;
        this.after = after;
        this.mergeTag = mergeTag;
    }

    @Override
    public IUndo<Document> noMerging()
    {
        this.merging = false;

        return this;
    }

    @Override
    public boolean isMergeable(IUndo<Document> undo)
    {
        return this.merging
            && this.mergeTag != null
            && undo instanceof LayerStateUndo other
            && this.mergeTag.equals(other.mergeTag);
    }

    @Override
    public void merge(IUndo<Document> undo)
    {
        if (undo instanceof LayerStateUndo other)
        {
            this.after = other.after;
        }
    }

    @Override
    public void undo(Document context)
    {
        context.fromData(this.before);
    }

    @Override
    public void redo(Document context)
    {
        context.fromData(this.after);
    }
}
