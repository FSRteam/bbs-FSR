package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.api.EventBus;
import mchorse.bbs_mod.api.events.BaseRegisterSettingsEvent;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The three API registration events a client addon subscribes to.
 *
 * <p>This test exists because a compiler cannot see the defect it guards. Three
 * {@code api.client.events} classes carried addon-facing javadoc and were dispatched to the wrong
 * channel: they compiled, they subscribed, and they never fired. Nothing in a type system connects
 * "the class an addon subscribes to" with "the class that is posted", so only executing the post
 * tells the two apart.</p>
 *
 * <p>Three properties are pinned, each with its own failure mode:</p>
 * <ul>
 *   <li>the channel — a subscriber annotated with {@code api.Subscribe} is reached by a post on
 *       {@code api.EventBus.INSTANCE}, and one annotated with the internal {@code events.Subscribe}
 *       is not. The two annotations look identical at the call site, so without the negative
 *       control a test can pass while believing the wrong annotation is the one that works;</li>
 *   <li>the promise — {@code RegisterL10nEvent}'s javadoc says a language file registered there is
 *       read by the load that follows it, so the test registers one on a live {@code L10n} and
 *       reads back the very list that load reads;</li>
 *   <li>the production call sites — which class each dispatch names, which channel it posts on, and
 *       that the language dispatch sits between the client-addon registration and the load, and
 *       after the core language files were registered.</li>
 * </ul>
 *
 * <p>Super-class delivery is checked here too, because {@code BaseRegisterSettingsEvent}'s own
 * javadoc promises that "the bus hands an event to the subscribers of its super classes as well" —
 * a promise about the addon channel that the class never had a chance to make good on.</p>
 *
 * <p><b>The subscriber table is static and has no unregister.</b> Every subscriber this class
 * registers stays on the channel for the rest of the JVM, so a later method's post reaches an
 * earlier method's subscriber as well. That is why each subscriber captures its own list and no
 * assertion reads a list it does not own - and why a future assertion must not assume that the
 * subscriber it just registered is the only live one. Adding an unsubscribe solely for this test
 * would leave the production path that lacks one untested.</p>
 */
public final class AddonRegistrationDispatchTest
{
    private static final String CLIENT = "src/client/java/mchorse/bbs_mod/BBSModClient.java";
    private static final String DASHBOARD = "src/client/java/mchorse/bbs_mod/ui/dashboard/UIDashboard.java";

    private AddonRegistrationDispatchTest()
    {}

    public static void runAll()
    {
        addonChannelReachesApiSubscribers();
        internalAnnotationDoesNotFeedTheAddonChannel();
        baseEventSubscriptionSeesTheClientSettingsEvent();
        languageFilesAddedOnTheEventReachTheLoad();
        productionDispatchNamesTheApiClasses();

        System.out.println("AddonRegistrationDispatchTest: all tests passed");
    }

    private static void addonChannelReachesApiSubscribers()
    {
        List<String> seen = new ArrayList<>();

        EventBus.INSTANCE.register(new ApiSubscriber(seen));

        EventBus.INSTANCE.post(new RegisterL10nEvent(null));
        EventBus.INSTANCE.post(new RegisterClientSettingsEvent());
        EventBus.INSTANCE.post(new RegisterDashboardPanelsEvent(null));

        require(seen.equals(List.of("l10n", "settings", "panels")),
            "the addon channel did not deliver every api registration event: " + seen);
    }

    /**
     * The negative control. A subscriber shaped exactly like the one above, differing only in the
     * annotation, must not be indexed: the addon bus checks {@code api.Subscribe} and nothing else.
     * If this ever passes with {@code seen} non-empty, the addon bus started accepting the internal
     * annotation and the positive test above stopped proving which annotation works.
     */
    private static void internalAnnotationDoesNotFeedTheAddonChannel()
    {
        List<String> seen = new ArrayList<>();

        EventBus.INSTANCE.register(new InternalSubscriber(seen));
        EventBus.INSTANCE.post(new RegisterL10nEvent(null));

        require(seen.isEmpty(),
            "a subscriber carrying the internal events.Subscribe was accepted by the addon channel: " + seen);
    }

    private static void baseEventSubscriptionSeesTheClientSettingsEvent()
    {
        List<String> seen = new ArrayList<>();

        EventBus.INSTANCE.register(new BaseSettingsSubscriber(seen));
        EventBus.INSTANCE.post(new RegisterClientSettingsEvent());

        require(seen.equals(List.of("base")),
            "a subscriber to BaseRegisterSettingsEvent was not called for RegisterClientSettingsEvent: " + seen);
    }

