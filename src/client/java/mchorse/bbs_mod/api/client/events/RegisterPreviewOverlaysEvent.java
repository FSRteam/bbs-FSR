package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Posted on the client before any film editor is opened, for an addon that wants a layer of its
 * own over the editor's preview — one that takes the mouse, not just draws.
 *
 * <p>The element becomes a child of the preview, so it is laid out and receives input the way
 * everything else in the editor does.</p>
 *
 * <p><b>Deviation from upstream.</b> Upstream's {@code register(...)} delegates straight into the
 * preview ({@code UIFilmPreview.registerOverlay}); FSR keeps the factories in a table on this class
 * and the preview reads them back with {@link #getOverlays()}. Same call shape for an addon, a
 * different owner for the list — the preview that consumes it is edited separately from this batch.
 * Until that one line exists, a registered factory is collected and readable but no element is
 * built from it. {@link #unregister} is the way back out.</p>
 */
public class RegisterPreviewOverlaysEvent
{
    private static final List<Function<UIFilmPreview, UIElement>> OVERLAYS = new CopyOnWriteArrayList<>();

    /**
     * @param factory makes the element. It is asked once per film editor, since a preview is
     *                built anew every time one is opened.
     */
    public void register(Function<UIFilmPreview, UIElement> factory)
    {
        if (factory != null)
        {
            OVERLAYS.add(factory);
        }
    }

    /** The registered overlay factories, in registration order. */
    public static List<Function<UIFilmPreview, UIElement>> getOverlays()
    {
        return Collections.unmodifiableList(OVERLAYS);
    }

    /** Takes a factory back out; returns whether it was there. */
    public static boolean unregister(Function<UIFilmPreview, UIElement> factory)
    {
        return factory != null && OVERLAYS.remove(factory);
    }
}
