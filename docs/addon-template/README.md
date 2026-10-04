# A NeoForge addon for BBS FSR — skeleton

A minimal, copyable project that compiles against BBS FSR, loads beside it on a NeoForge 1.21.1
client and server, and registers exactly one thing with BBS. It is the NeoForge counterpart of the
upstream FS 2.6 `docs/addon-template/` (which is Fabric: `fabric.mod.json`, `bbs-addon` entry
points, Loom); the file-by-file mapping is in the migration task's report, not here.

The recipes — descriptor fields, capabilities, every facade, the network broker, the client API —
are in [`../addon-api-2.md`](../addon-api-2.md). The contract boundary and the "not ported yet"
list are in [`../../ADDONS.md`](../../ADDONS.md). This file only explains the skeleton itself.

> **Status.** Every BBS type, method and signature used below was checked against this
> repository's `src/main/java/mchorse/bbs_mod/api/**` at the time of writing. The skeleton itself
> has **not** been compiled or launched: it lives outside the BBS build on purpose, and building it
> needs the BBS jar in `libs/` plus a Gradle/NeoForge download. Expect the usual first-build
> shakedown — a stale `neo_version`, a missing jar, a mapping complaint — rather than a
> ready-made project.

## What it does and does not do

It **does**:

* declare itself in `neoforge.mods.toml`, with a hard dependency on `bbs`;
* register through `BBSApi.registerAddon(DESCRIPTOR, ExampleAddon::new)` from the `@Mod`
  constructor, which is early enough that BBS's own registration window still accepts it;
* refuse to load on a BBS older than the API it was written against (`BBSApi.requireVersion`);
* register one settings module under **BBS's Options → Example Addon**, through
  `context.settings()`.

It **does not**:

* add a form, a clip, a keyframe factory, a renderer, a network channel or a UI panel — those are
  one paragraph each in `../addon-api-2.md` and are deliberately left out so the skeleton stays
  readable;
* ship a Gradle wrapper, a licence, an icon or CI. Copy one from any mod project;
* work against the Fabric FS 2.6 build. The BBS jar it compiles against has to be the NeoForge
  1.21.1 (Mojmap) one, or nothing in it will link.

## Layout

```
docs/addon-template/
├── build.gradle            NeoGradle userdev, pinned like BBS FSR's own build
├── gradle.properties       versions copied from BBS FSR's gradle.properties
├── settings.gradle         the plugin repository (NeoForged maven)
└── src/main
    ├── java/com/example/bbsaddon
    │   ├── ExampleAddon.java          the BBSAddon: descriptor + register()
    │   └── ExampleAddonNeoForge.java  the @Mod class: version gate + registration
    └── resources/META-INF
        └── neoforge.mods.toml         the mod metadata, expanded by processResources
```

## Building it

BBS is not on any repository, so its jar goes into `libs/` by hand:

```
libs/bbs-fsr-<bbs_version>.jar
```

`gradle.properties`' `bbs_version` selects which one is used, and `build.gradle` fails with a
readable message when it is missing. The jar comes from a BBS FSR release, or from `build/libs/`
of a BBS FSR checkout after `gradlew build` — where this branch produces
`BBS_FSR_beta_v0.0.11.jar`, so copy it in under the name above (or point `bbs_version` at whatever
you have, as long as the file name follows `libs/bbs-fsr-<bbs_version>.jar`).

```sh
gradle build      # the addon jar lands in build/libs/
gradle runClient  # BBS and this addon, in a dev client
```

Two things about that dev client are worth knowing before they look like bugs:

* it runs BBS **without Sodium and Iris** — they are BBS's own compile-time dependencies and are
  not pulled in transitively here. BBS's mixins into them log a warning and are skipped, so
  nothing that goes through shaders is exercised by this project;
* `runClient` needs BBS's own assets and config, which the BBS jar provides; if the game starts
  without BBS in the mod list, the `bbs` dependency in `neoforge.mods.toml` is what reports it.

The addon is compiled against BBS by **classpath**, not by remapping: `implementation files(...)`
puts the jar on the compile and run classpath as-is, which is only correct because both sides use
NeoForge 1.21.1's Mojmap names. A Fabric-era BBS jar will compile nothing and must not be used.

## The five moving parts

1. **`neoforge.mods.toml`** — one `[[mods]]` entry and three `[[dependencies.${mod_id}]]`
   entries. The one that matters is `bbs`, `type="required"`, `ordering="AFTER"`: it is what makes
   BBS come up first, so the addon's `@Mod` constructor runs against a BBS that already exists.
   The registration queue below makes an early arrival survivable, but it is not a reason to leave
   the dependency out. A dependency on a *different* mod is a different thing — see
   `requiredMod(...)` in `ExampleAddon`.
