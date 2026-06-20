# BBS Addon/API 2.0

本文档描述 `new` 中的 NeoForge 1.21.1 Addon/API 2.0。API 2.0 的目标是开放更多扩展能力，同时避免 addon 直接持有 BBS 内部可变 manager。

## 设计原则

- v2 addon 使用 descriptor 声明 id、版本、能力和依赖。
- v2 addon 通过 registration context 注册资源、forms、settings、clips、particles、network 和 runtime event subscriber。
- v1/current addon 继续可用：`BBSAddonRegisterEvent` 与 `BBSMod.registerAddon(String, Supplier<? extends BBSAddonMod>)` 不删除。
- BBS 会记录 addon 诊断：协议版本、状态、接受/拒绝的注册项、warning 和失败 phase。
- addon 回调异常会被隔离，默认不让第三方 addon 崩溃 BBS 核心启动。

## 快速开始

```java
import mchorse.bbs_mod.api.BBSApi;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonRegistrationContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public final class ExampleAddon implements BBSAddon
{
    private static final BBSAddonDescriptor DESCRIPTOR = BBSAddonDescriptor.builder("example_addon")
        .displayName("Example Addon")
        .addonVersion("1.0.0")
        .capability(BBSAddonCapability.SETTINGS)
        .build();

    public static void bootstrap()
    {
        BBSApi.registerAddon(DESCRIPTOR, ExampleAddon::new);
    }

    @Override
    public BBSAddonDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public void register(BBSAddonRegistrationContext context)
    {
        context.settings().register(Icons.PROCESSOR, "example_addon", (builder) ->
        {
            builder.category("general");
            builder.getBoolean("enabled", true);
        });
    }
}
```

外部 NeoForge addon 应在自身 mod 构造/bootstrap 阶段调用 `BBSApi.registerAddon(...)`。如果调用早于 BBS manager 初始化，BBS 会先排队，之后自动接入。

## Descriptor 字段

- `addonId`：稳定 id，只允许小写字母、数字、下划线、连字符和点号。
- `displayName`：诊断和日志名称。
- `addonVersion`：addon 自身版本。
- `apiVersion`：默认当前 API 版本 `2.0`。
- `side`：`COMMON`、`CLIENT` 或 `DEDICATED_SERVER`。BBS 会在注册阶段校验当前运行端；不匹配的 addon 会被跳过并写入 diagnostics。
- `capabilities`：声明需要的能力，例如 `SOURCE_PACKS`、`SETTINGS`、`FORMS`、`CLIPS`、`PARTICLES`、`NETWORK`、`EVENTS`。对应 facade 注册时会强制检查 capability，未声明会返回 `REJECTED` registration result 并写入 diagnostics。
- `requiredMods`：缺失时跳过 addon。
- `optionalMods`：会校验 mod id 格式，并在 diagnostics 中记录每个 optional mod 是否已加载；缺失 optional mod 不会跳过 addon。
- `namespaces`：允许 addon 使用的资源 namespace，默认使用 addon id。
- `compatPolicy`：默认允许 legacy compat。`API2_ONLY` 会记录为禁用 legacy bridge；`LEGACY_COMPAT_ONLY` 只用于 v1 diagnostics，不能用于 API 2.0 注册。

## Common Facades

`BBSAddonRegistrationContext` 暴露以下 facade：

- `resources()`：注册 `ISourcePack`，不直接暴露 `AssetProvider`。
- `forms()`：注册 form id 与 form class，不直接暴露 `FormArchitect`。
- `settings()`：注册 settings module。
- `clips()`：注册 camera/action clip。
- `particles()`：用 component id 和 class name 注册客户端 particle component。服务端只保存类名，客户端 `ParticleParser` 初始化时再加载。
- `network()`：提供 legacy raw-buffer receiver bridge，以及 BBS core-owned addon broker 子协议；它不创建新的 NeoForge payload type。
- `events()`：注册内部 EventBus subscriber，适合 runtime-safe hooks。

每个 facade 返回 `BBSRegistrationResult`，调用者可检查 `accepted()` 和 `status()`。

facade 能力检查如下：

- `resources()` 需要 `BBSAddonCapability.SOURCE_PACKS`。
- `forms()` 需要 `BBSAddonCapability.FORMS`。
- `settings()` 需要 `BBSAddonCapability.SETTINGS`。
- `clips()` 需要 `BBSAddonCapability.CLIPS`。
- `particles()` 需要 `BBSAddonCapability.PARTICLES`。
- `network()` 注册 receiver 需要 `BBSAddonCapability.NETWORK`。
- `events()` 需要 `BBSAddonCapability.EVENTS`。

## Network Facade

`BBSAddonCapability.NETWORK` 表示 addon 需要网络能力，不表示 addon 可以在运行期注册新的 NeoForge `CustomPacketPayload`。未声明该 capability 的 addon 调用 `context.network()` 注册 receiver 会返回 rejected diagnostic，client facade 也会返回 rejected result 或 send failure。

NeoForge 要求 payload type 在 `RegisterPayloadHandlersEvent` 阶段注册。API 2.0 addon 的 `register(...)` 回调晚于这个冻结点，因此自定义 addon 消息走 BBS core 预注册的 broker channel，而不是 addon 动态 channel：

```java
context.network().registerServerReceiver(ResourceLocation.fromNamespaceAndPath("example_addon", "ping"), (server, player, id, buf) ->
{
    int value = buf.readInt();

    FriendlyByteBuf reply = context.network().createBuffer();
    reply.writeInt(value + 1);

    context.network().sendToPlayer(player, ResourceLocation.fromNamespaceAndPath("example_addon", "pong"), reply);
});
```

