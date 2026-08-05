# BBS Addon/API 2.0

本文档描述 `new` 中的 NeoForge 1.21.1 Addon/API 2.0。API 2.0 的目标是开放更多扩展能力，同时避免 addon 直接持有 BBS 内部可变 manager。

本文新增的 UI Mirror、Render Surface 与 Film 协作接口都属于 **API 2.0 的增量兼容扩展**，不会把版本号改成 `2.1` 或 `2.x`。仓库根目录的独立参考消费者 `bbs-web-remote/` 仍在 descriptor 中显式声明 `.apiVersion("2.0")`。它使用的浏览器 wire `1.1` 与 Minecraft 间协作 wire `1.1` 都是 addon 私有传输版本，不能拿来替代或推导 FSR Addon API 版本。

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
- `network()`：提供 BBS core-owned addon broker 子协议；旧 raw-buffer receiver 签名仅为源码/二进制兼容保留并明确返回拒绝，不创建新的 NeoForge payload type。
- `events()`：注册内部 EventBus subscriber，适合 runtime-safe hooks。

每个 facade 返回 `BBSRegistrationResult`，调用者可检查 `accepted()` 和 `status()`。
所有结构注册方法只在当前 addon 的 `register(...)` / `REGISTER_COMMON` 回调内
有效；callback 返回后，retained context 或 facade 会明确返回 rejected
diagnostic，`discover(...)` / `setup(...)` 中即使强制转型也不能写注册表。
`network()` 的 buffer 创建与发送方法仍可由已注册 receiver 在运行期使用，
但 receiver 绑定本身遵守上述一次性窗口。

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
- 为避免恶意 broker 流量把日志变成第二条拒绝服务路径，C2S `bbs:s15` malformed/unbound/receiver-failure 诊断按 10 秒窗口采样：每连接最多 4 条、全服共享最多 32 条，并只保留最近 1024 个连接的有界状态；S2C `bbs:c18` 在客户端每 10 秒最多记录 8 条。窗口恢复后的首条样本携带 suppression 计数，既保留首批上下文也不会逐包刷 WARN/ERROR。
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

legacy raw receiver 签名仍存在，仅用于保持旧 byte-buffer 接线可编译：

```java
context.network().registerLegacyServerReceiver(id, receiver);
```

当前批准的 frozen table 为 C2S `bbs:s1..s15`、S2C `bbs:c1..c19`，全部由
BBS core 持有；其中 addon broker 固定使用 C2S `bbs:s15` 与 S2C
`bbs:c18`。因此 `registerLegacyServerReceiver(...)` 没有任何 addon 可达
channel，会返回 rejected diagnostic，不能覆盖 core receiver，也不会动态
增加 payload type。自定义双向消息必须使用 broker 子协议。

## Client Facade

client-only API 位于 `src/client/java/mchorse/bbs_mod/api/client/BBSClientApi.java`。它提供：

- key binding 注册；
- after-entities / after-level render hook；
- HUD render hook；
- client tick、world tick、disconnect、started、stopping hook；
- entity renderer 和 block entity renderer 注册；
- host-owned 自定义 Dashboard 面板注册；
- 固定原生 Dashboard 打开、UI Mirror/输入、Render Surface 与 Film 语义协作等
  API 2.0 增量 client-only 合同。

client facade 不放入 common lifecycle 接口，避免 dedicated server 加载客户端类。

结构性 client 注册推荐使用带 descriptor 的重载：
`registerKeyBinding(BBSAddonDescriptor, KeyMapping)`、
`registerEntityRenderer(BBSAddonDescriptor, ...)` 与
`registerBlockEntityRenderer(BBSAddonDescriptor, ...)`。它们必须在 addon 的
client bootstrap 早期调用；NeoForge 对应注册事件开始消费时，BBS 会原子关闭
一次性窗口，之后的调用明确拒绝且不再留在永远无法消费的队列中。
`REGISTER_CLIENT` 入队/拒绝以及 `CLIENT_SETUP` 实际消费结果会把 addon id、
phase 和实现 source 写入同一份 API 2.0 diagnostics。旧的不带 descriptor 重载
继续兼容，但因为无法可靠判断所属 addon，只做本地 source 日志，不伪造某个
addon 的诊断记录。
descriptor-aware 注册在 `REGISTER_CLIENT` 被接受时只表示成功入队；
`CLIENT_SETUP` 实际消费成功后状态才可进入 `REGISTERED_CLIENT`，随后 common setup
最终进入 `READY`。单个 renderer 或 key binding 失败只附加 phase/source 到该
addon 的 diagnostics，不会替换已接受的 common winner 或伪造成功状态。

### 自定义 Dashboard 面板

声明 `BBSAddonCapability.CLIENT_UI` 的 Addon API 2.0 addon 可以在 client bootstrap
期间注册面板。公开合同只接收描述和内容工厂，不暴露 `UIDashboard` 或
`UIDashboardPanel`：

