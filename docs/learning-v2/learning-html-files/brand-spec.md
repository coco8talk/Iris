# M0 阅读页品牌规范

## 色彩令牌

```css
:root {
  --bg: oklch(0.9697 0.0153 7.48);
  --surface: oklch(1.0000 0.0000 89.88 / 0.85);
  --fg: oklch(0.3109 0.0222 355.11);
  --muted: oklch(0.3808 0.0172 348.75);
  --border: oklch(1.0000 0.0000 89.88 / 0.60);
  --accent: oklch(0.7893 0.1284 5.11);
}
```

## 字体

- Display：`-apple-system, "SF Pro Text", "PingFang SC", "Helvetica Neue", system-ui, sans-serif`
- Body：`-apple-system, "SF Pro Text", "PingFang SC", "Helvetica Neue", system-ui, sans-serif`
- Mono：`"SF Mono", "JetBrains Mono", Menlo, monospace`

## 视觉姿态

- 浅粉渐变背景承载三枚低饱和柔光色块，毛玻璃只用于内容层级，不用于代码块。
- 一级章节各自形成 16–20px 圆角、85% 白色透明度的内容卡片，间距保持 24–32px。
- 正文采用 16–17px、1.75 行高与 920px 最大行宽，优先保证长文阅读节奏。
- One Dark Pro 代码块保持纯色不透明，以粉色系行内代码区分正文。
- 动效限于滚动淡入、卡片轻微抬升与目录当前章节状态。
