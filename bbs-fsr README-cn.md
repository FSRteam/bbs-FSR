<p align="center">
  <img src="../../docs/banner.png" alt="BBS FS Reborn" width="100%">
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
  <a href="../../LICENSE.md"><img src="https://img.shields.io/badge/license-see%20LICENSE-2A2A2A?style=flat-square" alt="License"></a>
</p>

<p align="center">
  <strong>BBS FS 的 NeoForge 移植版 —— 把电影与动画创作工具带到 Minecraft 1.21.1。</strong>
</p>

<p align="center">
  <em>暴雪粒子 · 音频伪装 · 主题化 UI · NeoForge 原生</em>
</p>

<p align="center">
  <a href="../../README.md">English</a> | <strong>简体中文</strong>
</p>

<p align="center">
  <a href="https://github.com/fsrteam/bbs-fsr/releases">
    <img src="https://img.shields.io/badge/下载-最新版本-2A2A2A?style=for-the-badge&logo=github&logoColor=white" alt="Download Latest Release" height="40">
  </a>
</p>

---

<p align="center">
  <a href="#什么是-bbs-fs-reborn">项目概述</a> ·
  <a href="#独家功能">独家功能</a> ·
  <a href="#快速上手">快速上手</a> ·
  <a href="#从源码构建">源码构建</a> ·
  <a href="#兼容性">兼容性</a> ·
  <a href="#迁移追踪">迁移追踪</a> ·
  <a href="#社区与支持">社区与支持</a> ·
  <a href="#贡献">贡献</a>
</p>

---

## 什么是 BBS FS Reborn？

BBS FS Reborn（简称 FSR）是 BBS FS 在 Minecraft 1.21.1 上的 NeoForge 移植版。它提供了一整套用于在 Minecraft 内部创建动画与电影内容的工具，同时在本仓库中持续维护 NeoForge 与 Mojmap 适配。

无论你是电影创作者、动画爱好者，还是整合包开发者，FSR 都把 BBS FS 的体验带到了现代 NeoForge 生态中——同样的功能集、同样的"创作者优先"理念，跑在一个持续维护的平台上。

|                          | 原版 Minecraft          | BBS FS（原版）               | **BBS FS Reborn**                       |
| :----------------------- | :----------------------- | :--------------------------- | :-------------------------------------- |
| 电影创作工具集            | 有限 / 仅命令            | 完整工具集                    | **完整工具集**                          |
| 暴雪粒子                  | -                         | -                             | **有 — 独家功能**                       |
| 音频伪装（3D）            | -                         | -                             | **有 — 独家功能**                       |
| 主题化 UI                 | -                         | -                             | **有 — 独家功能**                       |
| Minecraft 1.21.1 支持     | -                         | -                             | **有 — 原生**                           |
| NeoForge 21.1.x 支持      | -                         | -                             | **有 — 原生**                           |
| Mojmap 适配               | -                         | 否                             | **有**                                  |
| 构建流水线                | -                         | 上游                           | **测试严格，任何错误即失败**            |
| 迁移追踪                  | -                         | 自身                           | **跟随 FS `master` @ `395f6927`**       |
| 开源                      | -                         | 是                             | **是**                                  |

<p align="center">
  <img src="../../docs/screenshot-overview.png" alt="BBS FS Reborn Overview" width="800">
</p>

---

## 独家功能

四件事让 BBS FS Reborn 不只是一个简单的移植。它们正是本仓库存在的理由。

### 暴雪粒子

一套完整的粒子表单系统（`ParticleForm` / `VanillaParticleForm`），把粒子效果提升为**电影对象的一等公民**——不是命令行、不是刷怪蛋。

- **自定义纹理链接** — 可绑定任意纹理图集到粒子上，包括用户自带的素材
- **六个用户参数**（`user1`–`user6`） — 动画师可控的浮点通道，着色器和预设可读取它们做颜色、尺寸、生命周期或任何用途
- **速度、数量、频率、散射** — 对发射速率和方向扩散有完整控制
- **分轴偏移** — 用 `offset_x` / `offset_y` / `offset_z` 定位发射器
- **本地空间 / 世界空间** — 每个粒子系统可单独切换
- **可暂停** — 在时间线上冻结某个粒子系统，不影响场景其余部分

表单和其他剪辑一样可以打关键帧，所以粒子参数可以随镜头演进——开始慢、推向暴风雪高潮、再按 cue 坍缩为无。

<p align="center">
  <img src="../../docs/screenshot-blizzard-particles.png" alt="Blizzard Particles" width="800">
</p>

### 音频伪装

一套空间音频系统（`forms/forms/sound/`），让任何声音都拥有**在世界中的形状**——不再是一个点声源。声音不再"在扬声器所在的位置"，而是"声音所填充的那个体积"。

两种发射形状是一等公民：

