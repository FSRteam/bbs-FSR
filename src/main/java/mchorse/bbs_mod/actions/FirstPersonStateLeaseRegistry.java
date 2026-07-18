package mchorse.bbs_mod.actions;

import java.util.IdentityHashMap;
import java.util.Map;

final class FirstPersonStateLeaseRegistry<T>
{
    interface Lease<T>
    {
        boolean acquire();

        boolean isHeld();

        /**
         * Move a held lease to an exact replacement identity atomically.  This
         * is used by NeoForge's PlayerEvent.Clone, where the replacement player
         * must not be able to acquire a second lease between old-player teardown
         * and restoration of the cached first-person state.
         */
        boolean transfer(T expectedTarget, T replacementTarget);

        void release();
    }

    private static final class DeniedLease<T> implements Lease<T>
    {
        @Override
        public boolean acquire()
        {
            return false;
        }

        @Override
        public boolean isHeld()
        {
            return false;
        }

        @Override
        public boolean transfer(T expectedTarget, T replacementTarget)
        {
            return false;
        }

        @Override
        public void release()
        {}
    }

    private final Map<T, Lease<T>> owners = new IdentityHashMap<>();

    public Lease<T> create(T target)
    {
        return target == null ? deniedLease() : new Handle(target);
    }

    public void clear()
    {
        synchronized (this)
        {
            this.owners.clear();
        }
    }

    public static <T> Lease<T> deniedLease()
    {
        return new DeniedLease<>();
    }

    private final class Handle implements Lease<T>
    {
        private T target;
        private boolean held;

        private Handle(T target)
        {
            this.target = target;
        }

        @Override
        public boolean acquire()
        {
            synchronized (FirstPersonStateLeaseRegistry.this)
            {
                Lease<T> owner = FirstPersonStateLeaseRegistry.this.owners.get(this.target);

                if (owner != null && owner != this)
                {
                    return false;
                }

                FirstPersonStateLeaseRegistry.this.owners.put(this.target, this);
                this.held = true;

                return true;
            }
        }

        @Override
        public boolean isHeld()
        {
            synchronized (FirstPersonStateLeaseRegistry.this)
            {
                return this.held && FirstPersonStateLeaseRegistry.this.owners.get(this.target) == this;
            }
        }

        @Override
        public boolean transfer(T expectedTarget, T replacementTarget)
        {
            synchronized (FirstPersonStateLeaseRegistry.this)
            {
                if (!this.held
                    || expectedTarget == null
                    || replacementTarget == null
                    || expectedTarget == replacementTarget
                    || this.target != expectedTarget
                    || FirstPersonStateLeaseRegistry.this.owners.get(this.target) != this)
                {
                    return false;
                }

                Lease<T> replacementOwner = FirstPersonStateLeaseRegistry.this.owners.get(replacementTarget);

                if (replacementOwner != null && replacementOwner != this)
                {
                    return false;
                }

                FirstPersonStateLeaseRegistry.this.owners.remove(this.target);
                this.target = replacementTarget;
                FirstPersonStateLeaseRegistry.this.owners.put(this.target, this);

                return true;
            }
        }

        @Override
        public void release()
        {
            synchronized (FirstPersonStateLeaseRegistry.this)
            {
                if (FirstPersonStateLeaseRegistry.this.owners.get(this.target) == this)
                {
                    FirstPersonStateLeaseRegistry.this.owners.remove(this.target);
                }

                this.held = false;
            }
        }
    }
}