```java
BBSDashboardPanelSpec spec = BBSDashboardPanelSpec.builder("tools")
    .title(IKey.raw("Example tools"))
    .icon(Icons.GEAR)
    .build();

BBSRegistrationResult result = BBSClientApi.registerDashboardPanel(DESCRIPTOR, spec, () ->
{
    UIText status = new UIText("Ready").padding(0, 0);
    UIButton run = new UIButton(IKey.raw("Run action"), (button) ->
        status.text("Action completed"));
    UIElement root = UI.column(8, 12,
        new UIText("Content created by the addon").padding(0, 0),
        status,
        run
    );

    return new BBSDashboardPanelContent()
    {
        @Override
        public UIElement root()
        {
            return root;
        }

        @Override
        public void onOpen()
        {
            status.text("Dashboard opened");
        }

        @Override
        public void onAppear()
        {
            status.text("Panel selected");
        }

        @Override
        public void onDisappear()
        {
            status.text("Panel hidden");
        }

        @Override
        public void onClose()
        {
            status.text("Dashboard closed");
        }
    };
});
```

`"tools"` 是 owner 内的 local id，必须匹配
`[a-z0-9][a-z0-9_.-]{0,63}`。宿主最终使用
`<addonId>:<localId>`，插件不能传完整 id 来冒充其他 owner。同一完整 id 采用
first-wins，后续注册返回 `DUPLICATE`；descriptor、`CLIENT_UI`、spec、title、icon
或 factory 无效时返回 `REJECTED`，并把 `REGISTER_CLIENT` 结果写入 API 2.0
diagnostics。

factory 在 Dashboard 第一次需要投影该贡献时运行，必须返回一个尚未挂到其他父节点的
非空 `UIElement` 根。宿主把根节点铺满内容区域，并在 Minecraft client thread 调用
`onOpen -> onAppear -> onUpdate* -> onDisappear -> onClose`。Dashboard 对象会在多次打开
之间复用，所以插件应把一次 screen session 的资源放在 `onOpen/onClose`，把当前选择态
资源放在 `onAppear/onDisappear`；不要在回调里阻塞、做文件 IO 或切换 screen。单个
factory、生命周期回调或布尔能力查询抛出 `Exception`/`LinkageError` 时会记入
`CLIENT_SETUP` 并隔离，不会阻止 Dashboard 和其他面板工作。

Addon 面板是启动期注册，没有运行期注销 API；更新 Addon JAR 仍需重启。旧的
`RegisterDashboardPanelsEvent` 继续发出，直接监听旧事件的 addon 不需要迁移。

### UI Mirror（API 2.0 增量扩展）

UI Mirror 只存在于 client source set，并继续使用现有 API 版本 `2.0`。它镜像当前实际打开的 BBS `UIScreen`，不会向 addon 暴露 `UIScreen`、`UIBaseMenu`、`UIElement`、`GuiGraphics`、本地路径或 OpenGL texture id。

需要从浏览器等外部控制入口打开原生界面时，声明 `CLIENT_UI` 的 addon 可以调用固定目标 API：

```java
BBSClientApi.requestDashboardOpen(DESCRIPTOR).thenAccept((result) ->
{
    /* accepted mailbox request 在 Minecraft client thread 完成；入队前拒绝可能已完成。 */
    switch (result.status())
    {
        case OPENED -> startWaitingForMirrorSession();
        case ALREADY_OPEN -> {
            if (hasAuthoritativeMirrorSession()) finishOpenRequest();
            else startWaitingForMirrorSession();
        }
        case NO_WORLD, BUSY, STALE, REJECTED, FAILED -> reportOpenFailure(result.message());
    }
});
```

该增量接口只允许打开 BBS 自己的 `Dashboard`，不接受类名、panel id、路径或任意 screen factory，因此不会向 addon 暴露内部 UI 对象。请求可从任意线程提交到 16 项有界 mailbox，每个 addon 同时最多一个 pending request；每个 client tick 最多处理 4 项。缺少 `CLIENT_UI`、空 descriptor 或队列满会明确返回，不会无界排队。已在 drain admission 前 `cancel(false)` 的 future 保证不调用 `UIScreen.open(...)`；disconnect、client stop 以及 screen/world/Replay lifecycle 变化会让旧排队请求返回 `STALE`。

执行时没有世界返回 `NO_WORLD`；当前已经是原生 BBS Dashboard 返回幂等的 `ALREADY_OPEN`；任意其他 Minecraft screen 返回 `BUSY`，绝不替换它。没有其他 screen 时，即使 world Replay 正在播放也允许打开 Dashboard，因为播放、暂停和时间轴仍应经原生 BBS 控件操作。实际 Dashboard 构造、`Minecraft.setScreen(...)` 和已接受 mailbox request 的 future 完成都发生在 Minecraft client thread；descriptor/capability、not-ready、duplicate 或 mailbox-full 等入队前拒绝可以返回 already-completed future。API 不负责浏览器身份或网络鉴权；网络 addon 必须在调用前独立保证只有当前 `CONTROLLER` 能触发，`VIEWER` 不得借此 API 提权。

