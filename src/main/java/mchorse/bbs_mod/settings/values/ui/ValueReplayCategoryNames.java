package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.categories.Categories;
import mchorse.bbs_mod.utils.categories.CategoryPath;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;

/** Keeps the legacy addon field usable while folder metadata owns persistence. */
public final class ValueReplayCategoryNames extends ValueStringKeys
{
    private final Categories categories;
    private final Set<String> names = new AbstractSet<>()
    {
        @Override
        public Iterator<String> iterator()
        {
            Iterator<String> paths = categories.getPaths().iterator();
            return new Iterator<>()
            {
                private String current;

                @Override
                public boolean hasNext() { return paths.hasNext(); }

                @Override
                public String next() { return this.current = paths.next(); }

                @Override
                public void remove() { categories.removeRecord(this.current); }
            };
        }

        @Override
        public int size() { return categories.getPaths().size(); }

        @Override
        public boolean add(String path)
        {
            String normalized = CategoryPath.normalize(path);
            if (normalized.isEmpty() || categories.getByPath(normalized) != null) return false;
            categories.ensure(normalized);
            return true;
        }
    };

    public ValueReplayCategoryNames(Categories categories)
    {
        super(categories.getId());
        this.categories = categories;
    }

    @Override
    public Set<String> get() { return this.names; }

    @Override
    public Set<String> getOriginalValue() { return this.names; }

    @Override
    public void set(Set<String> names, int flags)
    {
        this.categories.preNotify(flags);
        for (String path : this.categories.getPaths())
        {
            if (!names.contains(path)) this.categories.removeRecord(path);
        }
        for (String path : names) this.categories.ensure(path);
        this.categories.postNotify(flags);
    }

    @Override
    public BaseType toData() { return this.categories.toData(); }

    @Override
    public void fromData(BaseType data) { this.categories.fromData(data); }
}
