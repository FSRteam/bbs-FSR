# BBS FS Reborn (FSR)

BBS FS Reborn is the NeoForge port of BBS FS for Minecraft 1.21.1. It provides tools for creating animations and other cinematic content inside Minecraft while preserving the NeoForge and Mojmap adaptations maintained in this repository.

## Platform

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21

## Build

On Windows, run:

```powershell
.\gradlew.bat --no-daemon build
```

The built mod is written to `build/libs/`.

The current migration tracks FS `master` at `395f6927`, using FS `1.21.1` at `8b25fbaf` as the Minecraft 1.21.1 API reference. Platform-specific Fabric code is adapted to the existing NeoForge implementation rather than copied directly.

See `LICENSE.md` for license information.