参考网页 addon 在自己的 additive browser wire `1.1` 中把它编码为固定
13-byte `OPEN_UI`（`0x33`）Dashboard 请求，并返回有界的
`OPEN_UI_RESULT` 控制消息；这不会把 FSR Addon/API 从 `2.0` 升级为
`2.1`。请求结果只表示本次固定目标调用的结果：`OPENED` 必须继续等待
真实 UI Mirror `SESSION`；`ALREADY_OPEN` 仅在浏览器已经持有活动的权威
session 时立即完成，否则也等待竞态中的 `SESSION`。两条分支都不能自行
伪造 session 或输入目标。Core 的 `BBSUiOpenDispatcherTest` 通过
`testUiInputMailbox` 覆盖 capability、mailbox 容量/duplicate、状态、取消和
生命周期 fence；该确定性回归不替代真实 Minecraft 与浏览器的端到端验收。

addon 必须声明 `BBSAddonCapability.CLIENT_UI`，然后在 client bootstrap 注册 initially-inactive subscription，并只在存在已授权观看者时开启 demand：

```java
BBSUiMirrorSubscription uiMirror = BBSClientApi.subscribeUiMirror(DESCRIPTOR, (frame) ->
{
    /* 每个 listener 都有独立、有界、串行的异步 handoff；frame 是 latest-wins。
     * 编码和网络发送仍应放入 addon 自己的有界工作队列。 */
    latestFrame.set(frame);
});

uiMirror.setActive(authorizedViewerCount.get() > 0);
/* client stop 时幂等关闭，释放该 listener 的 handoff worker。 */
uiMirror.close();
```

`subscribeUiMirror(...)` 注册成功后仍异步跟踪 session open/close，因此从 inactive 切到 active 后，第一帧前一定已经排入对应 open；inactive、关闭和重新激活会推进 demand generation，不会把旧 queued frame/asset 交给新观看者。`setActive(false)` 后新帧 capture 和 asset read 均停止，subscription `close()` 保留最终有序 close、排空生命周期事件并回收 worker。旧的 `registerUiMirror(...)` facade 为源码/二进制兼容继续存在，并保持 permanently-active 行为；有运行期 viewer 的 addon 不应使用它。

listener 的 open/asset/frame/close 都在该 listener 自己的 bounded serial handoff worker 上调用，不在 Minecraft render/client thread 或 asset reader thread上同步调用。frame backlog 只保留最新值；open/frame/close 顺序不乱，close 不会被 frame overflow 丢弃。慢或抛异常的 listener 会进入指数退避 quarantine，异常日志限频；其他 listener 与渲染线程不受阻塞。listener 会收到 session open/close 事件以及不可变的 `BBSUiFrame`。每帧携带 session id、单调 frame sequence、逻辑 UI 尺寸、cursor 和有序 draw commands。当前首个垂直切片记录：

- `BBSUiClipPush` / `BBSUiClipPop`；
- 经过当前 `PoseStack` 变换后的 `BBSUiQuad` 四顶点和 ARGB 颜色；
- `BBSUiTextureQuad`：opaque `BBSUiAssetRef`、经过变换的四顶点、归一化 UV 和 ARGB tint；
- `BBSUiSurfaceQuad`：`BBSRenderSurfaceKind`、经过变换的四顶点、归一化 UV 和 ARGB tint；它只描述动态画面在 painter order 中的位置，不携带 GL id；
- 经过当前 `PoseStack` 变换后的 `BBSUiGlyphRun` 边界、文本、颜色和 shadow 标记。

`BBSUiGlyphRun` 的 UTF-8 原文、整体四边形边界和 shadow 是权威布局信息。参考网页 addon 的 wire `1.1` 会原样携带这些字段；缺少 Minecraft glyph atlas 时，浏览器可以把系统字体缩放进权威边界，但不得用 `measureText()` 的结果重新决定 BBS 控件布局。系统字体只保证文字可读和边界一致，不承诺不同操作系统上的字形、emoji 或抗锯齿像素完全相同。

纹理命令首次引用可由 `TextureManager` 的静态 PNG 映射解析时，listener 会异步收到一次 `onAssetAvailable(BBSUiAssetBytes)`。asset id 不包含 `Link`、本地路径或 GL id；bytes DTO 携带 `image/png`、SHA-256 内容哈希和防御复制的编码数据。读取使用单线程、有界队列、单资源大小上限和总缓存上限；读取/哈希结束后只把不可变 bytes 投递到该 listener 的 handoff worker。后注册的 listener 会在资源下一次被绘制时补收 bytes，每个 listener 对同一 opaque id 最多成功通知一次。

单帧命令数有上限；超过上限时 `BBSUiFrame.truncated()` 为 `true`。无 active demand（包括已注册但零观看者）时 recorder 不创建命令列表，也不解析、读取或哈希纹理资源。`Batcher2D` 的 `Texture` 叶绘制路径会被记录；raw integer texture id、自定义 shader、动态/动画纹理和任意 mesh 仍标记为不支持。直接调用 Minecraft renderer/shader 的动态内容通过显式 `BBSUiSurfaceQuad` 放回 painter order，不能把 GL 对象塞入 UI command DTO。

API 2.0 当前定义四种稳定 logical surface kind；这是精确 `2.0` 上的增量扩展，不改变 API 版本号：

