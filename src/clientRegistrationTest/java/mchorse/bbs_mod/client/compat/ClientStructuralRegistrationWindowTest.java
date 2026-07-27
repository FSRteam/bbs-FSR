package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.test.ExpectedErrorLogCapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic checks for one-shot client structural registration windows. */
public final class ClientStructuralRegistrationWindowTest
{
    private ClientStructuralRegistrationWindowTest()
    {}

    public static void main(String[] args)
    {
        try (ExpectedErrorLogCapture capture = ExpectedErrorLogCapture.install(
            "client registration", 2, (event) ->
            {
                String message = event.getMessage().getFormattedMessage();
                Throwable error = event.getThrown();
                String cause = error == null ? "" : error.getMessage();
                return message.startsWith("[bbs-client-api] key binding registration failed for '")
                    && ("deterministic registration exception".equals(cause)
                        || "deterministic registration linkage failure".equals(cause));
            }, "bbs-client-api"))
        {
            NetworkCompatClientDescriptorTest.runAll();
            UIFilmPanelCompatibilityDescriptorTest.runAll();
            CameraControllerResetTest.runAll();
            ModelBlockItemRendererSourceTest.runAll();
            acceptsBeforeEventAndRejectsLateCalls();
            closesBeforeInvokingNeoForgeRegistrations();
            isolatesRegistrationFailures();
            keepsSnapshotsImmutable();
            productionFacadeAndNeoForgeEventsStayWired();
            capture.assertExpectedErrors();
        }

        System.out.println("ClientStructuralRegistrationWindowTest: all tests passed; captured 2 expected failure diagnostics");
    }

    private static void acceptsBeforeEventAndRejectsLateCalls()
    {
        ClientApiCompat.StructuralRegistrationWindow<String> window = window("entity renderer");
        List<String> applied = new ArrayList<>();

        check(window.register("first", "first", null, "test:first"), "early registration was rejected");
        check(window.register("first", "first", null, "test:duplicate"), "idempotent registration was rejected");
        check(window.snapshot().equals(List.of("first")), "the same registration was queued more than once");
        window.close();
        check(!window.register("late", "late", null, "test:late"), "registration was accepted after the event sealed its window");

        window.consume(applied::add);

        check(applied.equals(List.of("first")), "queued registration was not applied exactly once");
        check(!window.register("later", "later", null, "test:later"), "registration was accepted after event consumption");

        window.consume(applied::add);

        check(applied.equals(List.of("first")), "a repeated NeoForge event applied registrations twice");
        check(window.snapshot().equals(List.of("first")), "late registration mutated the sealed queue");
    }

    private static void closesBeforeInvokingNeoForgeRegistrations()
    {
        ClientApiCompat.StructuralRegistrationWindow<String> window = window("block entity renderer");

        check(window.register("first", "first", null, "test:first"), "early block renderer registration was rejected");
        window.consume(value ->
        {
            check(!window.register("during-callback", "during-callback", null, "test:callback"),
                "registration remained open while NeoForge callbacks were executing");
        });
        check(window.snapshot().equals(List.of("first")), "callback-time registration entered the sealed queue");
    }

    private static void isolatesRegistrationFailures()
    {
        ClientApiCompat.StructuralRegistrationWindow<String> window = window("key binding");
        List<String> applied = new ArrayList<>();

        check(window.register("first", "first", null, "test:first"), "first key registration was rejected");
        check(window.register("exception", "exception", null, "test:exception"), "exception registration was rejected");
        check(window.register("linkage", "linkage", null, "test:linkage"), "linkage registration was rejected");
        check(window.register("last", "last", null, "test:last"), "last key registration was rejected");

        window.consume(value ->
        {
            if (value.equals("exception"))
            {
                throw new IllegalStateException("deterministic registration exception");
            }

            if (value.equals("linkage"))
            {
                throw new NoClassDefFoundError("deterministic registration linkage failure");
            }

            applied.add(value);
        });

        check(applied.equals(List.of("first", "last")),
            "one failing structural registration prevented later registrations: " + applied);
    }

