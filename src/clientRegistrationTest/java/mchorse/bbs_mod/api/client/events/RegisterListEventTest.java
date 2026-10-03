package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The three register-list events with a table of their own.
 *
 * <p>These three differ from the rest of the family: upstream hands each registration straight to
 * the UI object that consumes it, while here the factory is kept on the event class and read back
 * through a getter. That difference is only defensible if the round trip works, so this test walks
 * it: register, read back, take it out, and confirm the list is empty again. Without it, "the
 * factory was stored" and "the factory was dropped on the floor" are the same observation.</p>
 *
 * <p>It also pins the two properties that make the list safe to hand out: the reader returns a view
 * that cannot be used to mutate the list behind the event's back, and a null factory is refused
 * rather than stored.</p>
 */
public final class RegisterListEventTest
{
    private RegisterListEventTest()
    {}

    public static void runAll()
    {
        panels();
        overlays();
        actions();

        System.out.println("RegisterListEventTest: all tests passed");
    }

    private static void panels()
    {
        Consumer<UIForm<?>> first = form -> {};
        Consumer<UIForm<?>> second = form -> {};

        int before = RegisterFormPanelsEvent.getPanels().size();

        RegisterFormPanelsEvent event = new RegisterFormPanelsEvent();

        event.register(first);
        event.register(second);
        event.register(null);

        List<Consumer<UIForm<?>>> read = RegisterFormPanelsEvent.getPanels();

        require(read.size() == before + 2, "both panel factories were stored");
        require(read.get(before) == first, "the panel factories stay in registration order");
        require(read.get(before + 1) == second, "the panel factories stay in registration order");
        require(!read.contains(null), "a null panel factory is not stored");
        require(!RegisterFormPanelsEvent.unregister(null), "unregistering null reports nothing was removed");
        require(RegisterFormPanelsEvent.unregister(first), "unregister reports the panel factory was there");
        require(!RegisterFormPanelsEvent.unregister(first), "unregistering it twice reports it was gone");
        require(RegisterFormPanelsEvent.getPanels().size() == before + 1, "the list shrank by one");
        require(RegisterFormPanelsEvent.unregister(second), "the second panel factory comes out too");
        require(RegisterFormPanelsEvent.getPanels().size() == before, "the list is back where it started");

        requireUnmodifiable("panel factories", () -> RegisterFormPanelsEvent.getPanels().add(first));
    }

    private static void overlays()
    {
        Function<UIFilmPreview, UIElement> factory = preview -> null;

        int before = RegisterPreviewOverlaysEvent.getOverlays().size();

        RegisterPreviewOverlaysEvent event = new RegisterPreviewOverlaysEvent();

        event.register(factory);
        event.register(null);

        List<Function<UIFilmPreview, UIElement>> read = RegisterPreviewOverlaysEvent.getOverlays();

        require(read.size() == before + 1, "the overlay factory was stored, and the null one was not");
        require(read.get(before) == factory, "the overlay factory is the one that was registered");
        require(!RegisterPreviewOverlaysEvent.unregister(null), "unregistering null reports nothing was removed");
        require(RegisterPreviewOverlaysEvent.unregister(factory), "unregister reports the overlay factory was there");
        require(RegisterPreviewOverlaysEvent.getOverlays().size() == before, "the list is back where it started");

        requireUnmodifiable("overlay factories", () -> RegisterPreviewOverlaysEvent.getOverlays().add(factory));
    }

    private static void actions()
    {
        BiFunction<UIFilmPanel, Supplier<Replay>, UIElement> factory = (panel, actor) -> null;

        int before = RegisterReplayActionsEvent.getActions().size();

        RegisterReplayActionsEvent event = new RegisterReplayActionsEvent();

        event.register(factory);
        event.register(null);

        List<BiFunction<UIFilmPanel, Supplier<Replay>, UIElement>> read = RegisterReplayActionsEvent.getActions();

        require(read.size() == before + 1, "the action factory was stored, and the null one was not");
        require(read.get(before) == factory, "the action factory is the one that was registered");
        require(!RegisterReplayActionsEvent.unregister(null), "unregistering null reports nothing was removed");
        require(RegisterReplayActionsEvent.unregister(factory), "unregister reports the action factory was there");
        require(RegisterReplayActionsEvent.getActions().size() == before, "the list is back where it started");

        requireUnmodifiable("action factories", () -> RegisterReplayActionsEvent.getActions().add(factory));
    }

    /**
     * The readers hand back an unmodifiable view. If they handed back the live list, an addon could
     * clear every other addon's registrations by calling {@code getPanels().clear()} — and the next
     * pane would be built against a list that one addon had already emptied.
     */
    private static void requireUnmodifiable(String what, Runnable mutation)
    {
        try
        {
            mutation.run();
        }
        catch (UnsupportedOperationException expected)
        {
            return;
        }

        throw new AssertionError("the " + what + " reader hands back a mutable list");
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
