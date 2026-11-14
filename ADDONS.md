# Writing an addon for BBS FSR

FSR (the NeoForge 1.21.1 branch) keeps the addon API 2.0 that this repository has always had,
and adds the contract gate described below. The recipes live in
[`docs/addon-api-2.md`](docs/addon-api-2.md) — start there. This file is the short version: what
is a contract, how to refuse to load on the wrong BBS, and where extensions run.

## What is and isn't a contract

`mchorse.bbs_mod.api` and its sub-packages are a contract. Stable signatures do not change
without `BBSApi.VERSION` changing with them. An API marked `@Experimental` may change without
a version bump; check its documentation before depending on it.

Everything outside that package is BBS's own business and moves without notice. An addon
reaching into it is on its own, and the breakage shows up in the game rather than on the build.

## Getting in

An addon declares itself in its own `neoforge.mods.toml` and registers from its mod constructor
or bootstrap:

```java
BBSApi.registerAddon(DESCRIPTOR, ExampleAddon::new);
```

`BBSApi` queues the registration when it arrives before BBS's own managers are up, so an addon
does not have to guess the right moment. If the addon is client-only or server-only, say so in
the descriptor (`BBSAddonSide`) rather than guarding every registration by hand — a mismatch is
skipped with a diagnostic instead of crashing.

From there BBS drives the addon through `BBSAddonPhase`: `DISCOVER`, `REGISTER_COMMON`,
`COMMON_SETUP`, `REGISTER_CLIENT`, `CLIENT_SETUP`, `RUNTIME`, `UNLOAD`. Structural registration
is only accepted inside the registration callbacks; a retained context returns rejected
diagnostics afterwards.

## Versioning

Two different questions live on `BBSApi`, and both are answered:

- `BBSApi.currentApiVersion()` — the human-readable FSR API version (`"2.0"`). This is what the
  descriptor declares by default.
- `BBSApi.VERSION` — the same numbers as an `int`, for the contract gate.
  `BBSApi.VERSION` and `BBSApiVersion.MAJOR` are the same value, so they cannot drift apart.

An addon that needs a feature introduced in a given API version says so, once, at the top of its
entry point:

```java
BBSApi.requireVersion("my_addon", 2);
```

On an older BBS this throws with the mod id, the required version and the version actually
present, which reads as "this addon does not fit this build" instead of "the game crashed on the
first right click". `BBSApi.isAtLeast(int)` is the non-throwing form, and
`BBSApi.getModVersion()` returns BBS's own version (or `"unknown"` when no loader is installed,
which is what the headless test harnesses see).

## Extending one of BBS's forms

An addon extending a BBS form subclasses it and registers the subclass through
`context.forms()`. Renderers and editors are looked up by the form's own class first and then
along its superclass chain, so a subclass inherits the parent's renderer and editor and only
overrides what it actually changes.

## Subscribing to events

Alongside the callbacks above, BBS has the FS 2.6 event family for addons.
An addon hands over an object whose `@Subscribe` methods take exactly one event parameter, and
BBS keeps it:

```java
public final class MyAddon implements BBSAddonMod
{
    @Subscribe
    public void onSections(RegisterFormSectionsEvent event)
    {
        event.register((categories) -> new MyFormSection(categories));
    }
}
```

On NeoForge, register this marker subscriber from the addon's mod constructor or bootstrap,
before BBS common setup:

```java
EventBus.INSTANCE.register(new MyAddon());
```

Register subscribers mentioning client events from a client-only bootstrap. FSR's API2
`context.events().register(subscriber)` also registers the object on this channel, but that
callback runs after the early source-pack, keyframe-factory, form and clip events have already
been posted. Use constructor/bootstrap registration for those events. API2's resource, form and
clip facades remain available in its registration callback.

Two things are worth knowing about it:

- It is **not** `mchorse.bbs_mod.events.EventBus`. That bus carries BBS's own events and is not
  a contract; this one carries `mchorse.bbs_mod.api.events.*` and
  `mchorse.bbs_mod.api.client.events.*` to addons and is. Neither forwards into the other.
- Ordering comes from BBS's initialization, not from the channel. There is no priority, no
  lifetime and no cancellation — a subscriber is called when the phase it registered in reaches
  the point that posts the event.

