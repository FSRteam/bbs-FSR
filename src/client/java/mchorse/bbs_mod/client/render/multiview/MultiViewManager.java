package mchorse.bbs_mod.client.render.multiview;

import com.mojang.blaze3d.pipeline.RenderTarget;
import mchorse.bbs_mod.graphics.texture.Texture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns render resources for editor viewports and their lifecycle. */
public final class MultiViewManager
{
    public static final String MAIN_ID = "preview";

    private final Map<String, ViewRenderState> views = new LinkedHashMap<>();

    public ViewRenderState register(String id, int width, int height, boolean primary)
    {
        ViewRenderState existing = this.views.get(id);

        if (existing != null)
        {
            existing.setRequestedSize(width, height);
            existing.setPrimary(primary);
            return existing;
        }

        ViewRenderState created = new ViewRenderState(id, width, height, primary);
        this.views.put(id, created);
        return created;
    }

    public ViewRenderState get(String id)
    {
        return this.views.get(id);
    }

    public ViewRenderState getOrCreate(String id, int width, int height)
    {
        return this.register(id, width, height, MAIN_ID.equals(id));
    }

    public void attachTarget(String id, RenderTarget target, Texture texture)
    {
        ViewRenderState view = this.views.get(id);

        if (view == null)
        {
            int width = target == null ? 1 : target.width;
            int height = target == null ? 1 : target.height;
            view = this.register(id, width, height, MAIN_ID.equals(id));
        }

        view.setFramebuffer(target);
        view.setTexture(texture);
    }

    public Collection<ViewRenderState> all()
    {
        return Collections.unmodifiableCollection(this.views.values());
    }

    public Collection<ViewRenderState> auxiliary()
    {
        Collection<ViewRenderState> result = new ArrayList<>();

        for (ViewRenderState view : this.views.values())
        {
            if (!view.isPrimary())
            {
                result.add(view);
            }
        }

        return result;
    }

    public ViewRenderState remove(String id)
    {
        ViewRenderState removed = this.views.remove(id);

        if (removed != null)
        {
            removed.dispose();
        }

        return removed;
    }

    public void clearAuxiliary()
    {
        for (String id : new ArrayList<>(this.views.keySet()))
        {
            ViewRenderState view = this.views.get(id);

            if (view != null && !view.isPrimary())
            {
                this.remove(id);
            }
        }
    }

    public void dispose()
    {
        for (ViewRenderState view : this.views.values())
        {
            view.dispose();
        }

        this.views.clear();
    }
}