- `FILM_PREVIEW`：Film 编辑器使用的世界/演员预览；
- `WORLD_REPLAY`：正在播放的 world Replay；有原生 `UIScreen` 时是控件下方的 full-viewport 背景，没有原生 UI 时可使用只读 placement-only session；
- `MORPH_WORLD_PREVIEW`：Morph/形态选择界面背后的实际世界画面；在原生 UI command 之前记录 full-viewport quad，因此保持 painter-first；
- `FORM_PREVIEW_ATLAS`：形态列表、选择器和列表行里经过 `FormRenderer.renderUI(...)` 的可见 direct-preview。core 先按当前 viewport 裁剪每个可见预览矩形，再取这些矩形的一个 bounding union，只读回这一块 framebuffer 并发布一个 JPEG/一个对应 union quad。它不是逐格 atlas，也不是整页截图；union 内格子间的间隙和同一区域的最终 framebuffer 像素会随裁剪一起传输。

这些栅格画面要求 addon 同时声明 `CLIENT_RENDER`。注册 listener 后，只有 `demand()` 返回 active demand 且对应 surface 当前可用时才会启动 GPU/PBO 捕获与 JPEG 编码：

```java
BBSClientApi.registerRenderSurface(DESCRIPTOR, new BBSRenderSurfaceListener()
{
    @Override
    public BBSRenderSurfaceDemand demand()
    {
        return viewerCount.get() == 0
            ? BBSRenderSurfaceDemand.none()
            : BBSRenderSurfaceDemand.desktop(Set.of(
                BBSRenderSurfaceKind.FILM_PREVIEW,
                BBSRenderSurfaceKind.WORLD_REPLAY,
                BBSRenderSurfaceKind.MORPH_WORLD_PREVIEW,
                BBSRenderSurfaceKind.FORM_PREVIEW_ATLAS
            ));
    }

    @Override
    public void onFrame(BBSRenderSurfaceFrame frame)
    {
        /* 专用 JPEG encoder thread；这里只交给有界 latest-frame 网络队列。 */
        mediaQueue.replace(frame);
    }
});
```

`BBSRenderSurfaceDemand.mobile(kinds)` 的内置上限为 960×540、30 FPS、JPEG quality 68；`desktop(kinds)` 为 1280×720、60 FPS、quality 72。自定义 demand 的帧率上限为 120 FPS，尺寸上限为 1920×1080，JPEG quality 上限为 95；较低帧率仍为兼容其他 addon 而接受，但网页远控插件只提供 30–120 FPS。`demand()` 是运行期需求而不是注册期常量，addon 可以随实际订阅者变化返回新值；多个浏览器共享一次编码时，应先验证每个浏览器声明的档位，再聚合为能够满足所有活动观看者的一个有界 demand。无观看者时必须返回 `none()`，不能为等待网络连接持续做 GPU readback/JPEG 编码。

core 不会在 render thread 同步调用 addon 的 `demand()`。它使用两个 daemon worker、64 项有界队列和每 listener 至多一个 in-flight 采样任务，把最近成功结果缓存给 render/capture path；正常结果约每 50 ms 刷新，超过 1 秒的旧结果自动按 `none()` 处理。抛异常或返回 `null` 会立即 fail closed，并用 250 ms 到 30 秒指数退避重试；慢调用和失败日志都有退避。这个隔离保证慢/故障 addon 不会卡住或逐帧刷爆渲染线程，但 addon 仍应让 `demand()` 只做线程安全快照读取，不执行网络、磁盘或 UI 工作。

参考网页 addon 让媒体订阅自身在 `/ws/media` 握手查询中声明 `smooth|high|original`，或使用 `profile=custom&width=<w>&height=<h>&fps=<30..120>&quality=<30..95>`，而不是把媒体需求混入 UI/control `HELLO`。`mobile -> smooth`、`desktop -> high` 仍作为旧客户端别名。服务器对 literal query 设总长与重复字段限制，非十进制、percent-encoded、缺失或未知 profile 保守回退 high；合法数值被限制到 320×180..1920×1080、30..120 FPS、Q30..95。全部活动观看者共享一次编码，宽、高、FPS 与质量分别取有界最大值；零媒体观看者返回 `none()`。JPEG 媒体 WebSocket 不协商 deflate，UI/control 仍可使用 RFC 7692。

`BBSRenderSurfaceFrame` 只暴露 logical kinds、opaque `generation`、sequence/timestamp、宽高、`image/jpeg`、`flipY` 和只读 encoded bytes。core 使用固定的两个物理 capture lane：`WORLD_LANE` 负责 `FILM_PREVIEW`、`WORLD_REPLAY`、`MORPH_WORLD_PREVIEW` 的 world composite，`UI_LANE` 只负责 `FORM_PREVIEW_ATLAS` 的 UI-region crop。每条 lane 都有独立的 generation、PBO/readback、两缓冲 RGB pool 和 capacity-one latest slot；两个有界 JPEG encoder 都在对应 lane 首次 active capture 时懒启动，跨 generation/session fence 长期复用，只在 client stopping 时终止。两条 lane 共享同一个 client-lifetime sequence allocator，所以 `sequence()` 在整个 Minecraft client 进程和两条 lane 之间都保持正数单调，不会因 demand/kind 暂停或 UI session 切换而重置。