    /**
     * Reads back {@code getAllLinks} rather than running {@code L10n.reload()}: that is the list the
     * load reads first, and the only thing an addon's registration can change about it. Calling
     * {@code reload} here would additionally walk the asset provider and {@code BBSSettings}, which
     * a headless JVM does not stand up - a failure there would be indistinguishable from the one
     * this test is about.
     */
    private static void languageFilesAddedOnTheEventReachTheLoad()
    {
        L10n l10n = new L10n();

        l10n.register((lang) -> Collections.singletonList(Link.assets("strings/" + lang + ".json")));
        EventBus.INSTANCE.register(new LanguageSubscriber());
        EventBus.INSTANCE.post(new RegisterL10nEvent(l10n));

        Link addonFile = Link.bbs("addon_dispatch_test/strings/" + L10n.DEFAULT_LANGUAGE + ".json");
        Link coreFile = Link.assets("strings/" + L10n.DEFAULT_LANGUAGE + ".json");

        require(l10n.getAllLinks(L10n.DEFAULT_LANGUAGE).contains(addonFile),
            "a language file registered on RegisterL10nEvent never reached the list the load reads");
        require(l10n.getAllLinks(L10n.DEFAULT_LANGUAGE).contains(coreFile),
            "the core language file list was lost while the addon one was added");
    }

    private static void productionDispatchNamesTheApiClasses()
    {
        String client = compact(read(CLIENT));
        String dashboard = compact(read(DASHBOARD));

        /* Each dispatch has to name the api class: the legacy class of each pair is a different
         * type, so posting it puts the event under a key no addon-subscribed subscriber has. */
        require(client.contains("postAddonEvent(new RegisterL10nEvent(l10n));"),
            "the language dispatch no longer posts the api RegisterL10nEvent on the addon channel");
        require(client.contains("postAddonEvent(new RegisterClientSettingsEvent());"),
            "the client settings dispatch no longer posts the api RegisterClientSettingsEvent on the addon channel");
        require(dashboard.contains("EventBus.INSTANCE.post(new RegisterDashboardPanelsEvent(this));"),
            "the dashboard dispatch no longer posts the api RegisterDashboardPanelsEvent on the addon channel");

        /* The old shape is asserted absent, not merely the new one present: a re-added legacy import
         * alongside a correct post would keep this test green while re-splitting the channel. */
        require(!client.contains("mchorse.bbs_mod.events.register.RegisterL10nEvent"),
            "the language dispatch still names the legacy event class");
        require(!client.contains("mchorse.bbs_mod.events.register.RegisterClientSettingsEvent"),
            "the client settings dispatch still names the legacy event class");
        require(!dashboard.contains("mchorse.bbs_mod.events.register.RegisterDashboardPanelsEvent"),
            "the dashboard dispatch still names the legacy event class");
        require(!client.contains("BBSMod.events.post(new RegisterL10nEvent"),
            "the language event is still posted on the internal bus");
        require(!client.contains("BBSMod.events.post(new RegisterClientSettingsEvent"),
            "the client settings event is still posted on the internal bus");
        require(!dashboard.contains("BBSMod.events.post(new RegisterDashboardPanelsEvent"),
            "the dashboard panels event is still posted on the internal bus");

        /* The order the javadoc promises: the core filler is registered, then the addon is given
         * the chance to add its own, then the table is read. Registering the client entrypoint in
         * between is what lets the posted event have a listener at all - moved back below the
         * reload, the fix is invisible again. */
        assertOrdered(client,
            "l10n.register((lang) ->",
            "getEntrypoints(\"bbs-client-addon\"",
            "postAddonEvent(new RegisterL10nEvent(l10n));",
            "l10n.reload();");
    }

    private static final class ApiSubscriber
    {
        private final List<String> seen;

        private ApiSubscriber(List<String> seen)
        {
            this.seen = seen;
        }

        @mchorse.bbs_mod.api.Subscribe
        public void onL10n(RegisterL10nEvent event)
        {
            this.seen.add("l10n");
        }

        @mchorse.bbs_mod.api.Subscribe
        public void onSettings(RegisterClientSettingsEvent event)
        {
            this.seen.add("settings");
        }

        @mchorse.bbs_mod.api.Subscribe
        public void onPanels(RegisterDashboardPanelsEvent event)
        {
            this.seen.add("panels");
        }
    }

    private static final class InternalSubscriber
    {
        private final List<String> seen;

        private InternalSubscriber(List<String> seen)
        {
            this.seen = seen;
        }

        @mchorse.bbs_mod.events.Subscribe
        public void onL10n(RegisterL10nEvent event)
        {
            this.seen.add("l10n");
        }
    }

    private static final class BaseSettingsSubscriber
    {
        private final List<String> seen;

        private BaseSettingsSubscriber(List<String> seen)
        {
            this.seen = seen;
        }

        @mchorse.bbs_mod.api.Subscribe
        public void onSettings(BaseRegisterSettingsEvent event)
        {
            this.seen.add("base");
        }
    }

    private static final class LanguageSubscriber
    {
        @mchorse.bbs_mod.api.Subscribe
        public void onL10n(RegisterL10nEvent event)
        {
            event.l10n.registerOne((lang) -> Link.bbs("addon_dispatch_test/strings/" + lang + ".json"));
        }
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, previous + 1);

            require(next > previous, "the registration order drifted at: " + marker);
            previous = next;
        }
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static String read(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            Path source = current.resolve(relativePath);

            if (Files.isRegularFile(source))
            {
                try
                {
                    return Files.readString(source);
                }
                catch (IOException e)
                {
                    throw new AssertionError("could not read " + source, e);
                }
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relativePath);
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