2. **`ExampleAddonNeoForge`** — the `@Mod` class. Its constructor is the addon's own entry
   point: the version gate, then `BBSApi.registerAddon(...)`. An addon that arrives before BBS's
   managers are up is queued rather than dropped, so no ordering guesswork is needed here.
3. **`ExampleAddon.descriptor()`** — the descriptor declares the id, the display name, the version
   and the **capabilities**. Capabilities are enforced, not advertised: registering a settings
   module without `BBSAddonCapability.SETTINGS` comes back `REJECTED` and is written to BBS's
   addon diagnostics.
4. **`ExampleAddon.register(context)`** — the only place structural registration is accepted.
   Facades used after the callback returns reject the write, so everything the addon adds belongs
   inside this method.
5. **`processResources`** — expands `${mod_id}` and friends in the toml from
   `gradle.properties`, so the id lives in exactly one place.

The settings module's own labels come from the language files and follow the host's convention:
the module is `exampleaddon.config.title`, its category `exampleaddon.config.general.title`, and
a value `exampleaddon.config.general.enabled`. Without those keys BBS shows the raw key, which is
the addon's problem and not a bug in the host — the same convention BBS's own
`bbs.config.timeline.*` keys follow.

## The version gate

```java
BBSApi.requireVersion(ADDON_ID, 2);
```

`ADDON_ID` is the addon's own id (the same string as the descriptor's and the mod's), and `2` is
the API version this addon is written against. `BBSApi.VERSION` and `BBSApiVersion.MAJOR` are both
`2` on this branch. On an older BBS this throws, and the message names the addon, what it wants
and what is installed — instead of the mismatch surfacing later as a `NoSuchMethodError` in the
middle of editing a film. `BBSApi.isAtLeast(int)` is the non-throwing form.

**Do not compare `BBSApi.VERSION` yourself.** It is a `public static final int` initialised from
another `static final int`, so it is a Java compile-time constant: a direct
`if (BBSApi.VERSION >= 2)` is inlined into your class as `if (2 >= 2)` at the moment *you* compile,
and keeps answering "2" no matter which BBS is actually installed. The gate has to go through the
method, because the comparison then runs inside BBS's own class, which is the one that shipped
with the running game.

## Adding a client half

The skeleton is common-only on purpose. BBS's client API lives in its client source set, and a
class that so much as mentions one of those types cannot be loaded on a dedicated server, so the
split is by source set rather than by `if (dist)`. When the addon needs
`BBSClientApi.registerKeyBinding(...)`, render hooks or a dashboard panel:

1. put the client class in `src/client/java`, and add the source set plus a `runs { client { modSource sourceSets.client } }`
   block to `build.gradle`. The BBS Physics addon (`bbs-physics-engine-neoforge`, developed
   alongside BBS FSR) is a full worked example of exactly that layout;
2. construct it from the common `@Mod` constructor **by reflection**, as that addon does, so the
   common class never links against the client class;
3. set `.side(BBSAddonSide.CLIENT)` on the descriptor, or leave it `COMMON` and register the
   client-only parts from the client bridge only.

## Extension points that are not here yet

FS 2.6 has a much larger addon surface — the `Register*Event` family, `FilmEvents`,
`FormPoseEvents`, `FilmEditEvents`, `TimelineEvents`, `RegisterFilmTools`, structure render parts
and more. FSR has **not** ported all of it yet, and what is missing is **absent, not stubbed**: an
import of a class that has not landed fails to compile, and there is no runtime fallback. The
authoritative list is the *Not ported yet* section of [`../../ADDONS.md`](../../ADDONS.md); the
porting order is the migration task's `research/fs26-api-addon.md`.

The upstream template's own imports are a good illustration of how partial it is. BBS FSR does
still have the v1 reflection channel — `mchorse.bbs_mod.api.BBSAddonMod` plus
`mchorse.bbs_mod.api.Subscribe`, fed through `context.events().registerSubscriber(...)` (which
needs `BBSAddonCapability.EVENTS`) — and it does deliver `mchorse.bbs_mod.api.events.*` and
`mchorse.bbs_mod.api.client.events.*`. But that channel carries FSR's own event classes, and of
the six events the upstream template subscribes to, three — `RegisterSourcePacksEvent`,
`RegisterSettingsEvent` and `RegisterFormModifiersEvent` — do not exist under those names here,
while the `FilmEvents` channel its client half registers listeners on is gone entirely. So the
upstream template does not compile against FSR as it stands, and copying its imports is not a way
to get started.

The API 2.0 path this skeleton uses — `BBSAddon`, the descriptor, and the capability-checked
facades — is the one that is documented and complete for what has landed so far, so a new addon
should start here rather than from the upstream files. When an upstream event class does land, it
arrives as a subscriber method on the v1 channel, not as a change to the skeleton's structure.
