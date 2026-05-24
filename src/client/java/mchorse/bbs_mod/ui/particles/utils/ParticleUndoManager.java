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

    private ParticleScheme scheme;
    private boolean dirty;
    private final Timer boundaryTimer = new Timer(800);

    public ParticleUndoManager(ParticleScheme scheme)
    {
        this.reset(scheme);
    }

    public void reset(ParticleScheme scheme)
    {
        this.scheme = scheme;
        this.snapshots.clear();
        this.position = -1;
        this.dirty = false;
        this.boundaryTimer.reset();

        if (scheme != null)
        {
            this.snapshots.add(ParticleScheme.toData(scheme));
            this.position = 0;
        }
    }

    public void pushSnapshot(ParticleScheme scheme)
    {
        if (scheme != null)
        {
            this.scheme = scheme;
        }

        this.markBoundary();
    }

    public void markBoundary()
    {
        if (this.scheme == null)
        {
            return;
        }

        this.dirty = true;
        this.boundaryTimer.mark();
    }

    public void trySubmit()
    {
        if (this.dirty && this.boundaryTimer.checkReset())
        {
            this.commitCurrentSnapshot();
            this.dirty = false;
        }
    }

    private void flush()
    {
        if (this.dirty)
        {
            this.commitCurrentSnapshot();
            this.dirty = false;
            this.boundaryTimer.reset();
        }
    }

    private void commitCurrentSnapshot()
    {
        if (this.scheme == null)
        {
            return;
        }

        MapType snapshot = ParticleScheme.toData(this.scheme);

        if (this.position >= 0 && this.position < this.snapshots.size() && snapshot.equals(this.snapshots.get(this.position)))
        {
            return;
        }

        while (this.snapshots.size() > this.position + 1)
        {
            this.snapshots.remove(this.snapshots.size() - 1);
        }

        if (this.snapshots.size() >= this.limit)
        {
            this.snapshots.remove(0);
            this.position -= 1;
        }

        this.position += 1;
        this.snapshots.add(snapshot);
    }

    public MapType undo()
    {
        this.flush();

        if (this.position <= 0)
        {
            return null;
        }

        this.position -= 1;

        return this.snapshots.get(this.position);
    }

    public MapType redo()
    {
        this.flush();

        if (this.position + 1 >= this.snapshots.size())
        {
            return null;
        }

        this.position += 1;

        return this.snapshots.get(this.position);
    }
}