    private static void keepsSnapshotsImmutable()
    {
        ClientApiCompat.StructuralRegistrationWindow<String> window = window("entity renderer");

        check(window.register("first", "first", null, "test:first"), "snapshot registration was rejected");

        try
        {
            window.snapshot().add("mutation");
            throw new AssertionError("registration snapshot was mutable");
        }
        catch (UnsupportedOperationException ignored)
        {}
    }

    private static void productionFacadeAndNeoForgeEventsStayWired()
    {
        String facade = compact(readSource("src/client/java/mchorse/bbs_mod/api/client/BBSClientApi.java"));
        String events = readSource("src/client/java/mchorse/bbs_mod/client/BBSClientNeoEvents.java");

        check(facade.contains(
                "public static KeyMapping registerKeyBinding(KeyMapping keyBinding) "
                    + "{ return ClientApiCompat.registerKeyBinding(keyBinding); }"),
            "public key-binding facade no longer delegates to the sealed compatibility window");
        check(facade.contains(
                "public static KeyMapping registerKeyBinding(BBSAddonDescriptor descriptor, KeyMapping keyBinding) "
                    + "{ return ClientApiCompat.registerKeyBinding(descriptor, keyBinding); }"),
            "descriptor-aware key-binding facade no longer delegates to the sealed compatibility window");
        check(facade.contains(
                "public static <T extends Entity> void registerEntityRenderer(EntityType<T> type, EntityRendererProvider<T> factory) "
                    + "{ ClientApiCompat.registerEntityRenderer(type, factory); }"),
            "public entity-renderer facade bypasses the structural registration window");
        check(facade.contains(
                "public static <T extends Entity> void registerEntityRenderer( BBSAddonDescriptor descriptor, "
                    + "EntityType<T> type, EntityRendererProvider<T> factory ) "
                    + "{ ClientApiCompat.registerEntityRenderer(descriptor, type, factory); }"),
            "descriptor-aware entity-renderer facade bypasses the structural registration window");
        check(facade.contains(
                "public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, "
                    + "BlockEntityRendererProvider<? super E> factory) "
                    + "{ ClientApiCompat.registerBlockEntityRenderer(type, factory); }"),
            "public block-entity-renderer facade bypasses the structural registration window");
        check(facade.contains(
                "public static <E extends BlockEntity> void registerBlockEntityRenderer( BBSAddonDescriptor descriptor, "
                    + "BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory ) "
                    + "{ ClientApiCompat.registerBlockEntityRenderer(descriptor, type, factory); }"),
            "descriptor-aware block-entity-renderer facade bypasses the structural registration window");

        String keyMappings = method(events,
            "private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)",
            "private static void onRegisterClientExtensions(RegisterClientExtensionsEvent event)");
        assertOrdered(keyMappings,
            "ClientApiCompat.closeKeyMappingRegistrationWindow();",
            "try",
            "BBSModClient.registerKeyMappings(event::register);",
            "finally",
            "ClientApiCompat.registerQueuedKeyMappings(event::register);");

        String renderers = method(events,
            "private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)",
            "private static void registerCompatEntityRenderer");
        assertOrdered(renderers,
            "ClientApiCompat.closeRendererRegistrationWindows();",
            "try",
            "event.registerEntityRenderer(BBSMod.ACTOR_ENTITY.get(), ActorEntityRenderer::new);",
            "finally",
            "ClientApiCompat.registerQueuedEntityRenderers",
            "ClientApiCompat.registerQueuedBlockEntityRenderers");
    }

    private static String readSource(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            Path source = current.resolve(relativePath);

            if (Files.isRegularFile(source))
            {
                return read(source);
            }

            Path nestedSource = current.resolve("new").resolve(relativePath);

            if (Files.isRegularFile(nestedSource))
            {
                return read(nestedSource);
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relativePath);
    }

    private static String read(Path source)
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

    private static String method(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start, "could not locate production registration method: " + startMarker);

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, previous + 1);

            check(next > previous, "production registration order drifted at: " + marker);
            previous = next;
        }
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static ClientApiCompat.StructuralRegistrationWindow<String> window(String kind)
    {
        return new ClientApiCompat.StructuralRegistrationWindow<>(kind, "TestRegistrationEvent");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