同一 `WORLD_LANE` frame 的 `kinds()` 可以包含 `FILM_PREVIEW`、`WORLD_REPLAY`、`MORPH_WORLD_PREVIEW` 的任意有效组合，表示这些 logical kinds 复用同一物理 JPEG payload；参考 addon 只广播一次 payload，并按确定性的 canonical handle（Film 优先，其次 Replay，再次 Morph）把同帧 logical aliases 指向该物理画面。某个 kind 单独发布时仍保留自己的稳定默认 handle。`FORM_PREVIEW_ATLAS` 永远使用独立 UI lane/handle，不得与 world composite 共享。consumer 必须按 physical handle 分别维护 generation/latest-wins gate；一个 lane 的新 generation 不能淘汰另一个 lane 的有效帧。

`generation()` 在对应 capture stream 启动、kind 集变化或 session fence 时推进。core 把 generation 从 PBO issue 贯穿 poll、RGB lease、JPEG encode 到 publish，旧代结果在 map/发布前丢弃；每个 listener 的 callback admission 与 generation invalidate 使用同一个原子门，invalidate 后不再接纳新旧代 callback，已经接纳的单个 callback 可以完成。参考 addon 继续把 UI session 映射为 wire epoch，并把该 client-lifetime sequence 映射为 media sequence；它还按 physical handle 以 generation 与 `capturedAtNanos` 建立 session 下限并在编码后复验，因此并发中已接纳的旧 callback 也不能套用新 epoch。generation 不替代 wire epoch。OpenGL 读回是 bottom-up，frame 的 `flipY()` 为 `true`；浏览器 surface 合成层只翻转一次。

UI session open/close、disconnect 和 world close 会同时推进两条 lane 的 lifecycle fence、丢弃各自旧 encoder slot，并在 render thread 幂等删除 fence、PBO、FBO 和缩放纹理。只要 client 未停止，后续 demand 可以复用两个长期 encoder worker；无 active demand 时不会继续 issue GPU readback 或 JPEG work。

媒体 payload 本身不定义显示区域。addon 只能在对应 `BBSUiSurfaceQuad` 到达时，按它在 draw-command painter order 中的位置合成 surface；不能根据枚举 ordinal、内部 handle 或“看起来像全屏”的 JPEG 猜测摆放位置。

core 已为两种 world Replay 生命周期提供明确 placement：存在原生 `UIScreen` 时，会在该帧所有原生 UI 命令之前记录一个覆盖完整逻辑视口的 `WORLD_REPLAY` quad，使世界画面位于控件下方；没有 `UIScreen` 但 Replay 正在播放且存在匹配的 surface demand 时，会用当前 window 的 GUI-scaled 宽高和 framebuffer 宽高创建 placement-only synthetic mirror session，并在每帧发布唯一的 full-viewport `WORLD_REPLAY` quad。该 session 保持自己的单调 frame sequence，resize 不换 session；Replay/demand 停止、真实 `UIScreen` 接管、disconnect、world close 或 client stop 时幂等关闭。它不绑定 `BBSUiInputDispatcher`，因此只用于观看，不创造第二套可输入 BBS UI。

`MORPH_WORLD_PREVIEW` 同样在原生 UI 绘制前记录 full-viewport placement，让 Morph/形态选择控件继续由 UI commands 覆盖在实际世界 JPEG 上。`FORM_PREVIEW_ATLAS` 则在本帧 UI/延迟绘制全部 flush 后，按可见 direct-preview 的 union 矩形捕获最终 framebuffer，并在帧尾以该 union 的逻辑 bounds 记录一个 surface quad；浏览器不得把它扩展成整页，也不得假设其中包含逐格可寻址的独立图片。

`testUiSurfacePlacement` 覆盖无订阅不捕获、kind demand 匹配、synthetic session open/resize/close、full-viewport UV/bounds、`MORPH_WORLD_PREVIEW` painter-first 和 `FORM_PREVIEW_ATLAS` union-rectangle placement。surface lifecycle regression 覆盖 client sequence、generation/callback fence 与 encoder latest-slot；参考 addon 的 protocol/server/browser regressions 覆盖独立 physical handle gate、per-handle latest-wins、media profile 解析、atlas UV composition 和只对 surface 启用 high-quality smoothing。上述自动回归仍不能替代真实 GPU、形态缩略图、Morph 世界画面、浏览器和设备验收。

远程输入必须同时提供离散事件与 held-state 快照：

```java
BBSUiRemoteInputState held = new BBSUiRemoteInputState(
    mouseX,
    mouseY,
    pressedMouseButtonMask,
    pressedGlfwKeys,
    modifiers
);

BBSUiInputBatch batch = new BBSUiInputBatch(sessionId, inputSequence, held, events);

BBSClientApi.submitUiInput(DESCRIPTOR, batch).thenAccept((inputResult) ->
{
    /* future 在 Minecraft client thread 完成；不要在该线程 join 或执行网络 I/O。 */
});
```