- **`SoundSphereForm`** — 声音从一个球体辐射；内部可听、外部静音，衰减就是球半径
- **`SoundConeForm`** — 声音沿锥体向前投射；适合定向解说、点效果和舞台投射

围绕发射形状的完整声学模型：

| 属性                | 作用                                                                  |
| :------------------ | :-------------------------------------------------------------------- |
| `volume` / `pitch`  | 每表单播放音量与音高，可关键帧                                         |
| `looping`           | 无缝循环，可选 `loop_interval` 控制迭代间静默                          |
| `start_offset`      | 从剪辑的任意点开始播放                                                 |
| `falloff`           | 距离衰减模型（inverse / linear / exponential）                       |
| `ref_distance`      | 声音开始衰减的距离                                                     |
| `rolloff`           | 超过 `ref_distance` 后衰减速度                                        |
| `air_absorption`    | 频率相关的距离衰减——高频先死                                          |
| `reflections`       | 最多 8 次早期反射，每次反弹有独立衰减，可选方块遮挡                    |
| `reflection_decay`  | 每次反弹保留的能量比例                                                 |

> 形状的边界**就是**可听边界。绘制的可视化和可听的衰减永远不会漂移——这正是整套系统的意义所在。

每个字段都是 `Value*`，这意味着它自动被序列化并**可关键帧**——电影编辑器通过遍历值树来收集可动画属性，所以声明一个字段就足以得到一条时间线轨道。回放擦洗与倒放都无需特殊处理。

<p align="center">
  <img src="../../docs/screenshot-sound-disguise.png" alt="Sound Disguise" width="800">
</p>

<details>
<summary><strong>查看音频伪装组件</strong></summary>

<br>

- `AbstractSoundForm` — 共享基类，源/距离/反射字段
- `SoundSphereForm` — 球形发射形状
- `SoundConeForm` — 锥形发射形状，配套 `SoundConeGeometry`
- `SoundFalloff` — 衰减模型枚举
- `SoundAcoustics` — 空气吸收与混响类属性
- `SoundReflections` — 早期反射求解器
- `SoundPlaybackTimeline` — 逐帧精确的播放调度器
- `SoundKeyframeValue` — 音频属性的关键帧插值
- `SoundVoice` — 每源 voice 管理器

</details>

### 主题化 UI

FSR 的 UI 通过文档化的主题包格式（`docs/theme-spec/`）实现完全可主题化。这不是一个颜色选择器——它是一套完整的视觉身份系统。

- **颜色** — 表面 chrome / base / raised / deep / divider，强调色，文字 primary/muted，状态 positive/negative/warning
- **圆角** — 每元素独立的圆角半径，不是全局开关
- **纹理** — 图标图集、背景图、装饰贴花
- **UI 动画** — 每接入点开关/时长、缓动、弹簧动力学、退场动画、track 编排
- **背景模式** — 纯色、图片或自定义
- **继承** — `base` 指向另一个主题 id；链深度最多 4 层，隐式回退到内置 `dark`
- **热重载** — 编辑 JSON，在皮肤面板按重载按钮，无需重启
- **安全失败** — 坏 JSON、非法颜色、未知缓动名会回退到内置 `dark` 并记日志，绝不崩游戏

内置主题随 mod 分发（`dark`、`light`、`refreshed`）；用户主题放在 `.minecraft/config/bbs/assets/themes/<id>/`，按 id 覆盖内置。"导出模板"按钮会把当前选中主题及其同目录资源写入用户主题目录，作为起点。

> 主题规范 v1 已实现（2026-07-24）。v2 视觉与动效扩展已实现（2026-07-25：圆角、弹簧动力学、退场动画、track 编排、装饰贴花、背景模式）。运行 `./gradlew testThemeCore` 可执行一致性测试套件。

<p align="center">
  <img src="../../docs/screenshot-themes.png" alt="Themeable UI" width="800">
</p>

<details>
<summary><strong>查看主题包布局</strong></summary>

<br>

```
<主题-id>/
├── theme.json        # 必需，详见 docs/theme-spec/README.md §4
└── (可选贴图，如 icons.png，路径由 theme.json 引用)
```

主题来源：

| 来源   | 路径                                                        | 备注                                    |
| :----- | :--------------------------------------------------------- | :-------------------------------------- |
| 内置   | mod jar 内 `assets/bbs/assets/themes/<id>/`               | `dark`、`light`、`refreshed` 随 mod 分发 |
| 用户   | `.minecraft/config/bbs/assets/themes/<id>/`               | 同 id 覆盖内置                          |

应用方式：游戏内 `BBS 面板 → 设置（齿轮）→ 皮肤 → 选择主题`。重载按钮重新应用 JSON，无需重启。

</details>

### NeoForge 原生

