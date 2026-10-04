package mchorse.bbs_mod.ui.film.clips.renderer;

import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.camera.clips.misc.VideoClientClip;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.HashMap;
import java.util.Map;

/**
 * How each kind of clip draws its strip on a timeline.
 *
 * <p>The registry is static because the object is not: a new one is made for every timeline that
 * exists, and it used to fill itself in its constructor — so there was no one place an addon
 * could have registered into even if it had been able to reach it.</p>
 */
public class UIClipRenderers
{
    private static final Map<Class, IUIClipRenderer> RENDERERS = new HashMap<>();

    private final UIClipRenderer defaultRenderer = new UIClipRenderer();

    static
    {
        register(AudioClientClip.class, new UIAudioClipRenderer());
        register(VideoClientClip.class, new UIAudioClipRenderer());
    }

    /** Gives a clip type its timeline strip renderer. */
    public static void register(Class key, IUIClipRenderer renderer)
    {
        if (key != null && renderer != null)
        {
            RENDERERS.put(key, renderer);
        }
    }

    /** Removes a clip's renderer; only removes the instance that was registered. */
    public static void unregister(Class key, IUIClipRenderer renderer)
    {
        if (key != null && RENDERERS.get(key) == renderer)
        {
            RENDERERS.remove(key);
        }
    }

    public static boolean hasRenderers()
    {
        return !RENDERERS.isEmpty();
    }

    public <T extends Clip> IUIClipRenderer<T> get(T clip)
    {
        IUIClipRenderer renderer = RENDERERS.get(clip.getClass());

        return renderer == null ? this.defaultRenderer : renderer;
    }
}
