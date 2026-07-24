# FSR UI 主题包规范(theme-spec)v1

FSR(BBS mod NeoForge 版)的 UI 支持以"主题包"方式高度自定义:颜色、样式开关、
贴图(图标 atlas / 背景图)与 UI 动画(每个接入点的开关/时长/缓动)。
本文是主题包格式的**唯一规范**;`example-theme/` 是可直接拷贝使用的完整样例。

> 实现状态:已实现(2026-07-24)。运行时:`mchorse.bbs_mod.ui.themes`(ThemeParser/ThemeManager);
> 对拍测试:`src/themeCoreTest`(`./gradlew testThemeCore`,含本规范样例包解析与内置主题逐字节校验)。
> 实现与规范冲突时,修其一并在 Trellis 任务文档记录。

## 1. 主题包是什么

一个主题包 = 一个文件夹:

```
<主题id>/                  ← 文件夹名即主题 id(小写字母、数字、-、_)
├── theme.json             ← 必需,主题文档(本文 §4)
└── (可选贴图,如 icons.png,路径自定,由 theme.json 引用)
```

## 2. 放哪里、如何生效

| 来源 | 位置 | 说明 |
|---|---|---|
| 内置 | mod jar 内 `assets/bbs/assets/themes/<id>/` | `dark`、`light` 等随 mod 分发 |
| 用户 | `.minecraft/config/bbs/assets/themes/<id>/` | 玩家/作者放这里;**同 id 覆盖内置** |

生效方式:游戏内 `BBS 面板 → 设置(齿轮)→ 皮肤 → 选择主题`。
修改 JSON 后点皮肤栏的**重载**按钮即时生效,无需重启。
"导出模板"按钮会把样例包写入用户主题目录,作为起步。

坏文件安全:JSON 语法错误、非法颜色、未知缓动名等都**不会崩溃游戏**——
回退到内置 dark(或该 key 的继承值)并在日志输出 warn。

## 3. 通用规则

- **颜色格式**:字符串 `"#RRGGBB"` 或 `"#AARRGGBB"`(不写 alpha 即不透明)。
- **继承**:`base` 指向另一主题 id,未写的 key 沿继承链取值;链深最多 4 层,禁止循环;
  链尾隐式接内置 `dark` 的完整默认值,因此**任何 key 都可省略**。
- **未知键**:忽略。约定以 `_` 开头的键为注释位(如 `"_doc": "..."`),解析器跳过。
- **版本**:`format` 当前为 `1`;未来不兼容变更会递增,加载器拒载不认识的 format 并回退。

## 4. theme.json 逐 key 规范

### 4.1 元信息

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `format` | int | 必填 | 规范版本,当前 `1` |
| `name` | string | 主题 id | 显示名(任意语言) |
| `author` | string | `""` | 作者名,选择器里展示 |
| `description` | string | `""` | 一句话简介 |
| `base` | string | `"dark"` | 继承的主题 id |
| `variant` | string | `"dark"` | `"dark"` 或 `"light"`;告知 UI 当前是明还是暗体系(个别代码分支据此微调,如 tooltip 配色) |

### 4.2 colors.surface —— 界面表面色(层级由深到浅)

| key | 默认(dark) | 用在哪 |
|---|---|---|
| `colors.surface.chrome` | `#111316` | 最外层镶边:顶栏、侧栏、面板骨架 |
| `colors.surface.base` | `#171A1F` | 面板主体底色 |
| `colors.surface.raised` | `#1D2127` | 浮起元素:卡片、行悬浮底、弹层内容 |
| `colors.surface.deep` | `#0F1217` | 凹陷区域:输入框、列表容器内衬 |
| `colors.surface.divider` | `#30353D` | 分隔线、描边 |

> 用户设置里的"背景亮度"(background_brightness)会在这些表面色上做后处理,主题无需关心。

### 4.3 colors.accent / colors.text / colors.state

| key | 默认(dark) | 用在哪 |
|---|---|---|
| `colors.accent.primary` | `#FF3242` | 强调色:主按钮、选中态、高亮描边。用户可在设置里覆盖("强调色跟随主题"关闭时) |
| `colors.text.primary` | `#FFFFFF` | 正文/按钮/列表文字 |
| `colors.text.muted` | `#AAAAAA` | 次要文字、占位符、说明 |
| `colors.state.positive` | `#59D940` | 成功/确认/可用 |
| `colors.state.negative` | `#FF4059` | 错误/删除/危险 |
| `colors.state.warning` | `#FFBB00` | 警告/未激活提醒 |
| `colors.state.active` | `#0088FF` | 激活/选中(非强调色语义的选中,如列表多选) |
| `colors.state.highlight` | `#DDDDFF` | 焦点高亮/提示性发光 |
| `colors.state.cursor` | `#57F52A` | 文本输入光标 |