`events` 支持 mouse button、双轴 scroll、key press/repeat/release 和 text。held-state 会接入 BBS 已有的 key/button 轮询，因此 timeline drag、trackpad 和 modifier key combo 不依赖伪造本地 GLFW 事件。一个 batch 中的事件会按顺序推进临时 held-state，全部完成后再收敛到 batch 的权威最终快照；因此 mouse/key/text 事件必须携带其事件时刻的 modifier。标准 Shift/Ctrl/Alt/Super 位一旦在本 batch 中由对应的左右 GLFW modifier key 持有，就以 pressed-key 时间线为准：释放最后一个物理 key 后，后续非 modifier 事件中的陈旧位不会重新置回；没有物理 key 所有权的虚拟 modifier 仍精确采用事件字段。需要在同一位上从物理 modifier 切换为虚拟 modifier 时，应在物理 release 后开始新 batch。多事件 batch 中的 scroll 必须使用 `new BBSUiScrollEvent(x, y, dx, dy, modifiers)`；兼容的四参数构造器没有事件时刻 modifier，只能在单事件 batch 中从最终快照安全恢复。输入 batch 可从任意线程提交，但总是在 Minecraft client thread 校验并调用原 `UIScreen` / `UIBaseMenu` 输入链；过期 session、重复/倒退 sequence、超限数据和第二个 addon controller 会被拒绝。浏览器控制权释放时调用 `BBSClientApi.clearUiInput(DESCRIPTOR)`，screen close、disconnect 和 client stopping 也会清空 held state，避免粘键。

### Film/Replay 语义协作（API 2.0 增量扩展）

Film 语义协作同样只存在于 client source set，并复用 `CLIENT_UI` capability。公共包 `mchorse.bbs_mod.api.client.film` 只提供不可变 DTO；不会暴露 `Film`、`BaseValue`、`DataPath`、`UIFilmPanel`、`UIElement`、manager 或本地文件路径。

```java
BBSFilmCollaborationSubscription filmSubscription =
    BBSClientApi.registerFilmCollaboration(DESCRIPTOR, new BBSFilmCollaborationListener()
    {
        @Override
        public void onSessionOpened(BBSFilmSession session)
        {
            /* sessionId 仅标识本机当前原生 Film 面板生命周期。 */
        }

        @Override
        public void onLocalMutations(BBSFilmMutationBatch batch)
        {
            /* 快速放入可靠、有界的网络队列；不要在 client thread 编码/发送。 */
        }

        @Override
        public void onCheckpointRequired(BBSFilmCheckpointRequired checkpoint)
        {
            /* 该本地 commit 已推进 revision，但超出普通 mutation 边界；改走整 Film checkpoint。 */
        }

        @Override
        public void onPresence(BBSFilmPresence presence)
        {
            /* 变化采样最多 30 Hz；view/full-sheet id 与 semantic tick/row 用于上下文隔离。 */
        }

        @Override
        public void onSessionClosing(BBSFilmSession session)
        {
            /* 最后一次同步读取窗口；必要时立刻 requestFilmSnapshot，再把结果交给 worker。 */
        }

        @Override
        public void onSessionClosed(long sessionId)
        {
            /* 丢弃该 session 的 pending 操作、presence 与网络映射。 */
        }
    });
```

`BBSFilmMutation` 只有 `SET` 与 `REPLACE_SUBTREE`。路径是相对 Film 根的 `List<String>`，每个 entry 都是原子 value id：id 本身可以包含 `/` 或 `.`，因此 addon/wire 必须逐段 length-prefix 编码，禁止 `join`、`split` 或用字符串 `DataPath` 重建。结构 list 变更由 core 提升到稳定祖先并发送 `REPLACE_SUBTREE`。本地提交使用真正有界的约 100 ms 窗口合并：同路径 latest-wins、父路径覆盖子路径，并在窗口到期形成一个 atomic batch；remote CAS、snapshot、保存、换页、关闭、disconnect 和 client stop 都会先强制 flush。连续 240 Hz 拖动因此约降为 10 batch/s，同时保留最终值。

若合并后超过 mutation 数量、路径、单值或 batch byte 上限，core 不会只写日志后丢失编辑，而是在当前本地 revision 已推进后回调 `onCheckpointRequired(BBSFilmCheckpointRequired)`。`reason()` 是稳定 enum；addon 应折叠未发送队列并走 whole-Film checkpoint。`onSessionClosing` 在 `current` 清空前触发，专门让 final checkpoint 同步取得最后 snapshot；`onSessionClosed` 只做释放，届时已不能再读取 Film。

`BBSFilmSession.revision()`、`BBSFilmMutationBatch.baseRevision()` 和 snapshot 的 revision 是当前客户端中同一个 Film 实例的本地 CAS revision，不是服务器协作 revision。每个成功的本地 batch、远端 batch 或 snapshot apply 都只推进一次 core revision，与 mutation 数量无关。addon 必须独立维护服务器 `collabRevision`：发往服务器时使用自己的协作 revision；收到服务器变更时，以接收端当前 core revision 构造 apply batch。

