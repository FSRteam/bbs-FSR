# 示例主题包:示例·夜蓝

这是 FSR UI 主题包的官方样例,可直接拷贝改造。规范全文见 [`../README.md`](../README.md)。

## 安装

1. 把整个 `example-theme/` 文件夹拷到:
   `.minecraft/config/bbs/assets/themes/example-theme/`
   (文件夹名即主题 id,可改成你自己的,如 `my-theme`)
2. 进游戏:BBS 面板 → 设置(齿轮)→ 皮肤 → 重载 → 选中它。

## 修改流程

改 `theme.json` → 游戏内点皮肤栏的**重载** → 立即看到效果。写坏了不会崩游戏,
会回退默认并在日志提示原因。

## 文件说明

```
example-theme/
├── theme.json          主题文档:显式写全了规范里每一个 key,可当逐 key 对照表
├── README.md           本文
└── textures/           贴图覆盖玩法说明(样例未附带贴图,theme.json 里 textures 均为 null)
    └── README.md
```

## 改造建议

- 只想换配色:改 `colors.surface` 五色 + `accent.primary` 就够了,其余整段删掉
  (省略的 key 自动继承 `base` 指向的主题)。
- 想要"性冷淡纯平风":`style.bevel=false`、`style.panel_shadow=false`。
- 想要更"弹"的动画:把 `motion.overlay.easing` 换成 `back_out` 或 `elastic_out`,
  时长加到 200 左右。
- 想关掉全部动画:`motion.enabled=false`。
