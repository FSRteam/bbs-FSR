# 贴图覆盖玩法

主题系统提供两条贴图自定义路径,按需选用。

## 方式一:主题内贴图(随主题切换)

把贴图放在**主题文件夹内**,并在 `theme.json` 的 `textures` 里用 `assets:` 链接引用:

```
config/bbs/assets/themes/my-theme/
├── theme.json
├── icons.png        ← 自定义图标 atlas
└── bg.png           ← 自定义背景图
```

```json
"textures": {
    "icons": "assets:themes/my-theme/icons.png",
    "background": "assets:themes/my-theme/bg.png"
}
```

只在选中该主题时生效,切走即还原——**推荐方式**。

### icons.png 规格

- atlas 使用 **256×256 逻辑坐标**,图标按 **16×16 逻辑网格**排布(个别图标占半格/跨格)。
  图片可以是 256×256,也可以是保持相同网格和长宽比的整数倍高分辨率原图;
  例如内置 Refreshed 主题直接使用其原包 1024×1024 atlas(4 倍物理像素),无需缩放素材。
- 制作方法:从 mod jar 解出 `assets/bbs/assets/textures/icons.png`
  (源码路径 `new/src/client/resources/assets/bbs/assets/textures/icons.png`),
  在原图上改——**网格位置不能动**,代码按坐标取图,挪位会取错图标。
- 图标绘制时会被 UI 按语义染色(白色部分被染成目标色),因此图标本体建议保持
  白色+透明,除非有意做彩色图标。

## 方式二:全局资源覆盖(对所有主题生效)

BBS 的 `assets:` 虚拟文件系统会优先读取游戏目录下的外部文件:

```
config/bbs/assets/<与内置相同的路径>
```

例如放 `config/bbs/assets/textures/icons.png` 会**全局**替换图标 atlas(无论选什么主题)。
任何 BBS 内置资源(`assets:` 链接的)都可以这样覆盖。适合"我就要永久替换某资源"的场景;
做可分享的主题包请用方式一。

## 优先级

用户设置(如显式选过的背景图) > 主题 `textures.*` > 全局覆盖(方式二) > mod 内置。
