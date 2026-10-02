# Writing an addon for BBS FSR

FSR (the NeoForge 1.21.1 branch) keeps the addon API 2.0 that this repository has always had,
and adds the contract gate described below. The recipes live in
[`docs/addon-api-2.md`](docs/addon-api-2.md) — start there. This file is the short version: what
is a contract, how to refuse to load on the wrong BBS, and what is not ported yet.

## What is and isn't a contract

`mchorse.bbs_mod.api` and its sub-packages are a contract. A signature in there does not change
without `BBSApi.VERSION` changing with it.

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

Alongside the callbacks above, BBS has an event channel for addons, taken from FS 2.6 verbatim.
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

## Not ported yet

The registration events are in (`mchorse.bbs_mod.api.events` and
`mchorse.bbs_mod.api.client.events`): the `Register*` family, plus `BBSReadyEvent` and
`BBSClientReadyEvent`. Everything under `api` and its sub-packages is a contract, so those do not
move without a version bump.

Still absent from FSR, and **absent rather than stubbed** — code importing them will not resolve:

- `FilmEvents`, `StructureRenderEvents` — no counterpart yet.
- The runtime events: `FilmEditEvents`, `FormPoseEvents`, `TimelineEvents`, `FormPreviewEvents`,
  `FilmGizmoEvents`.
- The remaining registration events: `RegisterL10nEvent`, `RegisterClientSettingsEvent`,
  `RegisterDashboardPanelsEvent`, `RegisterFilmToolsEvent`, `RegisterFormPanelsEvent`,
  `RegisterPreviewOverlaysEvent`, `RegisterReplayActionsEvent`.

They are being ported batch by batch; see the migration task's `research/fs26-api-addon.md` for
the inventory and order. Do not write against them from an addon and expect them to appear.