mutation/snapshot 的 `serverSeq` 是“同一 core Film session、同一 addon provider 内连续的 server-ordered semantic sequence”，不是 raw wire 全局序号，也不能替代 core revision。raw wire sequence/revision 必须先由 addon 严格检查；ACK、presence、成员事件等不进入 core Film mutation 的 wire 消息不消耗此 semantic sequence。远端 mutation、snapshot 以及跳过 echo 的 own broadcast 各成功消费一次；own broadcast 用 `observeFilmServerSequence(...)` 推进而不再次修改 Film。该水位按 addon id 隔离，跨 wire collaboration leave/rejoin 不重置，只在 core Film session 或 subscription 生命周期结束时重置。普通 mutation/observe 的重复、倒退或缺口返回 `RESYNC_REQUIRED`；权威 snapshot 可以建立任意非倒退水位以恢复缺口。

```java
BBSClientApi.requestFilmSnapshot(DESCRIPTOR, localSessionId)
    .thenAccept((result) -> {
        if (result.successful())
        {
            BBSFilmSnapshot snapshot = result.snapshot();
            /* encodedBbsData() 防御复制；转交 worker/network。 */
        }
    });

BBSFilmMutationBatch incoming = new BBSFilmMutationBatch(
    localSessionId,
    currentCoreRevision,
    originLocalOpId,
    serverSeq,
    mutations
);

BBSClientApi.applyRemoteFilmMutations(DESCRIPTOR, incoming)
    .thenAccept((result) -> {
        /* future 在 Minecraft client thread 完成；RESYNC_REQUIRED 时请求 snapshot。 */
    });

BBSClientApi.observeFilmServerSequence(
    DESCRIPTOR,
    new BBSFilmServerSequenceObserveRequest(localSessionId, currentCoreRevision, nextSemanticSeq)
);
```

完整恢复使用 `BBSFilmSnapshotApplyRequest(localSessionId, expectedCoreRevision, serverSeq, encodedBbsData)` 和 `applyRemoteFilmSnapshot(...)`。apply 始终在 Minecraft client thread 对同一个 Film 实例调用 `fromData`；嵌套 echo guard 会阻止远端变更重新进入本地 listener。普通 mutation 按 `BBSFilmRefreshHint` 定向刷新，snapshot 执行完整编辑器刷新。mutation batch 在验证后先捕获 whole-Film backup；任意 target 即使在 `fromData` 中“先改早期 child、后抛异常”，也会用同一个 Film 实例整体恢复。恢复本身失败会关闭损坏 session 并要求重新同步。listener 异常会被隔离；subscription close、Film 换页/关闭、disconnect 与 client stop 都是幂等清场。

远端 presence 不允许 addon 持有或修改 Film UI 对象。addon 把服务器身份与目标本地 revision 包装为 `BBSFilmRemotePresence(participantId, displayName, argbColor, serverSeq, presence)`，再调用 `applyRemoteFilmPresence(...)`；成员离开使用 `BBSFilmPresenceClearRequest` 和 `clearRemoteFilmPresence(...)`。presence 的 `serverSeq` 是每位 participant 的独立非倒退 watermark：30 Hz 更新可重复同一值，并且不消耗 mutation/snapshot/observe 的 provider semantic sequence。

core 会严格校验本地 session/revision。`BBSFilmEditorKind` 标识 camera/replay/action 主编辑器，`BBSFilmEditorView` 进一步区分 clip timeline、Replay list、keyframe dope-sheet 与单轨 graph；Replay keyframe 光标还携带原生完整 `semanticCursorSheetId`，而不是依赖每台客户端可能不同的可见行号。鼠标进入时间线时，presence 携带 Film-global semantic tick 和真实 clip layer/sheet row；`selectedReplayIndices` 与 `selectedKeyframes(sheetId, keyframeIndex)` 是防御复制的稳定选区投影。

最多 8 位参与者会通过原生 `Batcher2D` 绘制具名彩色 badge、匹配 Replay 行的选区、匹配完整 sheet id 的 keyframe 标记，以及只在相同 editor/view/replay scope 可见的裁剪行高亮与时间线光标，所以 Minecraft UI 与网页 draw-command replay 看到同一结果。任意 core revision 变化都会丢弃旧 overlay，绝不把旧 revision presence 改写成当前 revision；下一次最多 30 Hz 的当前协作水位采样再安全补回。显式离开/换协作会话调用 `clearAllRemoteFilmPresence(DESCRIPTOR, localSessionId)`，会按 addon id 一次清掉 overlay、watermark 与 clear tombstone，但不会重置 provider semantic sequence。Replay 列表结构变化、snapshot、session close、subscription close、disconnect 也会清理或失效旧的 revision-scoped selection，避免把旧索引画到另一条 Replay。

`BBSFilmCollaborationLimits` 是 DTO 与 wire 的共同上限源：最多 256 个 mutation，Replay 与 full-sheet keyframe presence selection 合计最多 256 项，最多 32 个 selection sheet id 且字典总计 512 UTF-8 bytes；另有 64 个 path segment、单段 1024 UTF-8 bytes、总路径 16 KiB、单 mutation 16 MiB、batch 32 MiB、snapshot 64 MiB。DTO 在防御复制前先校验 collection size，再校验非负 tick/有界 row、复合 selection 唯一性、sheet 字典、path 与 byte 边界；core apply 入口会再次验证 session、revision、provider sequence、path 和 encoded BBS data 后再修改 Film。

