# FSR UI 主题包规范(theme-spec)v1

FSR(BBS mod NeoForge 版)的 UI 支持以"主题包"方式高度自定义:颜色、样式开关、
圆角半径、贴图(图标 atlas / 背景图 / 装饰贴花)与 UI 动画(每个接入点的
开关/时长或弹簧/缓动/轨道编排)。
本文是主题包格式的**唯一规范**;`example-theme/` 是可直接拷贝使用的完整样例。

> 实现状态:v1 已实现(2026-07-24),v2 视觉与动效扩展已实现(2026-07-25:圆角、
> 弹簧动力学、退场动画、track 编排、装饰贴花、背景模式)。运行时:
> `mchorse.bbs_mod.ui.themes`(ThemeParser/ThemeManager);
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
| `style.corner_radius.chrome` | int | `0` | 弹层/右键菜单等"界面镶边"级圆角半径,0-16;0 = 直角(与旧版逐位一致) |
| `style.corner_radius.panel` | int | `0` | 卡片/列表选中态等面板级圆角半径 |
| `style.corner_radius.widget` | int | `0` | 按钮/输入框/开关等控件级圆角半径 |

> 圆角用 mask 贴图抗锯齿绘制;半径 <0.5 自动走旧直角路径。`widget` 半径 >0 时
> UIToggle 会切换为 macOS 风格圆形滑块开关。

### 4.5 textures —— 贴图覆盖

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `textures.icons` | string/null | `null` | 图标 atlas 覆盖,`assets:` 链接,如 `"assets:themes/<id>/icons.png"`。null=用默认。atlas 规格见 `example-theme/textures/README.md`(256×256,16×16 网格) |
| `textures.background` | string/null | `null` | 根界面背景图(仪表盘 + 独立 UI 屏通用铺底);用户在设置里显式选择的背景优先于主题 |
| `textures.background_mode` | string | `"stretch"` | 背景铺底模式:`stretch` 拉伸 / `cover` 等比裁切铺满 / `tile` 平铺 |
| `textures.background_dim` | float | `0` | 背景上的暗化蒙层强度,0-1;画在背景之上、贴花与 UI 之下 |

### 4.5.1 decorations —— 装饰贴花

顶层可选 `decorations` 数组(最多 16 项),每项:

```json
{ "texture": "assets:themes/<id>/decal.png", "anchor": "bottom_left",
  "offset": [12, -12], "scale": 1.5, "opacity": 0.9 }
```

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `texture` | string | 必填 | 贴图链接;缺失文件的项在加载时丢弃并 warn |
| `anchor` | string | `"top_left"` | 九宫锚位:`top_left/top/top_right/left/center/right/bottom_left/bottom/bottom_right`;未知锚位丢弃该项 |
| `offset` | [int,int] | `[0,0]` | 相对锚位的像素偏移 |
| `scale` | float | `1` | 贴图缩放,0.05-8 |
| `opacity` | float | `1` | 不透明度 0-1 |

贴花画在背景(及暗化蒙层)之后、主 UI 之前,**永不参与点击判定**。

贴图文件放主题文件夹内,链接写 `assets:themes/<id>/<文件名>`。
更狠的全局玩法(不走 theme.json):把文件放 `config/bbs/assets/<同内置路径>` 可覆盖
**任意** BBS 内置贴图(对所有主题生效),详见 `example-theme/textures/README.md`。

### 4.6 motion —— 动画

总控:

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `motion.enabled` | bool | `true` | 主题级动画总开关(用户设置里还有一个用户级总开关,两者都开才有动画) |
| `motion.speed` | float | `1.0` | 主题级速度倍率,与用户设置的速度倍率相乘;有效时长 = duration ÷ 总倍率 |

六个 v1 接入点 + 四个 v2 接入点,每个条目的通用结构:

```json
{ "enabled": true,
  "type": "ease",  "duration": 120, "easing": "sine_out",      ← ease 模式
  "type": "spring", "response": 0.35, "damping": 0.8,          ← spring 模式(二选一)
  "preset": "scale", "tracks": { "y": { "from": 8 } },         ← 可选轨道编排
  "scale": 1.05 }                                              ← 仅 hover_scale/press 使用
```

- `type`:`"ease"`(默认,固定时长 + 缓动)或 `"spring"`(弹簧动力学,打断时速度连续)。
- spring 参数:`response` 自然周期秒(0.05-3,越小越快),`damping` 阻尼比(0.1-2;
  <1 会过冲回弹,1 临界,>1 迟滞)。spring 模式忽略 `duration/easing`。
- 坏值逐 key 回退默认并 warn,不会崩溃。

| key | 默认 | 动画内容 |
|---|---|---|
| `motion.overlay` | `{true, 120, "sine_out"}` | 弹层/浮窗打开与**关闭**(退场反向播放同一编排) |
| `motion.panel_switch` | `{true, 100, "sine_inout"}` | 仪表盘面板切换:表面色纱罩淡出 |
| `motion.hover` | `{true, 80, "sine_out"}` | 按钮/图标/开关悬浮色渐变 |
| `motion.notification` | `{true, 150, "back_out"}` | 通知滑入滑出 |
| `motion.context_menu` | `{true, 100, "quad_out"}` | 右键菜单展开与关闭(退场反向) |
| `motion.scrollbar` | `{true, 200, "sine_inout"}` | 滚动条静止后淡出、活动恢复 |
| `motion.scroll_smooth` | `{true}` | 平滑滚动(视觉值指数趋近逻辑值;命中判定始终用逻辑值) |
| `motion.hover_scale` | `{false, scale 1}` | 悬浮时控件轻微放大(围绕中心);`scale` 指定倍率如 1.06 |
| `motion.press` | `{false, scale 1}` | 按下压缩、松开回弹;`scale` 指定按下倍率如 0.94 |
| `motion.layout` | `{false}` | 面板布局变更时 bounds 从旧位置动画到新位置(渲染与命中始终一致) |

### 4.6.1 preset / tracks —— 轨道编排(overlay、context_menu)

`overlay` 与 `context_menu` 条目支持在固定属性集 `alpha/scale/x/y` 上编排进出场:
每条 track 只给 `from` 起点(终点恒为静止态:alpha 1、scale 1、偏移 0),退场自动反向。

- `preset` 快捷方式:`"scale"`(v1 默认观感:缩放+淡入)、`"slide_right"`、`"slide_up"`、`"fade"`。
- `tracks` 显式覆盖 preset 对应属性:`{"alpha":{"from":0},"scale":{"from":0.9},"x":{"from":24},"y":{"from":8}}`
  (alpha 0-1,scale 0.1-3,x/y 像素 -200~200)。
- 应用顺序固定:translate(x,y) → scale(围绕锚点)→ alpha。
- 两个 key 都不写 = 该接入点内置变换,与 v1 完全一致。

动画只影响绘制,不影响点击判定与操作时序(唯一例外:弹层/右键菜单退场期间
元素仍在渲染,但语义上已关闭——立即失去命中与键盘输入,下层界面即刻可交互)。
关闭 = 与无动画的旧版行为完全一致。

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
