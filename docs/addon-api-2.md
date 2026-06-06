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
- `side`：`COMMON`、`CLIENT` 或 `DEDICATED_SERVER`。
- `capabilities`：声明需要的能力，例如 `SETTINGS`、`FORMS`、`PARTICLES`、`NETWORK`。
- `requiredMods`：缺失时跳过 addon。
- `namespaces`：允许 addon 使用的资源 namespace，默认使用 addon id。
- `compatPolicy`：默认允许 legacy compat。

## Common Facades

`BBSAddonRegistrationContext` 暴露以下 facade：

- `resources()`：注册 `ISourcePack`，不直接暴露 `AssetProvider`。
- `forms()`：注册 form id 与 form class，不直接暴露 `FormArchitect`。
- `settings()`：注册 settings module。
- `clips()`：注册 camera/action clip。
- `particles()`：用 component id 和 class name 注册客户端 particle component。服务端只保存类名，客户端 `ParticleParser` 初始化时再加载。
- `network()`：当前提供 legacy raw-buffer receiver bridge，保持 `NetworkCompat` frozen payload 不变。
- `events()`：注册内部 EventBus subscriber，适合 runtime-safe hooks。

每个 facade 返回 `BBSRegistrationResult`，调用者可检查 `accepted()` 和 `status()`。

## Client Facade

client-only API 位于 `src/client/java/mchorse/bbs_mod/api/client/BBSClientApi.java`。它提供：

- key binding 注册；
- after-entities / after-level render hook；
- HUD render hook；
- client tick、world tick、disconnect、started、stopping hook；
- entity renderer 和 block entity renderer 注册。

client facade 不放入 common lifecycle 接口，避免 dedicated server 加载客户端类。

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
