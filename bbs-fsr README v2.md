<p align="center">
  <img src="./docs/banner.png" alt="BBS FS Reborn" width="100%">
</p>

<p align="center">
  <a href="https://github.com/fsrteam/bbs-fsr/releases"><img src="https://img.shields.io/github/v/release/fsrteam/bbs-fsr?style=flat-square&color=2A2A2A" alt="Version"></a>
  &nbsp;
  <img src="https://img.shields.io/badge/minecraft-1.21.1-4A90D9?style=flat-square&logoColor=white" alt="Minecraft 1.21.1">
  &nbsp;
  <img src="https://img.shields.io/badge/neoforge-21.1.x-2A2A2A?style=flat-square" alt="NeoForge 21.1.x">
  &nbsp;
  <img src="https://img.shields.io/badge/java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21">
  &nbsp;
  <a href="./LICENSE.md"><img src="https://img.shields.io/badge/license-see%20LICENSE-2A2A2A?style=flat-square" alt="License"></a>
</p>

<p align="center">
  <strong>The NeoForge port of BBS FS — bringing cinematic and animation tools to Minecraft 1.21.1.</strong>
</p>

<p align="center">
  <em>Blizzard Particles · Sound Disguise · Themeable UI · NeoForge Native</em>
</p>

<p align="center">
  <strong>English</strong> | <a href="./docs/cn/README_zh.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/fsrteam/bbs-fsr/releases">
    <img src="https://img.shields.io/badge/Download-Latest%20Release-2A2A2A?style=for-the-badge&logo=github&logoColor=white" alt="Download Latest Release" height="40">
  </a>
</p>

---

<p align="center">
  <a href="#what-is-bbs-fs-reborn">Overview</a> ·
  <a href="#signature-features">Signature Features</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="#building-from-sources">Build</a> ·
  <a href="#compatibility">Compatibility</a> ·
  <a href="#migration-track">Migration</a> ·
  <a href="#community--support">Community</a> ·
  <a href="#contributing">Contributing</a>
</p>

---

## What is BBS FS Reborn?

BBS FS Reborn (FSR) is the NeoForge port of BBS FS for Minecraft 1.21.1. It provides tools for creating animations and other cinematic content inside Minecraft, while preserving the NeoForge and Mojmap adaptations maintained in this repository.

Whether you're a cinematic creator, animation enthusiast, or modpack developer, FSR brings the BBS FS experience to the modern NeoForge ecosystem — the same feature set, the same creator-first philosophy, on a maintained platform.

|                          | Vanilla Minecraft        | BBS FS (Original)            | **BBS FS Reborn**                       |
| :----------------------- | :----------------------- | :--------------------------- | :-------------------------------------- |
| Cinematic toolset        | Limited / commands only | Full toolset                  | **Full toolset**                        |
| Blizzard particles      | -                         | -                             | **Yes — signature feature**             |
| Sound disguise (3D)      | -                         | -                             | **Yes — signature feature**             |
| Themeable UI             | -                         | -                             | **Yes — signature feature**             |
| Minecraft 1.21.1 support | -                         | -                             | **Yes — native**                        |
| NeoForge 21.1.x support  | -                         | -                             | **Yes — native**                        |
| Mojmap adaptation        | -                         | No                            | **Yes**                                 |
| Build pipeline           | -                         | Upstream                      | **Test-strict, fails on any error**     |
| Migration track          | -                         | Self                          | **Follows FS `master` @ `395f6927`**   |
| Open source              | -                         | Yes                            | **Yes**                                 |

<p align="center">
  <img src="./docs/screenshot-overview.png" alt="BBS FS Reborn Overview" width="800">
</p>

---

## Signature Features

Four things make BBS FS Reborn more than a straight port. They are the reason this repository exists.

### Blizzard Particles

A full particle form system (`ParticleForm` / `VanillaParticleForm`) that turns particle effects into first-class cinematic objects — not a command line, not a spawn egg.

- **Custom texture link** — bind any texture atlas to the particle, including user-supplied assets
- **Six user parameters** (`user1`–`user6`) — animator-controlled float channels that your shaders and presets can read for color, size, lifetime, or anything else
- **Velocity, count, frequency, scattering** — full control over emission rate and direction spread
- **Per-axis offset** — position the emitter with `offset_x` / `offset_y` / `offset_z`
- **Local vs. world space** — toggle per particle system
- **Pausable** — freeze a particle system on the timeline without affecting the rest of the scene

The form is keyframable like any other clip, so particle parameters can evolve across the shot — start slow, ramp up into a blizzard, then collapse to nothing on cue.