### 参考消费者边界：BBS Web Remote

`bbs-web-remote/` 是独立 Gradle 目录和独立 NeoForge mod，不并入 `new/` 的构建。FSR core 只拥有不可变 DTO、client-thread apply/input 和渲染捕获；HTTP/WebSocket、Jetty、配对码、Cookie、Host/Origin 校验、浏览器 wire、差量缓存与网页 UI 全部由 addon 拥有。默认 loopback、显式 LAN 和权限角色是该 addon 的安全策略，不是 `BBSClientApi` 自动提供的网络服务。

参考 addon 只通过 `CLIENT_UI`、`CLIENT_RENDER` 与 `NETWORK` 能力接入 API 2.0。浏览器端 `VIEWER`/`CONTROLLER` 决定同一个本地 `UIScreen` 是否可输入；NeoForge Film 协作的 `collaboration.use` / `collaboration.create` 权限决定玩家能否加入或创建服务器会话。这两套权限、两套 sequence 与两层协议必须保持独立。安装、LAN 系统属性、配对流程、协作命令、带宽策略和当前 MVP 验收边界见 [`bbs-web-remote/README.md`](../../bbs-web-remote/README.md)。

## v1/current 兼容

以下旧路径继续可用：

- `BBSAddonRegisterEvent`
- `BBSMod.registerAddon(String, Supplier<? extends BBSAddonMod>)`
- `LoaderAccess#getEntrypoints("bbs-addon", BBSAddonMod.class)`
- `ClientApiCompat`
- `NetworkCompat`

BBS 会把 v1 addon 导入诊断索引，状态为 `BRIDGED_LEGACY`，并输出一次性迁移提示。重复 addon id 继续采用 first-wins + reject-later 策略；v2 诊断会记录冲突来源。

v1 时序安全规则：

- v1 与 API 2.0 请求进入同一个 FIFO first-wins addon-id 边界；后来的跨协议 duplicate 在 supplier 执行前拒绝，只运行首个实现，也不能把首个 winner 的 diagnostics 改成 `SKIPPED` / `FAILED`。
- `BBSAddonRegisterEvent` 只在 BBS 的 construct 阶段事件窗口内接受注册。
- `BBSMod.registerAddon(String, Supplier<? extends BBSAddonMod>)` 可覆盖外部 NeoForge addon 构造顺序差异，但只接受到 BBS 把 collector bridge 到内部 `EventBus` 之前。
- external window 会在 bridge snapshot 前、同一临界区内关闭；bridge 开始后的 v1 external 注册会被拒绝，而不是只接入 future events。这样可以避免 addon 错过 source packs、forms、settings 等启动注册事件后仍被半初始化运行。
- v1 supplier 的 `Exception` / `LinkageError` 会隔离为 phase/source diagnostic；null supplier 明确 warning + skipped diagnostic，不会静默消失。
- 如果 addon 需要运行期能力，应迁移到 API 2.0 的 runtime-safe facade，而不是依赖 late v1 注册。

## NeoForge Addon 与 FSR Hot Plugin 的边界

API 2.0 addon 仍由 NeoForge 在启动期发现和初始化。它的 registration window、first-wins addon identity、冻结的 payload channels 和结构性 client registrations 都是启动期合同；替换 addon JAR 不能在运行中卸载它。

FSR Hot Plugin Runtime 是另一条 additive SPI，使用 `BBSPlugin`、`BBSPluginContext` 和 `META-INF/bbs-plugin.json`。把 JAR 放入 `config/bbs/plugins/` 可以触发运行期安装、同 id generation replacement 和删除卸载，具体格式和 side/capability 限制见 [`hot-plugin-runtime.md`](hot-plugin-runtime.md)。现有 NeoForge addon 不会因为实现 API 2.0 而自动变成 hot plugin，也不会共享热插件的 classloader 或生命周期。

只有通过 host-owned hot SPI facade 注册的贡献才在 hot contract 内。当前 forms、clips、
particles、key mappings、已有 entity/block-entity renderer 覆盖以及 Dashboard panels
支持 generation-scoped 热替换；Minecraft registries、Mixins、access transformers、
coremods 和 native libraries 仍要求重启。hot plugins 是完全信任的 JVM 代码，不是
安全沙箱。

## 诊断

运行时可读取：

```java
BBSApi.addonDiagnostics();
```

每个诊断快照包含：

- addon id、显示名、addon 版本、API 版本；
- 协议：`API2_DECLARED`、`API1_REGISTERED`、`CURRENT_COMPAT`、`METADATA_ONLY`、`INVALID`；
- 状态：`DISCOVERED`、`ACCEPTED`、`BRIDGED_LEGACY`、`REGISTERED_COMMON`、`REGISTERED_CLIENT`、`READY`、`SKIPPED`、`FAILED`；
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
