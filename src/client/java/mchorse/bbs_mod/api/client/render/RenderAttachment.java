package mchorse.bbs_mod.api.client.render;

import mchorse.bbs_mod.forms.renderers.FormRenderer;

/** Typed key for renderer-owned transient client state. */
public final class RenderAttachment<T>
{
    public T get(FormRenderer<?> renderer) { return renderer == null ? null : renderer.getAttachment(this); }
    public void set(FormRenderer<?> renderer, T value) { if (renderer != null) renderer.setAttachment(this, value); }
    public void remove(FormRenderer<?> renderer) { if (renderer != null) renderer.setAttachment(this, null); }
}