<p align="center">
  <img src="./docs/screenshot-blizzard-particles.png" alt="Blizzard Particles" width="800">
</p>

### Sound Disguise

A spatial audio system (`forms/forms/sound/`) that gives any sound a **shape in the world** — not just a point source. Sound is no longer "where the speaker is"; it is "the volume the sound fills."

Two emission shapes are first-class:

- **`SoundSphereForm`** — sound radiates from a sphere; audible inside, silent outside, falloff is the sphere's radius
- **`SoundConeForm`** — sound projects forward through a cone; great for directional narration, spot effects, and stage projection

Around the emission shape, a full acoustic model:

| Property           | What it does                                                                          |
| :----------------- | :------------------------------------------------------------------------------------ |
| `volume` / `pitch` | Per-form playback volume and pitch, keyframable                                       |
| `looping`          | Seamless loop, with optional `loop_interval` silence between iterations              |
| `start_offset`     | Begin playback from any point in the clip                                             |
| `falloff`          | Distance attenuation model (inverse, linear, exponential)                            |
| `ref_distance`     | Distance at which the sound starts to attenuate                                       |
| `rolloff`          | How fast the sound falls off beyond `ref_distance`                                    |
| `air_absorption`   | Frequency-dependent attenuation by distance — high frequencies die first             |
| `reflections`      | Up to 8 early reflections with per-bounce decay, optional block occlusion           |
| `reflection_decay` | Fraction of energy kept per bounce                                                   |

> The shape's extent **is** the audible boundary. The drawn visualization and the audible falloff cannot drift apart — that is the whole point of the system.

Every field is a `Value*`, which means it is serialized and **keyframable for free** — the film editor picks up animatable properties by walking the value tree, so declaring a field is all it takes to get a timeline track. Scrubbing and reverse playback need no special handling.

<p align="center">
  <img src="./docs/screenshot-sound-disguise.png" alt="Sound Disguise" width="800">
</p>

<details>
<summary><strong>View Sound Disguise Components</strong></summary>

<br>

- `AbstractSoundForm` — shared base, source/distance/reflections fields
- `SoundSphereForm` — spherical emission shape
- `SoundConeForm` — conical emission shape, with `SoundConeGeometry`
- `SoundFalloff` — attenuation model enumeration
- `SoundAcoustics` — air absorption and reverb-ish properties
- `SoundReflections` — early reflection solver
- `SoundPlaybackTimeline` — frame-accurate playback scheduler
- `SoundKeyframeValue` — keyframe interpolation for audio properties
- `SoundVoice` — per-source voice manager

</details>

### Themeable UI

The FSR UI is fully themeable through a documented theme pack format (`docs/theme-spec/`). This is not a color picker — it is a complete visual identity system.

- **Colors** — surface chrome / base / raised / deep / divider, accent, text primary/muted, state positive/negative/warning
- **Corner radius** — per-element rounding, not a global switch
- **Textures** — icon atlas, background image, decorative decals
- **UI animations** — per-entry-point enable/duration, easing, spring physics, exit animations, track orchestration
- **Background modes** — solid, image, or custom
- **Inheritance** — `base` points at another theme id; chain depth up to 4, implicit fallback to built-in `dark`
- **Hot reload** — edit JSON, hit the reload button in the Skin panel, no restart
- **Safe failure** — bad JSON, illegal colors, unknown easings fall back to built-in `dark` and log a warning, never crash the game

Built-in themes ship in the jar (`dark`, `light`, `refreshed`); user themes go in `.minecraft/config/bbs/assets/themes/<id>/` and override built-ins by id. The "Export Template" button writes the currently selected theme to the user directory as a starting point.

> Theme spec v1 is implemented (2026-07-24). v2 visual and motion extensions are implemented (2026-07-25): corner radius, spring physics, exit animations, track orchestration, decorative decals, background modes. Run `./gradlew testThemeCore` for the conformance test suite.

<p align="center">
  <img src="./docs/screenshot-themes.png" alt="Themeable UI" width="800">
</p>

<details>
<summary><strong>View Theme Pack Layout</strong></summary>

<br>

```
<theme-id>/
├── theme.json        # required, see docs/theme-spec/README.md §4
└── (optional textures, e.g. icons.png, paths are theme.json-defined)
```

Theme sources:

| Source  | Path                                                        | Notes                                  |
| :------ | :--------------------------------------------------------- | :------------------------------------- |
| Built-in | mod jar `assets/bbs/assets/themes/<id>/`                  | `dark`, `light`, `refreshed` ship with the mod |
| User    | `.minecraft/config/bbs/assets/themes/<id>/`                | same id overrides built-in             |