第四个独家功能就是移植本身。FSR 不是"恰好能在 NeoForge 上跑"的 Fabric mod——它是**构建在** NeoForge 21.1.x 之上的，Mojmap 适配在本仓库内维护。

- **NeoForge 21.1.x** — 原生 mod loader 目标，不是兼容 shim
- **Mojmap** — 使用 Mojang 官方映射，与 NeoForge 生态保持同步
- **上游同步** — 跟踪 FS `master` @ `395f6927`，以 FS `1.21.1` @ `8b25fbaf` 作为 API 参考
- **地道适配** — 上游平台特定的 Fabric 代码被**适配**到既有的 NeoForge 实现，而非直接复制。不会把 Fabric 的假设泄漏到 NeoForge 代码库。
- **测试严格构建** — 任何失败的测试或未捕获的 `ERROR` 诊断都会让 `build` 失败。你发布的，就是被测试过的。

> 如果你在 Fabric 上用过 BBS FS，并想在 NeoForge 1.21.1 上拥有同样的工具集，这就是你要找的地方。

---

## 快速上手

1. 确保你已安装 **Minecraft 1.21.1** 与 **NeoForge 21.1.x**
2. 从 [Releases 页面](https://github.com/fsrteam/bbs-fsr/releases) 下载最新版本
3. 把下载的 `.jar` 放进你的 `mods/` 文件夹
4. 用 NeoForge 配置启动 Minecraft
5. 打开 BBS 面板，在 `设置 → 皮肤` 选择一个主题，然后开始创作——参见 [`docs/`](./docs/) 目录了解功能详解

> 需要 **Java 21**。

---

## 从源码构建

BBS FS Reborn 使用 Gradle 构建工具。仓库自带 [Gradle wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)——无需单独安装 Gradle。

### 构建要求

- **OpenJDK 21**（推荐 Eclipse Temurin 发行版）
- **Gradle 8.x**（由 wrapper 提供）

### 构建命令

Windows：

```powershell
.\gradlew.bat --no-daemon build
```

macOS / Linux：

```bash
./gradlew --no-daemon build
```

构建产物输出到 `build/libs/`。

### 测试严格模式

构建是**测试严格**的：任何失败的测试或未捕获的 `ERROR` 诊断都会让 `build` 失败。仅当某次打包必须无视测试失败时，使用下面的显式覆盖：

```powershell
.\gradlew.bat --no-daemon build -PforceBuild=true
```

> 强制模式**不会**忽略编译、资源处理或打包失败。它只绕过测试严格这一道门——慎用。

### 主题一致性测试

主题解析器与内置主题通过以下命令做逐字节校验：

```bash
./gradlew testThemeCore
```

该套件会解析 `docs/theme-spec/example-theme/` 下的每个示例主题，并对内置主题做与规范一致的逐字节检查。

---

## 兼容性

| 组件               | 版本 / 要求                       |
| :----------------- | :-------------------------------- |
| Minecraft          | 1.21.1                            |
| NeoForge           | 21.1.x                            |
| Java               | 21（推荐 OpenJDK）                |
| Mod loader 家族    | NeoForge（不支持 Fabric / Forge） |

> 不官方支持其他 Minecraft 或 NeoForge 版本。在不匹配的版本上安装大概率会失败或崩溃。

---

## 迁移追踪

当前迁移跟随 FS `master` @ `395f6927`，以 FS `1.21.1` @ `8b25fbaf` 作为 Minecraft 1.21.1 的 API 参考。

上游平台特定的 Fabric 代码被**适配到既有的 NeoForge 实现**，而非直接复制。这样让移植保持对 NeoForge 的地道性，避免把 Fabric 特定的假设泄漏进代码库。

---

## 社区与支持

- **Bug 报告与功能请求** → [Issue 跟踪器](https://github.com/fsrteam/bbs-fsr/issues)
- **安全报告** → 参见 [`SECURITY.md`](./SECURITY.md)
- **讨论与问答** → [GitHub Discussions](https://github.com/fsrteam/bbs-fsr/discussions)

提交 bug 时，请使用提供的 issue 模板——能显著加速分诊。

---

## 贡献

欢迎贡献。在提交 pull request 之前：

1. 阅读[贡献指南](./CONTRIBUTING.md)
2. 阅读[行为准则](./CODE_OF_CONDUCT.md)
3. 使用 [PR 模板](./.github/PULL_REQUEST_TEMPLATE.md)

> 构建是测试严格的——提交前请确保 `./gradlew --no-daemon build` 在本地通过。

[![Contributors](https://contrib.rocks/image?repo=fsrteam/bbs-fsr)](https://github.com/fsrteam/bbs-fsr/graphs/contributors)

---

## 许可证

参见 [`LICENSE.md`](./LICENSE.md) 了解许可证信息。

<hr>

<p align="center">
  BBS FS Reborn · 由 FSR Team 维护
</p>