Methods are collected along the class hierarchy, so shared subscriptions can live in a base
class. The most specific declaration wins: an override replaces the method it overrides, and an
override that drops `@Subscribe` unsubscribes it. A subscriber that throws is logged and the
other subscribers still run.

## Registration and assets

Common initialization posts `RegisterSourcePacksEvent`, `RegisterKeyframeFactoriesEvent`,
`RegisterFormsEvent`, `RegisterFormModifiersEvent`, both clip registration events, and
`RegisterSettingsEvent` before `BBSReadyEvent`. Client initialization posts the client
registration events before `BBSClientReadyEvent`. `RegisterDashboardPanelsEvent` is posted when
a dashboard is constructed, so its panel instances belong to that dashboard.

```java
@Subscribe
public void onSources(RegisterSourcePacksEvent event)
{
    event.registerAddon("my_addon", MyAddon.class);
}

@Subscribe
public void onSettings(RegisterSettingsEvent event)
{
    event.register(Icons.GEAR, "my_addon", builder ->
    {
        builder.category("general");
        builder.getBoolean("enabled", true);
    });
}
```

The source pack reads `assets/my_addon/assets` from the jar containing `MyAddon.class` and
exposes its files under the `my_addon:` resource namespace. Register language files through
`RegisterL10nEvent` before the first language load. `RegisterSettingsEvent` and
`RegisterClientSettingsEvent` are siblings; subscribe to `BaseRegisterSettingsEvent` when one
handler should handle both sides' settings events.

## Editor extensions

- `RegisterFormPanelsEvent`: create and register a fresh panel for each editor. Factories run
  before the shared general/material panels and before a form is assigned; panels receive
  `startEdit` and `finishEdit` normally.
- `RegisterPreviewOverlaysEvent`: return a new UI element for each preview, including auxiliary
  views. The element is attached to that preview and receives its layout and input lifecycle.
- `RegisterReplayActionsEvent`: return an actor-properties control. Its replay supplier follows
  the displayed selection and returns null when no actor is selected; resolve it when acting.
- `RegisterFilmToolsEvent`: supply a `FilmEditorTool` for each scene-owning film controller.
  Tool targeting uses `FilmTarget`, including stable replay identities and nested form paths.
- `RegisterTrackCategoriesEvent`: register categories and their inclusion predicates during
  startup. Registration finishes before keybind settings load, so category shortcuts are
  configurable and restored from disk. Late registrations and duplicate ids are rejected.

Form renderers and editors use superclass fallback. Clip panels use the actual runtime clip
class. Keyframe editors can be registered by factory or by property; widgets and track styles
are registered through their corresponding events before editors are opened.

## Runtime hooks

`FilmEvents` dispatches after scene creation, before and after entity ticks, after each scene
render, and on controller shutdown. The render callback receives the current FSR
`BBSWorldRenderContext`, with camera, pose stack, consumers, tick delta, matrices and per-pass
frustum. Different viewports have different pass contexts; retain no frustum across renders.

`FilmEditEvents` reports committed, undone and redone edit batches. `FormPoseEvents` participates
in the pose/matrix pipeline, including typed `FormBone` chain ownership through `CLAIM_CHAIN`.
`FormRenderEvents` surrounds real form draws; `FormPreviewEvents` runs after preview rendering.
`FilmGizmoEvents` extends actor gizmos, and `TimelineEvents` extends clip and replay timelines.
`StructureRenderEvents` lets the first listener accepting a render own it; structure parts own
copies of their positions and block-entity NBT. Frame overlays run on the finished frame in
registration order.

Functional runtime events expose `register` and `unregister`. Remove listeners when their owner
is unloaded. The annotation event bus keeps FSR's existing shared subscriber table, so
`EventBus.INSTANCE` and another `EventBus` instance reach the same subscriptions. FSR's internal
event bus remains a separate channel with its own lifecycle rules.

The external fixture in `src/replayKeyframeTest/java/sample/fs26addon` exercises public addon
consumers, lifecycle ordering, render-context forwarding, editor factories, aliases and
category shortcuts. Minecraft mixin access and visible rendering still require client smoke
checks.