broker 边界如下：

- NeoForge payload type 固定为 BBS core frozen channels：C2S `bbs:s15`，S2C `bbs:c18`。
- broker frame layout 为：`messageId` UTF `ResourceLocation` 字符串（最多 255 字符）、`bodyLength` int、`body` bytes。
- C2S body 上限为 28 KiB；S2C body 上限为 1 MiB - 4096 bytes。非法 id、超限、截断 body、尾随字节和未绑定子协议都会 drop 并记录 `[BBS-SEM] topic=net.addon_broker`。
- addon message id 必须使用 descriptor `namespaces` 中声明的 namespace；默认 namespace 是 addon id。
- `bbs` namespace 保留给 BBS core，addon broker message id 不能使用 `bbs:*`。
- 同一个 message id 只能被一个 addon server receiver 和一个 client receiver 绑定；重复注册返回 duplicate/rejected result。

服务端可通过 `BBSNetworkRegistry` 发送 S2C broker 消息：

```java
FriendlyByteBuf buf = context.network().createBuffer();
buf.writeUtf("payload");
context.network().sendToPlayer(player, ResourceLocation.fromNamespaceAndPath("example_addon", "message"), buf);
context.network().sendToPlayersTrackingEntity(entity, id, buf);
context.network().sendToPlayersTrackingEntityAndSelf(player, id, buf);
```

client-only 代码通过 `BBSClientApi` 注册 S2C 子协议并发送 C2S 子协议：

```java
BBSClientApi.registerNetworkReceiver(DESCRIPTOR, ResourceLocation.fromNamespaceAndPath("example_addon", "pong"), (id, buf) ->
{
    int value = buf.readInt();
});

FriendlyByteBuf buf = BBSClientApi.createNetworkBuffer();
buf.writeInt(41);
BBSClientApi.sendNetworkToServer(DESCRIPTOR, ResourceLocation.fromNamespaceAndPath("example_addon", "ping"), buf);
```

legacy raw receiver bridge 仍存在，用于迁移旧 byte-buffer 接线：

```java
context.network().registerLegacyServerReceiver(id, receiver);
```

legacy bridge 边界如下：

- 只接受 BBS 已经注册过的 C2S payload id。
- 不接受 addon 自有 namespace 的动态 payload id。
- 不接受 BBS core-owned frozen channels，也不允许覆盖已有 receiver。
- 不提供 S2C receiver、client receiver 或双向自定义 channel 注册；自定义双向消息应使用 broker 子协议。

## Client Facade

client-only API 位于 `src/client/java/mchorse/bbs_mod/api/client/BBSClientApi.java`。它提供：

- key binding 注册；
- after-entities / after-level render hook；
- HUD render hook；
- client tick、world tick、disconnect、started、stopping hook；
- entity renderer 和 block entity renderer 注册。

client facade 不放入 common lifecycle 接口，避免 dedicated server 加载客户端类。

`BBSClientApi.registerKeyBinding(KeyMapping)` 会把 key mapping 放入 BBS 的 client 队列，并在 NeoForge `RegisterKeyMappingsEvent` 触发时注册。addon 应在自身 client bootstrap/client setup 早期调用；如果调用晚于 `RegisterKeyMappingsEvent`，BBS 会记录 warning，该 key mapping 可能要到下一次启动才会出现在控制设置中。

## v1/current 兼容

以下旧路径继续可用：

- `BBSAddonRegisterEvent`
- `BBSMod.registerAddon(String, Supplier<? extends BBSAddonMod>)`
- `LoaderAccess#getEntrypoints("bbs-addon", BBSAddonMod.class)`
- `ClientApiCompat`
- `NetworkCompat`

BBS 会把 v1 addon 导入诊断索引，状态为 `BRIDGED_LEGACY`，并输出一次性迁移提示。重复 addon id 继续采用 first-wins + reject-later 策略；v2 诊断会记录冲突来源。

v1 时序安全规则：

- `BBSAddonRegisterEvent` 只在 BBS 的 construct 阶段事件窗口内接受注册。
- `BBSMod.registerAddon(String, Supplier<? extends BBSAddonMod>)` 可覆盖外部 NeoForge addon 构造顺序差异，但只接受到 BBS 把 collector bridge 到内部 `EventBus` 之前。
- bridge 完成后，v1 external 注册会被拒绝，而不是只接入 future events。这样可以避免 addon 错过 source packs、forms、settings 等启动注册事件后仍被半初始化运行。
- 如果 addon 需要运行期能力，应迁移到 API 2.0 的 runtime-safe facade，而不是依赖 late v1 注册。

## 诊断

运行时可读取：

```java
BBSApi.addonDiagnostics();
```

每个诊断快照包含：

- addon id、显示名、addon 版本、API 版本；
- 协议：`API2_DECLARED`、`API1_REGISTERED`、`CURRENT_COMPAT`、`METADATA_ONLY`、`INVALID`；
- 状态：`DISCOVERED`、`ACCEPTED`、`BRIDGED_LEGACY`、`REGISTERED_COMMON`、`READY`、`SKIPPED`、`FAILED`；
- accepted/rejected registrations；
- warnings/errors；
- failed phase 和最后错误类型。

## 迁移建议

v1 addon 可以先保持现状，再逐步迁移：

1. 新增 `BBSAddonDescriptor`。
2. 将 `BBSAddonMod` 事件 handler 中的注册逻辑移到 `BBSAddon#register(...)`。
3. 用 facade 注册 settings/forms/source packs，而不是直接保存内部 manager。
4. client-only 逻辑移动到 `BBSClientApi`。
5. 保留 v1 入口一段时间，确认诊断没有 warning 后再移除。