### 4.4 style —— 样式开关

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `style.text_shadow` | bool | `true` | 普通 UI 文字是否带阴影 |
| `style.bevel` | bool | `true` | 按钮等是否画 bevel(立体斜边);关闭则纯平 |
| `style.panel_shadow` | bool | `true` | 面板边缘阴影(带强调色的辉光阴影) |

### 4.5 textures —— 贴图覆盖

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `textures.icons` | string/null | `null` | 图标 atlas 覆盖,`assets:` 链接,如 `"assets:themes/<id>/icons.png"`。null=用默认。atlas 规格见 `example-theme/textures/README.md`(256×256,16×16 网格) |
| `textures.background` | string/null | `null` | 默认仪表盘背景图;用户在设置里显式选择的背景优先于主题 |

贴图文件放主题文件夹内,链接写 `assets:themes/<id>/<文件名>`。
更狠的全局玩法(不走 theme.json):把文件放 `config/bbs/assets/<同内置路径>` 可覆盖
**任意** BBS 内置贴图(对所有主题生效),详见 `example-theme/textures/README.md`。

### 4.6 motion —— 动画

总控:

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `motion.enabled` | bool | `true` | 主题级动画总开关(用户设置里还有一个用户级总开关,两者都开才有动画) |
| `motion.speed` | float | `1.0` | 主题级速度倍率,与用户设置的速度倍率相乘;有效时长 = duration ÷ 总倍率 |

六个接入点,每个都是 `{ "enabled": bool, "duration": int毫秒, "easing": "缓动名" }`:

| key | 默认 | 动画内容 |
|---|---|---|
| `motion.overlay` | `{true, 120, "sine_out"}` | 弹层/浮窗打开关闭:淡入 + 轻微缩放 |
| `motion.panel_switch` | `{true, 100, "sine_inout"}` | 仪表盘面板切换:表面色纱罩淡出 |
| `motion.hover` | `{true, 80, "sine_out"}` | 按钮/图标/开关悬浮色渐变 |
| `motion.notification` | `{true, 150, "back_out"}` | 通知滑入滑出 |
| `motion.context_menu` | `{true, 100, "quad_out"}` | 右键菜单展开淡入 |
| `motion.scrollbar` | `{true, 200, "sine_inout"}` | 滚动条静止后淡出、活动恢复 |

动画只影响绘制,不影响点击判定与操作时序;关闭 = 与无动画的旧版行为完全一致。

### 4.7 缓动名表(easing 可用值)

来自引擎 `Interpolations` 注册表,适合 UI 过渡的:

```
linear
sine_in     sine_out     sine_inout
quad_in     quad_out     quad_inout
cubic_in    cubic_out    cubic_inout
quart_in    quart_out    quart_inout
quint_in    quint_out    quint_inout
exp_in      exp_out      exp_inout
circle_in   circle_out   circle_inout
back_in     back_out     back_inout      (过冲回弹)
elastic_in  elastic_out  elastic_inout   (弹簧)
bounce_in   bounce_out   bounce_inout    (落地弹跳)
```

未知名字 → 回退 `sine_out` + 日志 warn。
(注册表里还有 `hermite/bezier/step/constant` 等关键帧专用插值,不建议用于 UI 过渡。)

## 5. 主题作者 checklist

1. 拷贝 `example-theme/` 到 `config/bbs/assets/themes/我的id/`(或游戏内点"导出模板")。
2. 改 `name/author/description`;确认 `base`(省 key 的兜底来源)与 `variant`。
3. 调 `colors.surface.*` 五色 —— 这五个值决定 80% 的观感;保持 deep < chrome < base < raised
   的明度关系(light 主题反向),divider 与 base 拉开对比。
4. 调 `accent.primary` 与 `text.*`;用 `state.*` 保持红=危险、绿=成功的惯例(除非有意打破)。
5. 需要动图标/背景再动 `textures`,否则删掉或留 null。
6. `motion` 不确定就整段省略(= 跟随 base)。
7. 游戏内:设置 → 皮肤 → 重载 → 选中;改一次 JSON 点一次重载即可预览。
8. 出问题看日志里的 `theme` 相关 warn。

## 6. 完整默认值参考

`example-theme/theme.json` 显式写全了每一个 key(含与 dark 相同的值),可当作
"逐 key 对照表"使用;省略任何 key 的效果 = 该 key 取 base 链上的值。
