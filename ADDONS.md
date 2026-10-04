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

These are also in: `FilmEvents`, `StructureRenderEvents`, and the runtime events `FilmEditEvents`,
`FormPoseEvents`, `TimelineEvents`, `FormPreviewEvents`, `FilmGizmoEvents`.

Still absent from FSR, and **absent rather than stubbed** — code importing them will not resolve:

- `RegisterFilmToolsEvent` — its signature names `FilmEditorTool`, which does not exist. Upstream's
  tool extends an element and is handed a `FilmTarget` per actor; neither the tool nor the target
  has been designed here yet, so writing the signature now would freeze a shape before there is
  anything to shape it to. It lands with the editor-tool work, not before.
- `FormPoseEvents.CLAIM_CHAIN` — deliberately undefined. Its signature hands a `FormBone` to each
  listener, and FSR has no per-bone group on a model form: a bone's physics settings live in the
  form's `physics` blob instead of in a bone. A chain claim is addressed *by bone*, so a stand-in
  signature would be a fake contract that changes the day the bone model arrives. It lands with
  `ValueBones`/`FormBone` (P1, upstream `4339b076a`), and B9·B1b preserves a file's `bones` data
  in the meantime so nothing is lost while it waits.

Note that everything else listed as ported above is real: it compiles, and it is declared. Read
that second word carefully for the runtime events named two paragraphs up — on `HEAD` all seven of
them (`FilmEvents`, `FilmEditEvents`, `FilmGizmoEvents`, `FormPoseEvents`, `FormPreviewEvents`,
`StructureRenderEvents`, `TimelineEvents`) compile and can be subscribed to, and **nothing
dispatches them yet**: their `invoker()` call sites exist only in the in-flight `task-21` work (a
`HEAD` count finds zero reachable dispatch points; the working tree has them). An addon that
subscribes today is collected and never called — the same shape as the three registrations below,
and the reason "it compiles" is not offered here as "it works". What is missing is named here one
item at a time rather than dismissed by section, because an addon can
afford to miss a feature and cannot afford to be told a class exists when it does not. An addon
that registers into `RegisterFormPanelsEvent`, `RegisterPreviewOverlaysEvent` or
`RegisterReplayActionsEvent` gets a factory it can read back from that event class
(`getPanels()`/`getOverlays()`/`getActions()`); the wiring that turns those factories into panels
and controls arrives with the editor work, and until it does the factories stay inert.

They are being ported batch by batch; see the migration task's `research/fs26-api-addon.md` for
the inventory and order. Do not write against the two items above from an addon and expect them to
appear.
