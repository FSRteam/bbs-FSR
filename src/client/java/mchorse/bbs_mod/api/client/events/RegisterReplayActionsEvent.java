package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Additional controls in actor properties. The supplier returns the currently displayed
 * actor (the first actor for a multiple selection), or null. Resolve it when acting;
 * do not capture its initial value while constructing the control.
 *
 * <p><b>Deviation from upstream.</b> Upstream's {@code register(...)} delegates straight into the
 * properties panel ({@code UIReplayPropertiesPanel.registerAction}); FSR keeps the factories in a
 * table on this class and the panel reads them back with {@link #getActions()}. Same call shape for
 * an addon, a different owner for the list — the panel that consumes it is edited separately from
 * this batch. Until that one line exists, a registered factory is collected and readable but no
 * control is built from it. {@link #unregister} is the way back out.</p>
 */
public class RegisterReplayActionsEvent
{
    private static final List<BiFunction<UIFilmPanel, Supplier<Replay>, UIElement>> ACTIONS = new CopyOnWriteArrayList<>();

    public void register(BiFunction<UIFilmPanel, Supplier<Replay>, UIElement> factory)
    {
        if (factory != null)
        {
            ACTIONS.add(factory);
        }
    }

    /** The registered action factories, in registration order. */
    public static List<BiFunction<UIFilmPanel, Supplier<Replay>, UIElement>> getActions()
    {
        return Collections.unmodifiableList(ACTIONS);
    }

    /** Takes a factory back out; returns whether it was there. */
    public static boolean unregister(BiFunction<UIFilmPanel, Supplier<Replay>, UIElement> factory)
    {
        return factory != null && ACTIONS.remove(factory);
    }
}
