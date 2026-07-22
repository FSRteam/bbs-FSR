# FSR Hot Plugin Runtime

The FSR Hot Plugin Runtime is a runtime-owned extension path for trusted local
code and content. It is separate from NeoForge mod loading and from the
startup-only Addon API 2.0. A hot plugin is not a NeoForge mod and does not get
to add registrations to the NeoForge mod bus, registries, payload types,
Mixins, or access transformers.

## Install, Replace, Remove

Place one plugin JAR per plugin in:

```
config/bbs/plugins/
```

The manager creates this directory and the per-plugin data directory
`config/bbs/plugin-data/` when it starts. The watcher observes only the top
level directory and only `.jar` files.

With the default `autoApply=true` policy:

| File operation | Runtime result |
| --- | --- |
| Create a stable JAR | Validate, shadow-copy, prepare, start, and publish a new generation. |
| Replace or overwrite a JAR | Prepare the candidate generation while the incumbent remains active, then atomically switch the active route. |
| Delete the active JAR | Stop new calls, drain in-flight calls, close owned contributions and the generation loader, then mark it logically unloaded. |
| Write a partial JAR | Wait for debounce and two matching fingerprints. The incomplete file is not loaded. |

No rescan command is needed for these normal operations. The watcher emits
intents; the single lifecycle executor performs all state changes serially.
`PluginDirectoryWatcher` uses a 750 ms debounce, a 250 ms stability interval,
and periodic reconciliation. `OVERFLOW`, an invalid watch key, startup, and a
manual rescan all use full directory reconciliation.

The current programmatic control surface is `BBSPluginManager`:
`start()`, `rescan()`, `reload(String pluginId)`, `enable(String pluginId)`,
`disable(String pluginId)`, `setAutoApply(boolean)`, `diagnostics()`, and
`close()`. `BBSMod.getPluginDiagnostics()` exposes the status snapshot to host
code. There is currently no `/bbs plugins` command or persisted hot-plugin
configuration file. Setting `autoApply=false` leaves watcher changes in
`RELOAD_PENDING`; an explicit `rescan()` or `reload(...)` is then required.

## Artifact Layout

Every JAR must contain exactly one strict JSON manifest at
`META-INF/bbs-plugin.json`, with schema version `1`. The default validator
limits are 64 MiB per JAR, 16,384 entries, 32 MiB per entry, 256 MiB total
uncompressed data, a 64 KiB manifest, 256 characters per string, and 128
collection entries.

Plugin IDs are portable lower-case identifiers (`[a-z0-9_.-]`, at most 64
characters, no `..`, and no Windows device name). The `api` field accepts an
exact numeric version or an interval. The current hot SPI version is
`BBSPluginApiVersion.CURRENT`, currently `"1.0"`; `[1.0,2.0)` is the usual
range.

### Code plugin

`kind=code` uses one classloader per `(id, generation)`. A common plugin must
declare `commonEntrypoint`; a `side=common` plugin may also declare a client
entrypoint. Both entrypoints implement `BBSPlugin`.

```json
{
  "schema": 1,
  "kind": "code",
  "id": "example_hot",
  "displayName": "Example hot plugin",
  "version": "1.0.0",
  "api": "[1.0,2.0)",
  "commonEntrypoint": "example.ExamplePlugin",
  "clientEntrypoint": "example.client.ExampleClientPlugin",
  "side": "common",
  "capabilities": ["events"],
  "dependencies": [],
  "reload": "hot"
}
```

`side=client` requires only `clientEntrypoint` and is rejected on a dedicated
server. `side=dedicated_server` requires only `commonEntrypoint` and is
rejected on a physical client. On an integrated server, a physical client
loads both entrypoints for a `side=common` artifact. A dedicated server never
resolves or initializes a client entrypoint.

The entrypoint contract is intentionally small:

