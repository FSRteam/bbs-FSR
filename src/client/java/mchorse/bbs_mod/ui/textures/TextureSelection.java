package mchorse.bbs_mod.ui.textures;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.input.items.Selection;

import java.util.ArrayList;
import java.util.List;

/**
 * The entries picked in a texture browser. Links are values, so equal links are the same
 * pick; there is a single order (the listing), so no scope.
 */
public class TextureSelection extends Selection<Link>
{
    public TextureSelection()
    {
        super((a, b) -> a.equals(b));
    }

    public List<Link> getLinks()
    {
        return new ArrayList<>(this.getItems());
    }

    public void set(Link link)
    {
        this.set(link, null);
    }

    public void add(Link link)
    {
        this.add(link, null);
    }

    public void toggle(Link link)
    {
        this.toggle(link, null);
    }

    public void range(Link link, List<TextureEntry> order)
    {
        this.range(link, null, order.stream().map(TextureEntry::link).toList());
    }

    /** Forget what is no longer listed. */
    public void retain(List<TextureEntry> entries)
    {
        this.retain((link) ->
        {
            for (TextureEntry entry : entries)
            {
                if (entry.link().equals(link))
                {
                    return true;
                }
            }

            return false;
        });
    }
}
