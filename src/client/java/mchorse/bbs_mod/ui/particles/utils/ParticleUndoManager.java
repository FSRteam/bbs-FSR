package mchorse.bbs_mod.ui.particles.utils;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.utils.Timer;

import java.util.ArrayList;
import java.util.List;

public class ParticleUndoManager
{
    private final List<MapType> snapshots = new ArrayList<>();
    private int position = -1;
    private final int limit = 50;

    private MapType editStartSnapshot;
    private boolean hasEditStarted;
    private final Timer boundaryTimer = new Timer(800);

    public void pushSnapshot(ParticleScheme scheme)
    {
        MapType snapshot = ParticleScheme.toData(scheme);

        if (this.editStartSnapshot == null)
        {
            this.editStartSnapshot = snapshot;
            this.hasEditStarted = true;
        }
    }

    public void markBoundary()
    {
        if (!this.hasEditStarted || this.editStartSnapshot == null)
        {
            return;
        }

        this.pushInternal(this.editStartSnapshot);
        this.editStartSnapshot = null;
        this.hasEditStarted = false;
    }

    public void trySubmit()
    {
        if (this.hasEditStarted && this.boundaryTimer.checkReset())
        {
            this.markBoundary();
        }
    }

    private void pushInternal(MapType snapshot)
    {
        while (this.snapshots.size() > this.position + 1)
        {
            this.snapshots.remove(this.snapshots.size() - 1);
        }

        if (this.snapshots.size() >= this.limit)
        {
            this.snapshots.remove(0);
        }
        else
        {
            this.position += 1;
        }

        this.snapshots.add(snapshot);
        this.boundaryTimer.mark();
    }

    public MapType undo()
    {
        if (this.position < 0)
        {
            return null;
        }

        MapType snapshot = this.snapshots.get(this.position);
        this.position -= 1;

        return snapshot;
    }

    public MapType redo()
    {
        if (this.position + 1 >= this.snapshots.size())
        {
            return null;
        }

        this.position += 1;

        return this.snapshots.get(this.position);
    }
}