```java
public final class ExamplePlugin implements BBSPlugin {
    @Override
    public void prepare(BBSPluginContext context) throws Exception {
        context.events().subscribe(RegisterFormCategoriesEvent.class,
            event -> context.diagnostics().info("EVENT", "callback received"));
        context.own(new AutoCloseable() {
            @Override
            public void close() {
                // Release any host-independent resource owned by this generation.
            }
        });
    }

    @Override
    public void start() throws Exception {
        // Runtime work begins only after all entrypoints have prepared.
    }

    @Override
    public void stop(BBSPluginStopReason reason) throws Exception {
        // Optional early cleanup. The host ledger still closes everything.
    }
}
```

`BBSPluginContext.events()` accepts host-owned BBS event classes and returns an
`AutoCloseable` subscription. The host also records that subscription in the
generation ledger. Registration remains open through `prepare()` and
`start()`, then is sealed before the generation becomes active.
`context.own(...)` records additional
`AutoCloseable` resources for reverse-order, idempotent cleanup.

### Content plugin

`kind=content` has no Java entrypoint and cannot contain `.class` files. The
current host builds a host-owned `PluginContentSnapshot` and routes
`assets/**` through `PluginRoutingSourcePack`; a replacement changes the
active snapshot without retaining a content plugin classloader.

```json
{
  "schema": 1,
  "kind": "content",
  "id": "example_language",
  "version": "1.0.0",
  "api": "[1.0,2.0)",
  "side": "common",
  "capabilities": ["resources"],
  "dependencies": [],
  "reload": "hot"
}
```

For this artifact, include files such as
`assets/example_language/strings/hot.txt`. The snapshot classifies
`content/language`, `content/settings`, `content/ui`, and `content/data`, but
the current public plugin context does not expose separate registration
facades for those categories.

## Gradle Packaging

Use Java 21 and compile against the FSR API as `compileOnly`. Keep the plugin
classes in the plugin's own package and put the manifest in resources:

```groovy
plugins {
    id 'java'
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    // Use the FSR host/API artifact supplied by your build, not a second copy.
    compileOnly files('libs/bbs-fsr-api.jar')
}

tasks.named('jar') {
    archiveFileName = 'example-hot-plugin.jar'
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
```

`src/main/resources/META-INF/bbs-plugin.json` is copied by the normal `jar`
task. Do not embed `java.*`, `net.minecraft.*`, `net.neoforged.*`,
`mchorse.bbs_mod.*`, `org.slf4j.*`, or `org.pf4j.*` classes. If a private
dependency must be bundled, shade it into a package owned by the plugin.
Structural entries such as `META-INF/neoforge.mods.toml`, Mixin and access
transformer files, coremod services, or native libraries are rejected before
an entrypoint is loaded.

## Capability Matrix

`BBSPluginCapability.hotSafe()` is the host's classification. A hot-safe
capability is still subject to the staged-context contract. The current public
`BBSPluginContext` exposes `EVENTS` and `own(...)` directly; the other
host-owned adapters are internal integration points until a context method is
provided.

| Manifest capability | Classification | Current boundary |
| --- | --- | --- |
| `events` | Hot-safe | `BBSPluginContext.events()` and host event proxy. |
| `network` | Hot-safe | Host-owned broker routes in `AddonPayloadBroker`; do not use a raw NeoForge payload type. |
| `resources` | Hot-safe | Content snapshots and host source-pack proxy; no plugin `ISourcePack` is accepted. |
| `settings` | Hot-safe | Host-owned declarative values only; plugin `BaseValue` objects are not a hot contract. |
| `ui_mirror`, `render_surface`, `film_collaboration` | Hot-safe | Client registries use owner and generation fences; use only a host adapter. |
| `executors` | Hot-safe | Managed resources use `PluginContributionLedger`, `ManagedPluginExecutor`, and `ManagedPluginScheduledExecutor`. |
| `forms`, `clips`, `particles`, `key_mappings`, `entity_renderer`, `block_entity_renderer` | Restart-only | Structural registration is rejected with `RESTART_REQUIRED`. |
| `minecraft_registry`, `mixin`, `access_transformer`, `coremod`, `jni`, `native_library` | Restart-only | Rejected before class loading or hot contribution publication. |

