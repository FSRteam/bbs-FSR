package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Adds panels to existing form editors, before their shared material/general panels.
 * Called once per editor instance, before a form is assigned; filter by editor type here.
 * Panels receive the usual startEdit/finishEdit lifecycle.
 *
 * <p>A listener must survive being called once per editor instance: it is asked for a new panel
 * each time one is opened, so it builds a panel rather than handing back one it made earlier.</p>
 *
 * <p><b>Deviation from upstream.</b> Upstream's {@code register(...)} delegates straight into the UI
 * controller ({@code UIForm.registerPanelExtension}); FSR keeps the factories in a table on this
 * class and the editor reads them back with {@link #getPanels()}. Same call shape for an addon, a
 * different owner for the list — the editor that consumes it is edited separately from this batch.
 * The consequence to know about: until that one line exists, a registered factory is collected and
 * readable but nothing builds a panel from it. {@link #unregister} is the way back out.</p>
 */
public class RegisterFormPanelsEvent
{
    private static final List<Consumer<UIForm<?>>> PANELS = new CopyOnWriteArrayList<>();

    public void register(Consumer<UIForm<?>> factory)
    {
        if (factory != null)
        {
            PANELS.add(factory);
        }
    }

    /** The registered panel factories, in registration order. */
    public static List<Consumer<UIForm<?>>> getPanels()
    {
        return Collections.unmodifiableList(PANELS);
    }

    /** Takes a factory back out; returns whether it was there. */
    public static boolean unregister(Consumer<UIForm<?>> factory)
    {
        return factory != null && PANELS.remove(factory);
    }
}