Apply via: in-game `BBS Panel → Settings (gear) → Skin → Select Theme`. Reload button reapplies JSON without restart.

</details>

### NeoForge Native

The fourth signature feature is the port itself. FSR is not a Fabric mod that happens to run on NeoForge — it is built **on** NeoForge 21.1.x, with Mojmap adaptations maintained in this repository.

- **NeoForge 21.1.x** — native mod loader target, not a compatibility shim
- **Mojmap** — uses Mojang's official mappings, kept in sync with the NeoForge ecosystem
- **Upstream sync** — tracks FS `master` at `395f6927`, using FS `1.21.1` at `8b25fbaf` as the API reference
- **Idiomatic adaptation** — platform-specific Fabric code is adapted to the existing NeoForge implementation rather than copied directly. No leaking Fabric assumptions into the NeoForge codebase.
- **Test-strict build** — any failed test or uncaptured `ERROR` diagnostic fails `build`. What you ship is exactly what was tested.

> If you have tried BBS FS on Fabric and want the same toolset on NeoForge 1.21.1, this is where you land.

---

## Quick Start

1. Make sure you have **Minecraft 1.21.1** with **NeoForge 21.1.x** installed
2. Download the latest release from the [Releases page](https://github.com/fsrteam/bbs-fsr/releases)
3. Drop the downloaded `.jar` into your `mods/` folder
4. Launch Minecraft with the NeoForge profile
5. Open the BBS dashboard, pick a theme in `Settings → Skin`, and start creating — see the [`docs/`](./docs/) folder for feature walkthroughs

> Requires **Java 21**.

---

## Building from Sources

BBS FS Reborn uses the Gradle build tool. The [Gradle wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) is included — no separate Gradle install needed.

### Build Requirements

- **OpenJDK 21** (Eclipse Temurin recommended)
- **Gradle 8.x** (provided by the wrapper)

### Build Command

On Windows:

```powershell
.\gradlew.bat --no-daemon build
```

On macOS / Linux:

```bash
./gradlew --no-daemon build
```

The built mod is written to `build/libs/`.

### Test-Strict Mode

The build is **test-strict**: any failed test or uncaptured `ERROR` diagnostic fails `build`. Use the explicit override below only when a package is needed despite test failures:

```powershell
.\gradlew.bat --no-daemon build -PforceBuild=true
```

> The forced mode does **not** ignore compilation, resource-processing, or packaging failures. It only bypasses the test-strict gate — use with care.

### Theme Conformance Test

Theme parser and built-in themes are byte-verified by:

```bash
./gradlew testThemeCore
```

This suite parses every example theme in `docs/theme-spec/example-theme/` and byte-checks the built-in themes against the spec.

---

## Compatibility

| Component          | Version / Requirement              |
| :----------------- | :--------------------------------- |
| Minecraft          | 1.21.1                             |
| NeoForge           | 21.1.x                             |
| Java               | 21 (OpenJDK recommended)           |
| Mod loader family  | NeoForge (not Fabric / Forge)      |

> Other Minecraft or NeoForge versions are not officially supported. Installing on mismatched versions will likely fail or crash.

---

## Migration Track

The current migration tracks FS `master` at `395f6927`, using FS `1.21.1` at `8b25fbaf` as the Minecraft 1.21.1 API reference.

Platform-specific Fabric code from the upstream is **adapted to the existing NeoForge implementation** rather than copied directly. This keeps the port idiomatic to NeoForge and avoids leaking Fabric-specific assumptions into the codebase.

---

## Community & Support

- **Bug reports & feature requests** → [Issue Tracker](https://github.com/fsrteam/bbs-fsr/issues)
- **Security reports** → see [`SECURITY.md`](./SECURITY.md)
- **Discussions & Q&A** → [GitHub Discussions](https://github.com/fsrteam/bbs-fsr/discussions)

When filing a bug, please use the provided issue templates — it speeds up triage significantly.

---

## Contributing

Contributions are welcome. Before opening a pull request:

1. Read the [Contributing Guide](./CONTRIBUTING.md)
2. Review the [Code of Conduct](./CODE_OF_CONDUCT.md)
3. Use the [PR template](./.github/PULL_REQUEST_TEMPLATE.md)

> The build is test-strict — make sure `./gradlew --no-daemon build` passes locally before submitting.

[![Contributors](https://contrib.rocks/image?repo=fsrteam/bbs-fsr)](https://github.com/fsrteam/bbs-fsr/graphs/contributors)

---

## License

See [`LICENSE.md`](./LICENSE.md) for license information.

<hr>

<p align="center">
  BBS FS Reborn · Maintained by the FSR Team
</p>
