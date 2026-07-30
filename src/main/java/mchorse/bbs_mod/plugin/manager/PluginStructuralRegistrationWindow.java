package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Callback-scoped staged structural contributions for one plugin generation. */
public final class PluginStructuralRegistrationWindow
{
    public enum Kind
    {
        FORM,
        CLIP,
        PARTICLE,
        CLIENT
    }

    private final PluginOwner owner;
    private final Set<String> replaceableKeys;
    private final Set<String> keys = new LinkedHashSet<>();
    private final Set<Class<?>> formTypes = new LinkedHashSet<>();
    private final Set<Class<?>> clipTypes = new LinkedHashSet<>();
    private final List<Stage> stages = new ArrayList<>();
    private boolean open = true;
    private boolean active;

    public PluginStructuralRegistrationWindow(PluginOwner owner)
    {
        this(owner, Set.of());
    }

    public PluginStructuralRegistrationWindow(PluginOwner owner, Set<String> replaceableKeys)
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.replaceableKeys = replaceableKeys == null ? Set.of() : Set.copyOf(replaceableKeys);
    }

    public synchronized void requireOpen(String facade)
    {
        if (!this.open)
        {
            throw new IllegalStateException("structural registration facade '" + facade + "' is closed for " + this.owner);
        }
    }

    public synchronized boolean canReplace(String key)
    {
        return this.replaceableKeys.contains(key);
    }

    public synchronized void stage(
        String key,
        Kind kind,
        Class<?> registeredType,
        PluginContributionLedger ledger,
        Runnable apply,
        Runnable undo
    )
    {
        this.requireOpen(kind.name().toLowerCase(java.util.Locale.ROOT));
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(undo, "undo");

        if (!this.keys.add(key))
        {
            throw new IllegalStateException("duplicate staged structural key '" + key + "' for " + this.owner);
        }

        CleanupSlot cleanup = new CleanupSlot(undo);
        ledger.own("structural " + key, cleanup);
        this.stages.add(new Stage(key, apply, cleanup));

        if (registeredType != null && kind == Kind.FORM)
        {
            this.formTypes.add(registeredType);
        }
        else if (registeredType != null && kind == Kind.CLIP)
        {
            this.clipTypes.add(registeredType);
        }
    }

    public synchronized Set<String> keys()
    {
        return Set.copyOf(this.keys);
    }

    synchronized Set<Class<?>> formTypes()
    {
        return Set.copyOf(this.formTypes);
    }

    synchronized Set<Class<?>> clipTypes()
    {
        return Set.copyOf(this.clipTypes);
    }

    synchronized boolean hasContributions()
    {
        return !this.stages.isEmpty();
    }

    synchronized void activate()
    {
        if (this.active)
        {
            return;
        }

        int applied = 0;

        try
        {
            for (Stage stage : this.stages)
            {
                stage.apply.run();
                stage.cleanup.activate();
                applied += 1;
            }

            this.active = true;
        }
        catch (Throwable error)
        {
            for (int i = applied - 1; i >= 0; i -= 1)
            {
                try
                {
                    this.stages.get(i).cleanup.deactivate();
                }
                catch (Throwable cleanupError)
                {
                    error.addSuppressed(cleanupError);
                }
            }

            throw error;
        }
    }

    synchronized void deactivate()
    {
        if (!this.active)
        {
            return;
        }

        Throwable failure = null;

        for (int i = this.stages.size() - 1; i >= 0; i -= 1)
        {
            try
            {
                this.stages.get(i).cleanup.deactivate();
            }
            catch (Throwable error)
            {
                if (failure == null)
                {
                    failure = error;
                }
                else
                {
                    failure.addSuppressed(error);
                }
            }
        }

        this.active = false;

        if (failure instanceof RuntimeException runtime)
        {
            throw runtime;
        }
        else if (failure != null)
        {
            throw new IllegalStateException("failed to deactivate structural registrations for " + this.owner, failure);
        }
    }

    public synchronized void close()
    {
        this.open = false;
    }

    private record Stage(String key, Runnable apply, CleanupSlot cleanup) {}

    private static final class CleanupSlot implements AutoCloseable
    {
        private final Runnable undo;
        private boolean active;
        private boolean retired;

        private CleanupSlot(Runnable undo)
        {
            this.undo = undo;
        }

        synchronized void activate()
        {
            if (this.retired)
            {
                throw new IllegalStateException("structural cleanup slot is retired");
            }

            this.active = true;
        }

        synchronized void deactivate()
        {
            if (!this.active)
            {
                return;
            }

            this.active = false;
            this.undo.run();
        }

        @Override
        public synchronized void close()
        {
            if (this.retired)
            {
                return;
            }

            this.deactivate();
            this.retired = true;
        }
    }
}