The host-owned runtime types `PluginOwner`, `PluginGenerationFence`,
`PluginContributionLedger`, and `ManagedPluginResources` are implementation
building blocks, not a substitute for a public context facade. Do not retain
them or call global registries directly from plugin code.

## Lifecycle and Ownership

Each generation has a `PluginOwner` `(pluginId, generation)`, a separate
`PluginGenerationClassLoader` for code artifacts, a generation fence, and a
`PluginContributionLedger`. The manager prepares and starts a candidate before
publishing it. An incumbent remains active until the candidate is complete.

After publication, new dispatches acquire only the new generation. The old
generation enters `DRAINING`; after the five-second drain bound, the manager
calls `stop(BBSPluginStopReason.RELOAD)` (or `REMOVED`, `DISABLED`,
`SHUTDOWN`, or `FAILURE`), closes ledger entries in reverse order, and closes
the classloader. Managed executors and schedulers fence late callbacks and
are closed with a bounded timeout. Disconnect and world close do not unload a
process-owned generation; runtime shutdown does.

Only host-owned and staged contributions are covered by candidate rollback.
An exception or `LinkageError` during `prepare` or `start` closes the
candidate and leaves the incumbent route active. A callback exception is
reported with the plugin status; a generation that has been retired is fenced
from new calls.

## Diagnostics and Rollback Limits

`BBSPluginManager.PluginStatus` reports `pluginId`, `version`, `generation`,
`sha256`, `BBSPluginState`, `lastTransitionMillis`, `lastCode`, `lastMessage`,
and `lastErrorType`. Common states include `VALIDATED`, `STAGED`, `ACTIVE`,
`RELOAD_PENDING`, `FAILED`, `INCOMPATIBLE`, `DISABLED`,
`LOGICALLY_UNLOADED`, and `RESTART_REQUIRED`. The status API is a latest
snapshot, not an unbounded history.

The artifact store keeps content-addressed copies under
`config/bbs/plugin-cache/<id>/<sha256>/plugin.jar` and rotates a bounded
`previous.json` pointer. A candidate that fails before publication does not
replace the active generation. There is no public manager method that
automatically restores the previous pointer or migrates plugin-private state;
manual recovery means selecting a known-good JAR and running the normal
reload transaction. If drain or deterministic teardown fails, the status is
`RESTART_REQUIRED`; a delayed classloader GC observation is not a proof of
logical unload and is not exposed as a separate public state.

## Windows Replacement

The runtime never executes classes from the user-facing source JAR. It hashes
and validates that file, copies it to the content-addressed shadow store, and
loads the shadow copy. The shadow copy is closed when its generation retires,
so the source JAR can be replaced while Minecraft is running.

For reliable replacement on Windows, build outside the plugin directory, copy
to a temporary file in the same `config/bbs/plugins` directory, close the
writer, and rename the temporary file over the destination. Same-volume
atomic replacement is preferred:

```java
Files.copy(buildJar, temporary, StandardCopyOption.REPLACE_EXISTING);
try {
    Files.move(temporary, destination,
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException unsupported) {
    // Use a controlled same-directory replacement, then let the watcher
    // re-fingerprint the final JAR.
    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
}
```

The validator also fingerprints before and after scanning. If the source
changes during validation or shadow-copy, the candidate is rejected and the
last active generation remains the one serving callbacks and assets.

## Trust Boundary

Hot plugins are fully trusted JVM code, not a security sandbox. Installing a
JAR grants it the ability to execute arbitrary Java code with the Minecraft
process permissions. The validator rejects declared structural payloads and
the classloader keeps host classes parent-first, but it cannot prevent a
plugin from calling an exposed global API, creating an unmanaged thread,
retaining a static reference, opening a socket, or loading native code by
indirection. Such a plugin is outside the hot-unload guarantee and may require
a restart. Restrict write access to `config/bbs/plugins/` accordingly.

For the startup-only Addon API and its frozen v1/v2 behavior, see
[`addon-api-2.md`](addon-api-2.md).
