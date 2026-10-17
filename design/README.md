# design/ —— 从网页版提取的设计资产

来源：`Reference_web/3.15.3/`（remote v4 / 3.15.3 快照，2026-10-15 抓取）。
技术栈事实：**React + Tailwind v4 + lucide 图标**（快照里可见 `react`/`react-dom`/`jsx-runtime`、
`createLucideIcon-*.js`，以及 455KB 含 Tailwind v4 令牌的 CSS）。

这些文件是**界面复刻的地基**：先有令牌，再有主题，再有界面。

| 文件 | 内容 |
|---|---|
| `tokens/colors.json` | 颜色令牌（Tailwind v4 的 `--color-*`，含 oklch 原值与换算后的 sRGB/hex），明暗两套 |
| `tokens/COLORS.md` | 颜色令牌说明与 Compose `Color` 映射建议 |
| `tokens/typography.json` | 字号 / 字重 / 行高 / 字体族 |
| `tokens/layout.json` | 间距、圆角、模糊、动效时长与缓动 |
| `ICONS.md` | 网页实际用到的 lucide 图标清单 + Compose 侧取用方案 |
| `SCREENS.md` | 界面与组件盘点（1:1 复刻的施工图） |
| `PROTOCOL-MAP.md` | 远程控制协议面清单（协议移植的索引，配合 `rust/crates/protocol/PORTING.md`） |

## 约定

- **只读来源**：`Reference_web/` 不入库（见根 `.gitignore`），所以这里的提取结果是**可提交的派生物**——
  尽量把结论落成文本/JSON，不要复制大块 JS。
- 提取时写明**出处**：哪个文件、哪一行/哪个选择器、快照版本 3.15.3。以后网页升级要能重新核对。
- 不确定的地方标 `UNKNOWN`，不要猜。
