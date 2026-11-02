# 3.17.1 工作区 / 任务 / 新对话审计

**基线：`Reference_web/3.17.1/`（2026-10-30 抓取，公开部署快照，只读）。**
**旧基线：`Reference_web/3.16.1/`（只作 diff，结论以 3.17.1 为准）。**
**状态：Round1-4 完成；最终规格见 [`WORKSPACES-FINAL.md`](WORKSPACES-FINAL.md)。**

## 最终交付

- **`WORKSPACES-FINAL.md`**：工作区 / 任务 / 新对话的最终规格与验收清单（以此为准）。
- `round1/`：3.17.1 行为初审计（含已被推翻的旧断言，仅作历史证据）。
- `round2/`：三路交叉复核（桥接与项目导航 / 新对话 / 任务合并），含「与 Round1 冲突」。
- `round3/`：实现、线协议、UI 状态三路审计及修复依据。
- `round4/`：最终一致性、字节级验证、验收审计。

## 为什么重做

3.17.1 是当前线上版本（桌面端 `desktopAppVersion` 也是 3.17.1）。真机暴露了三个
3.16.1 规格没有覆盖的问题：

1. 桌面侧栏有 13 个项目，bootstrap 只返回 2 个工作区；
2. 置顶区需要 `zcode-task` 元数据，3.16.1 的 sessions-index 不提供；
3. 新对话页发送后 `createSession` 没有发出，消息不出现。

## 源指纹

| 文件 | 大小（B） | SHA-256 |
|---|---:|---|
| `index.html` | 18,554 | `33d7fceb5048b07bdca66ba40dfa44232741ab16714a56dfae2aac93d02997ee` |
| `assets/index-Dwhx77cK.js` | 6,986,617 | `149691e76443c3983b3aa8930ad1563f86892bbe84538cd211b866feeb3127b6` |
| `assets/validation-B0HfQ5gr.js` | 348,943 | `ddfec687541aab5be29a2522a460372074748b98cbb205cbed07b4a1810ce3c0` |
| `assets/src-BSClmRM9.js` | 68,793 | `a3a78d4bc463ebd831f516dcf6d6aab3d930af96fed75a2b9ff4ef4ca9533484` |

快照规模：2,581 文件 / 46,583,133 B；抓取失败候选 6 个（wasm/font 的 404，不影响审计）。

## 文档

- `round1/01-workspace-list.md`：工作区列表、bootstrap 过滤、`window-project.readTree`
- `round1/02-tasks-metadata.md`：任务基底、pinned/archived/unread、`window-controller`
- `round1/03-new-conversation.md`：新对话、`createSession`、bridge 复用
- 后续 Round 2/3/4 报告追加在本目录。

## 纪律

- 不执行快照中的 JS；不联网；不依赖真机。
- 每条结论必须带文件 + UTF-8 字节偏移 + 原文片段；不能证明的标 `UNVERIFIED`。
- 旧基线（3.16.1）结论只作 diff，不直接搬进当前规格。
