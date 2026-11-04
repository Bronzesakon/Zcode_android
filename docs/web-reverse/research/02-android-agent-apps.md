> 调研对象：Android / Compose / KMP 生态中「AI Agent 对话客户端」的 UI 实现与可复用组件
> 调研方式：GitHub CLI（gh，已登录）仓库搜索 + 代码搜索 + 逐文件读取源码
> 数据快照：2026-11-03（stars / pushed_at 均为当日实测值）
> 服务对象：`android/app/src/main/kotlin/app/zemote` 原生 Compose 界面（下文简称 ZCode）
> 约束：本次调研未修改任何仓库代码，仅新增本文件。

---

## 0. 结论先行

1. **RikkaHub 是唯一一个「Compose 原生 + Agent 工具调用 + 富文本渲染」三条线都做完整的开源项目**（8.6k★，AGPL-3.0）。它的价值在架构分层，不在可直接搬运的代码：`ai/`（provider 与流式解码）与 `app/`（Compose UI）彻底解耦，UI 只消费 `UIMessagePart` 密封类。ZCode 现有 `timeline/` + `markdown/` 分层与它同构，差距在**渲染引擎深度**（行内数学公式、行内可组合内容的基线对齐）。
2. **Operit 是「流式 Markdown 增量渲染」做得最细的项目**（9.2k★，LGPL-3.0）：1249 行的 `StreamMarkdownRenderer` + 41 行的 `RenderBatchCoordinator`（固定窗口合并 + revision 计数，保证最后一次变更不丢）+ `LruCache` 缓存节点测量结果。ZCode 现在的做法是「80ms 采样 + 整段重解析」，方向不同：它是增量解析，我们是限流重解析。两者可叠加。
3. **最贴合 ZCode 需求的现成库是 `re-ovo/bettertext`（MIT）**：官方定位就是「渲染 LLM 回复」，原生支持 KaTeX 行内公式换行、行内 composable 与文本同基线、流式只重解析不稳定尾部、highlight.js 移植的 30 语言高亮、整条回复可选。**但它只有 3★、单作者、只发 JitPack、无 Maven Central**，属于「能力强 / 风险高」。
4. **`dev.snipme:highlights`（Apache-2.0，Maven Central，197★）与 `io.github.java-diff-utils:java-diff-utils`（Apache-2.0，1.5k★）是唯二「可直接依赖、零法律风险」的组件**。gpt_mobile 与 RikkaHub 分别在生产中使用它们。
5. **平板/自适应是这个赛道的普遍空白**：在 RikkaHub / Operit / gpt_mobile 三个头部项目里，`gh search code "windowSizeClass"` 全部返回空。LibreChat-Mobile 的 `Adaptive*` 系列只是「Liquid Glass vs M3」的样式分支，不是断点布局。ZCode 若做双栏（会话列表 + 时间线），在这个品类里是差异化优势。
6. **许可证是硬约束**：RikkaHub / LastChat / Cherry Studio 是 AGPL-3.0，Chatbox 是 GPL-3.0，gpt_mobile 是 GPL-3.0，Operit 是 LGPL-3.0。这些都**不能复制代码进 ZCode**（AGPL 连网络服务都传染）。可复制的只有 Apache-2.0 / MIT：bettertext、mikepenz renderer、highlights、java-diff-utils、compose-richtext、terminal-view（注意 Termux 整体是 GPLv3，只有这两个子模块是 Apache-2.0）。

---

## 1. 调研方法与检索证据

### 1.1 命令模板

```bash
gh search repos "<query>" --limit 10 --json fullName,stargazersCount,pushedAt,license,description
gh search repos --topic=<topic> --language=kotlin --limit 10 --json ...
gh search code "<symbol>" --repo <owner/name> --json path
gh api repos/<owner>/<name>                                  # stars / pushed_at / license / default_branch
gh api repos/<owner>/<name>/git/trees/<branch>?recursive=1   # 全量文件图
gh api repos/<owner>/<name>/contents/<path> --jq .content | base64 -d
gh api repos/<owner>/<name>/license --jq .content | base64 -d # NOASSERTION 时读许可证原文
```

### 1.2 已执行查询（36 组，超出「≥20 组」要求）

| # | 查询 | # | 查询 |
|---|---|---|---|
| 1 | `rikkahub` | 19 | `ollama android` |
| 2 | `android compose ai chat` | 20 | `--topic=llm-client --language=kotlin` |
| 3 | `kotlin compose llm client` | 21 | `--topic=ai-chat --language=kotlin` |
| 4 | `compose multiplatform ai chat` | 22 | `kelivo` |
| 5 | `compose markdown renderer` | 23 | `cherry studio mobile` |
| 6 | `compose code highlight` | 24 | `lobe chat mobile` |
| 7 | `compose chat ui` | 25 | `compose code editor` |
| 8 | `android terminal emulator compose` | 26 | `kotlin diff library` |
| 9 | `compose diff view` | 27 | `xterm android webview` |
| 10 | `compose syntax highlighting` | 28 | `compose terminal` |
| 11 | `code highlighting kotlin compose` | 29 | `compose multiplatform chat` |
| 12 | `compose markdown editor` | 30 | `kmp chat app compose` |
| 13 | `compose chat --language=kotlin` | 31 | `android agent llm tool calling compose` |
| 14 | `chatbot android compose` | 32 | `streaming markdown kotlin` |
| 15 | `claude code android` | 33 | `compose rich text selection` |
| 16 | `codex android client` | 34 | `codex mobile client` |
| 17 | `openai android client kotlin` | 35 | `claude code mobile ui` |
| 18 | `llm android app compose` | 36 | `openhands android` |

此外执行了 5 次 `gh search code`（`rememberChatMessageHeightMemory`、`rememberStreamingMarkdownState`、`windowSizeClass`×3、`Licensed under the Apache License`）用于**反向验证某能力是否真的存在**，而不是只信 README。

### 1.3 一条重要方法论

搜索 API 有 30 次/分钟限流，多词查询是**短语匹配**语义。实际有效做法是：先用 `--topic` + `--language` 粗筛，再对候选逐个 `gh api .../git/trees/...?recursive=1` 拉全量文件图，**用文件命名判断能力**（例如出现 `StreamMarkdownRenderer.kt` 就说明有流式渲染），最后才读源码确认。只看 README 会被严重误导（多个项目的 README 描述与代码不符）。

---

## 2. 生态全景

### 2.1 分类

| 类别 | 项目 | 与 ZCode 的相关度 |
|---|---|---|
| A. Compose 原生 Agent/聊天客户端 | RikkaHub、Operit、gpt_mobile、LibreChat-Mobile、maid-native、OpenCodeMobile、android-ai-chat-sdk、keryx | 最高：同平台、同问题域 |
| B. 非 Compose 的 AI 客户端（读交互，不读实现） | Chatbox、LobeChat、Cherry Studio、Open WebUI、OpenHands、Kelivo、Happy | 中：桌面/Web 的交互范式 |
| C. Compose 组件库 | bettertext、multiplatform-markdown-renderer、compose-markdown、highlights、java-diff-utils、compose-richtext、termux terminal-view、material3-adaptive | 最高：可直接依赖或改造 |
| D. 反面案例（不要碰） | stream-chat-android（专有许可）、Termux 整体（GPLv3） | 许可证风险 |

### 2.2 元数据总表（2026-11-03 实测）

| 仓库 | ★ | 最近提交 | License | 语言 | 形态 |
|---|---:|---|---|---|---|
| `AAswordman/Operit` | 9201 | 2026-11-03 | LGPL-3.0 | Kotlin | Android 原生，AI Agent + 工具执行 |
| `rikkahub/rikkahub` | 8620 | 2026-11-02 | AGPL-3.0 | Kotlin | Android 原生，多 Provider 聊天 |
| `Taewan-P/gpt_mobile` | 1265 | 2026-10-10 | GPL-3.0 | Kotlin | Android 原生，多 Provider + Agent/MCP |
| `Cocolalilal/LastChat` | 393 | 2026-11-02 | AGPL-3.0 | Kotlin | RikkaHub 的 UI 重制分支 |
| `garfiec/Librechat-Mobile` | 143 | 2026-11-03 | Apache-2.0 | Kotlin | **KMP + Compose Multiplatform**，LibreChat 原生客户端 |
| `salmanashraf/android-ai-chat-sdk` | 22 | 2026-05-29 | MIT | Kotlin | Compose 聊天 SDK（含大量 Placeholder） |
| `jaggerjack61/OpenCodeMobile` | 16 | 2026-10-19 | MIT | Kotlin | OpenCode 的原生 Android 客户端 |
| `CocaKova/keryx` | 13 | 2026-10-25 | MIT | Kotlin | 语音优先的 Hermes Agent 客户端 |
| `HatsyRei/maid-native` | 12 | 2026-11-03 | MIT | Kotlin | 轻量 OpenAI 兼容客户端 |
| `mikepenz/multiplatform-markdown-renderer` | 1130 | 2026-11-02 | Apache-2.0 | Kotlin | KMP Markdown 渲染库（含流式） |
| `jeziellago/compose-markdown` | 874 | 2026-10-08 | MIT | Kotlin | Compose Markdown 渲染 |
| `re-ovo/bettertext` | 3 | 2026-10-28 | MIT | Kotlin | **面向 LLM 回复的富文本引擎** |
| `SnipMeDev/highlights` | 197 | 2026-11-03 | Apache-2.0 | Kotlin | KMP 语法高亮引擎 |
| `java-diff-utils/java-diff-utils` | 1551 | 2026-10-18 | Apache-2.0 | Java | 文本 diff 算法库 |
| `halilozercan/compose-richtext` | 994 | 2026-06-08 | Apache-2.0 | Kotlin | Compose 富文本编辑/选择 |
| `termux/termux-app` | 64007 | 2026-09-26 | GPLv3（`terminal-view`/`terminal-emulator` 为 Apache-2.0） | Java | Android 终端 |
| `Chevey339/kelivo` | 4409 | 2026-11-03 | AGPL-3.0 | Dart | Flutter 跨端客户端 |
| `chatboxai/chatbox` | 42243 | 2026-10-29 | GPL-3.0 | TypeScript | Electron + RN |
| `CherryHQ/cherry-studio` | 53228 | 2026-11-03 | AGPL-3.0 | TypeScript | Electron |
| `lobehub/lobehub` | 83768 | 2026-11-03 | LobeHub Community License | TypeScript | Next.js Web |
| `open-webui/open-webui` | 157637 | 2026-11-03 | Open WebUI License（BSD-3 派生 + 品牌条款） | Svelte | Web |
| `OpenHands/OpenHands` | 92566 | 2026-11-03 | MIT | TypeScript | Web agent 平台 |
| `slopus/happy` | 24469 | 2026-11-03 | MIT | Swift/RN | Claude Code 移动端 |

---

## 3. 逐项目深读

### 3.1 RikkaHub —— 架构分层的最佳范本

- repo: https://github.com/rikkahub/rikkahub ｜ 8620★ ｜ 2026-11-02 ｜ **AGPL-3.0** ｜ Kotlin / Jetpack Compose（Android）+ Ktor
- 规模：`app` 模块 464 个 `.kt`；用户明确要求「ZCode 原生界面」，这是最贴近的参照物。

**模块划分（这是最值得抄的部分，是架构而非代码）**

```
ai/        → 与 UI 无关的 LLM 抽象层
  core/MessageRole.kt, core/Reasoning.kt
  provider/stream/SseEvent.kt, StreamChunkDecoder.kt
  provider/providers/{openai,claude,google,chatgpt}/*StreamDecoder.kt
  ui/UIMessagePart.kt, StreamChunk.kt, StreamChunkHandler.kt   ← UI 契约
ui/        → 可复用 Compose 组件（richtext / diff）
app/       → 页面、主题、组件
```

关键点：**流式协议差异在 `ai/provider/**StreamDecoder.kt` 里被吸收干净**，UI 只看到 `StreamChunk` 序列；`UIMessagePart` 是密封类（Text / Reasoning / Tool / Image / ...），UI 侧靠 `when` 穷举。ZCode 的 `EnginePort` + `timeline` 行模型已具备同一形态（我们更进一步：行是 `JsonObject` 投影，能直接复用 Web 端数据）。

**关键文件**

| 关注点 | 路径 |
|---|---|
| 消息契约 | `ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt` |
| 流式累积 | `ai/src/main/java/me/rerere/ai/ui/StreamChunkHandler.kt`、`StreamChunk.kt` |
| 会话时间线 | `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`（878 行） |
| Markdown | `app/.../ui/components/richtext/Markdown.kt`（447 行）、`MarkdownWeb.kt`（29 行，WebView 兜底） |
| 代码块 | `app/.../ui/components/richtext/HighlightCodeBlock.kt`（534 行） |
| 代码配色 | `app/.../ui/theme/CodeColor.kt`（Atom One Dark/Light 双套调色板） |
| 工具调用 | `app/.../ui/components/message/ChatMessageTools.kt`（529 行）、`tools/ToolUI.kt`、`tools/BuiltinToolUIs.kt`、`tools/WorkspaceToolUIs.kt` |
| 推理折叠 | `app/.../ui/components/message/ChatMessageReasoning.kt`（278 行）、`ChatMessageCot.kt` |
| diff | `ui/src/main/java/me/rerere/ui/richtext/DiffView.kt`（123 行）、`app/.../utils/DiffUtils.kt` |

**可借鉴**

1. **工具调用 = 思维链步骤**。`ChatMessageServerToolStep` / `ChatMessageToolStep` 都挂在 `ChainOfThoughtScope` 下，形状是「图标 + 标题 + 可展开正文 + 耗时」。工具与 reasoning 共用一条视觉时间线，用户读到的是「思考 → 调工具 → 出结果」的连续叙事，而不是三套风格各异的卡片。
2. **工具渲染注册表**。`ToolUIRegistry.resolve(toolName)` 按工具名返回专用渲染器，未命中则回落 `DefaultToolPreview`。这样新增工具不需要改时间线代码。
3. **审批是状态机，不是弹窗**。`ToolApprovalState`（Pending / Denied(reason) / Answered）挂在 part 上，UI 只读状态；拒绝要填理由，问用户要回填 `answerPayload`。ZCode 若要做「工具授权」，照这个状态机建模即可。
4. **耗时从数据推**，`rememberToolDuration(tool, loading)` 按时间戳差算，不靠 UI 计时器累加，进程被杀后重新进入仍正确。
5. **代码块**：`Row { 行号 Column + SelectableContent { BetterText } }` 外层套 `Modifier.horizontalScroll(scrollState)`，`softWrap = false`，字体关闭连字（`fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"`）。行号不参与选择，代码可选。
6. **长列表自动滚动**：`snapshotFlow { state.layoutInfo.visibleItemsInfo }` + `isAtBottom()` 判断，仅在「用户贴底且正在流式」时 `requestScrollToItem(lastIndex + 10)`。注意用的是 **request（不带动画、不与用户手势打架）**，不是 `animateScrollToItem`。
7. **顶部留白走 `contentPadding`** 而非 header item，配合 `topFadingEdge` 让消息能滚到透明标题栏下面。

**风险 / 不足**

- AGPL-3.0：**一行代码都不要复制**，只能读结构。
- `ChatList.kt` 的 `itemsIndexed` 只给了 `key`，**没给 `contentType`**；同一 LazyColumn 里混排文本/图片/工具卡时，复用池无法按类型分桶，是明确的性能损失点（ZCode 的 `Timeline.kt` 若已按类型分桶则不要退步）。
- 没有使用 `material3-adaptive`（版本目录里有声明，`gh search code "windowSizeClass"` 返回空），平板无专门布局。
- Markdown 依赖自家 `bettertext`（作者 `re-ovo` 即 `rerere`），等于把富文本引擎绑在个人项目上。

---

### 3.2 Operit —— 流式增量渲染与超大 Markdown 的工程化

- repo: https://github.com/AAswordman/Operit ｜ 9201★ ｜ 2026-11-03 ｜ **LGPL-3.0** ｜ Kotlin / Compose，项目体量极大（仓库 593 MB）
- 定位：Android 上的「AI Agent 助手」，有真实的工具执行、shell、文件系统能力，UI 复杂度高于普通聊天 App。

**关键文件**

| 关注点 | 路径 |
|---|---|
| 流式 Markdown | `app/src/main/java/com/ai/assistance/operit/ui/common/markdown/StreamMarkdownRenderer.kt`（1249 行） |
| 渲染节流器 | 同目录 `RenderBatchCoordinator.kt`（41 行） |
| 节点分组 | 同目录 `MarkdownNodeGrouper.kt`（61 行） |
| 代码块 | 同目录 `EnhancedCodeBlock.kt`（1046 行）、`CanvasMonospaceCodeBlockBody.kt` |
| 表格/图片/音视频 | 同目录 `EnhancedTableBlock.kt`、`MarkdownImageRenderer.kt`、`MarkdownAudioRenderer.kt`、`MarkdownVideoRenderer.kt` |
| 滚动 | `app/.../ui/features/chat/components/ChatScrollNavigator.kt`、`ChatScrollExtensions.kt` |
| 高度记忆 | `app/.../ui/features/chat/components/ChatMessageHeightMemory.kt`（41 行） |
| 平板 | `app/.../ui/main/layout/TabletLayout.kt` |

**可借鉴**

1. **`RenderBatchCoordinator`（41 行，可直接照思路重写）**：`requestUpdate()` 只自增 revision 并确保有一个协程在跑；循环里 `delay(intervalMs)` 后执行 `onFlush()`，用 `appliedRevision` 与 `requestedRevision` 比较。**核心价值是「合并更新但绝不丢最后一次变更」**——比朴素的 `debounce` 正确（debounce 会在流结束时丢掉尾帧）。ZCode 的 `MarkdownStream.kt` 已用「窗口到期补渲染最后一帧」解决同一问题，可对照检查边界。
2. **增量解析而非整段重解析**：`StreamMarkdownRenderer` 里 `appendInlineChunk`、`appendHtmlBreakNode`、`replaceRollbackTail`、`toStableNode()` —— 把已闭合的节点标为 stable，只重算尾部不稳定区。这才是流式 Markdown 的正解；我们目前每次 delta 都全量 parse，长回复（>50KB）时会明显吃 CPU。
3. **测量结果缓存**：`LruCache(key, value) { sizeOf = ... }` 缓存节点测量尺寸，避免每次重组重新 measure 长文档。
4. **流式代码体走 Canvas**：`CanvasMonospaceCodeBlockBody` 在流式期间用 Canvas 直绘（不走文本排版），流结束后再切回带高亮的 `AnnotatedString`。这是「流式期性能 vs 稳定期质量」的取舍范本。
5. **代码块闭环**：自动换行开关（`autoWrapEnabled`）、逐行高亮缓存 `lineCache[cacheKey]`、Mermaid 走 WebView 并按内容自适应宽高、HTML 走 `HtmlPreviewRenderer`。
6. **`ChatMessageHeightMemory`**：按 messageId 记录已测量的像素高度，`prune(validIds)` 清理，用于浮窗/长会话切换时**恢复滚动位置不跳动**。思路很小但很实用。

**风险**

- **LGPL-3.0**：可以链接，但直接拷贝源码文件到 ZCode 会让该文件受 LGPL 约束（且需允许替换/重链接）。**只借思路，不搬文件**。
- 代码风格偏「大文件堆叠」（单文件 1000+ 行、魔法数字多），直接照抄会把复杂度带进来。
- 仓库含大量非 UI 的 Agent 执行代码，检索成本高。

---

### 3.3 gpt_mobile —— 「贴底跟随」契约与工具轨迹工程

- repo: https://github.com/Taewan-P/gpt_mobile ｜ 1265★ ｜ 2026-10-10 ｜ **GPL-3.0** ｜ Kotlin / Compose
- 依赖实测（`app/build.gradle.kts`）：`libs.markdown.renderer` + `markdown.renderer.m3` + `markdown.renderer.code`（即 mikepenz），代码高亮单独用 `dev.snipme.highlights`。

**关键文件（全部在 `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/`）**

| 文件 | 行数 | 作用 |
|---|---|---|
| `ChatScreen.kt` / `ChatViewModel.kt` | — | 主屏与状态 |
| `ChatMarkdown.kt` | 495 | Markdown 渲染（mikepenz）+ 代码块高亮（highlights，`SyntaxThemes.atom(isDarkTheme)`） |
| `ChatMarkdownParser.kt` | — | 自定义解析补充 |
| `MathJaxView.kt` | 638 | WebView + MathJax 渲染公式（带 viewport 修正） |
| `StreamPresentation.kt` | 76 | **字素安全的渐进呈现** |
| `ThinkingBlock.kt` | 176 | reasoning 折叠块 |
| `ToolTraceBlock.kt` | 459 | 工具轨迹 |
| `AgentRunStatusBlock.kt` | — | Agent 运行状态 |
| `LiveSnapshotOverlay.kt` | — | 实时快照浮层 |

**可借鉴**

1. **`StreamPresentation.kt` 的 `nextPresentedText` / `takeGraphemes`**：打字机效果**按字素簇推进**，不会把 emoji、组合字符、ZWJ 序列劈成两半。ZCode 若做逐字呈现，必须按 grapheme 切，否则中文/emoji 会出现乱码帧。这是很多人踩的坑。
2. **「贴底跟随」被写成了可测契约**（`app/src/androidTest/.../ChatBottomAutoScrollerInstrumentedTest.kt`）：
   - 打开历史会话：**直接停在底部，但不触发自动滚动**；
   - 内容增长且处于跟随态：保持真底部（`assertFalse(listState.canScrollForward)`）；
   - 用户上滑后：出现「回到底部」按钮，点击才重新进入跟随态；
   - 晚到的内容增长：按钮仍能恢复跟随。
   
   ZCode 的 `Timeline.kt` 已有 `derivedStateOf` 判底与预取，**建议补一组同等语义的测试**——这类行为靠手测一定会回归。
3. **工具轨迹的可操作性**：`filterToolEvents(events, query)` 支持搜索过滤、`toolTextRequiresViewFull` 判断是否折叠长输出、`formatToolTraceMarkdown` 支持把轨迹导出为 Markdown。工具输出很长时，「搜索 + 展开全文 + 导出」三件套比单纯折叠有用得多。
4. **`toolTraceStatusSummary` / `formatToolDuration` / `toolEventErrorText`**：把「状态摘要、耗时、错误文案」抽成纯函数，UI 只负责排版，便于单测。

**风险**：GPL-3.0，同样不可复制。MathJax 走 WebView 会在滚动时与 LazyColumn 抢手势，只能作公式渲染的备选而非首选。

---

### 3.4 LibreChat-Mobile —— KMP + Compose Multiplatform 的工程分层

- repo: https://github.com/garfiec/Librechat-Mobile ｜ 143★ ｜ 2026-11-03（当天有提交）｜ **Apache-2.0** ｜ Kotlin / KMP
- 已核实是 KMP：`settings.gradle.kts` 含 `:core:ui`、`:feature:chat`、`:feature:agents`、`:feature:skills`、`:feature:files`、`:feature:schedules`；存在 `build-logic/.../KmpComposeConventionPlugin.kt`、`core/ui/src/commonMain/composeResources/`。

**价值点（对 ZCode 是「未来迁移 KMP」的路线图，不是当下刚需）**

1. **模块切分即产品切分**：`core/{model,network,data,ui}` + `feature/{auth,chat,conversations,agents,files,schedules,skills}`。`agents` / `skills` / `schedules` 独立成 feature，说明 Agent 客户端的导航层级应预留这三块——ZCode 现在把一切都塞进 `ui/chat/`，未来加「技能库」「定时任务」会缺挂载点。
2. **`Adaptive*` 组件族**：`AdaptiveAlertDialog` / `AdaptiveCard` / `AdaptiveChips` / `AdaptiveModalBottomSheet` 等，用 `expect/actual` 在「Liquid Glass」与 Material 3 之间切换。注意：**这是样式分支，不是断点布局**，不要误读成平板适配。
3. **`compose-stability.conf`**：用 Compose compiler 的稳定性配置集中管理，长列表场景值得借鉴（不稳定类型会让 LazyColumn 每帧重组）。

**风险**：143★，单人主导，仍在高频重构（当天提交）；Apache-2.0 允许复用，但模块边界尚未稳定，不宜照搬。

---

### 3.5 其他原生客户端的定位（快速判断，不必深读）

| 项目 | 结论 |
|---|---|
| `HatsyRei/maid-native`（MIT, 12★） | 结构干净的小型 OpenAI 兼容客户端，有 `domain/tools/{Mcp,FileTools,RunJavaScript}.kt`、`domain/tree/MessageTree.kt`（消息树/分支）。**消息树**建模值得看一眼，其他部分价值有限。 |
| `jaggerjack61/OpenCodeMobile`（MIT, 16★） | OpenCode 的原生客户端，`network/SseParser.kt` + `network/ShellTool.kt` + `ui/{ChatScreen,ChatComposer,Components}.kt`。**体积小、可整读**，适合当「最小可用 SSE 聊天客户端」样板。 |
| `salmanashraf/android-ai-chat-sdk`（MIT, 22★） | 名为 SDK，实际含 `CryptoManagerPlaceholder.kt`、`EmbeddingEnginePlaceholder.kt`、`ToolRegistryPlaceholder.kt` —— **大量占位实现**，README 能力高于实际，不要被误导。 |
| `CocaKova/keryx`（MIT, 13★） | 语音优先（`audio/{CallController,TtsController,NoiseFloor}.kt`），对 ZCode 只有「语音输入/播报」参考价值。 |
| `Cocolalilal/LastChat`（AGPL, 393★） | RikkaHub 的 UI 重制分支。想比较「同一后端数据的两种视觉方案」时可看，但 AGPL 拦住复用。 |
| `leavesczy/compose_chat`（Apache-2.0, 390★） | IM 场景的 Compose 最佳实践，**不是 AI 客户端**；只有列表/输入框的基础范式可参考。 |

### 3.6 非 Compose 项目：只读交互，不读实现

| 项目 | 值得看什么 | 不要看什么 |
|---|---|---|
| `chatboxai/chatbox`（GPL-3.0, 42k★） | 多模型切换的输入区布局、会话侧栏信息密度 | Electron/RN 实现（技术栈完全不同） |
| `lobehub/lobehub`（LobeHub Community License, 84k★） | 工具调用卡片、Assistant/Plugin 消息的视觉分层、会话分组 | 许可证非 OSI，禁止复用任何代码 |
| `CherryHQ/cherry-studio`（AGPL-3.0, 53k★） | 「话题/助手/知识库」三层信息架构；移动端设计稿在 `CherryHQ/cherry-studio-mobile-ui-design`（TypeScript 原型，无 License） | 代码 |
| `open-webui/open-webui`（自定义 BSD 派生, 158k★） | 消息操作条（复制/重新生成/分支）、引用角标（citation chip） | 许可证含品牌条款，不可商用改名分发 |
| `OpenHands/OpenHands`（MIT, 93k★） | **Agent 运行过程的可视化**：步骤流、终端输出、文件变更面板的组织方式 | 前端为 TypeScript |
| `Chevey339/kelivo`（AGPL-3.0, 4.4k★） | Flutter 跨端客户端的滚动与输入体验；底部工具栏密度 | 非 Compose |
| `slopus/happy`（MIT, 24k★） | Claude Code 移动端的「远程会话 + 审批」流程范式 | RN/Swift 实现 |

---

## 4. Compose 组件库逐个评估

### 4.1 `re-ovo/bettertext`（MIT，3★）—— 能力最强，风险最高

- repo: https://github.com/re-ovo/bettertext ｜ MIT ｜ Kotlin ｜ 仅 JitPack
- 官方自述（README 原文要点）：为「LLM 聊天应用的回复」而做的 Compose 库，解决 Compose `Text` 的三处硬伤：行内内容必须放进固定尺寸占位、已对齐基线、公式过高会压住相邻行；自定义文本布局无法参与选择。
- 能力清单：
  - **KaTeX 0.19.1 的 Kotlin 移植**，行内公式可像浏览器一样在关系符/二元运算符后换行，支持 mhchem（`\ce`）；
  - 行内 composable（如引用角标）与文本同基线，行高自适应；
  - **流式 Markdown：只重解析变化尾部，未闭合语法（未闭合加粗/公式）被临时闭合或隐藏，不闪原始字符**；
  - 代码块/公式/表格/图片/details 由宿主提供组件（不强绑 Material 3）；
  - **highlight.js 11.11.1 的 Kotlin 移植，30 种语言**；
  - 整条回复可整体选择复制。
- 用法：`com.github.re-ovo.bettertext:bom` + `text` / `markdown` / `highlight` / `latex`（JitPack，BOM 锁定版本）。
- **风险**：3★、单作者（即 RikkaHub 作者）、只发 JitPack（无 Maven Central）、API 未稳定。**一旦作者停更，ZCode 会被锁死在一个无法自行维护的文本引擎上**（自绘文本布局的维护成本极高）。
- 建议：**列为「若重写渲染层则优先评估」**，当前阶段不引入。

### 4.2 `mikepenz/multiplatform-markdown-renderer`（Apache-2.0，1130★）—— 最稳的通用方案

- repo: https://github.com/mikepenz/multiplatform-markdown-renderer ｜ Apache-2.0 ｜ Maven Central ｜ KMP（Android/iOS/Desktop/Web/macOS）
- 能力：完整 GFM（表格、任务列表、删除线、autolink、GitHub alerts）、`rememberStreamingMarkdownState()` 增量追加（append-only，只重解析不稳定尾部）、`-code` 模块提供语法高亮代码围栏 + 语言头 + 复制按钮、`MarkdownComponents` 可替换任意节点。
- 实测使用者：`Taewan-P/gpt_mobile`（生产使用，三个 artifact 全上）。
- 局限：**没有行内数学公式**；行内 composable 不支持；对「工具调用卡片嵌在 Markdown 里」这类需求要自己扩展。
- 建议：ZCode 已有自研 parser 与 RichBlock 体系，**整体替换不划算**；可作为 `mikepenz:multiplatform-markdown-renderer-code` 的高亮实现参考，或作为「兜底渲染器」用于未知语法。

### 4.3 `jeziellago/compose-markdown`（MIT，874★）

- 基于 `halilozercan/compose-richtext` 的 `RichText`，把 Markdown 映射为 AnnotatedString。
- 优点：简单、稳、MIT。缺点：**无流式增量**（每次整段重建 AnnotatedString），无表格，代码高亮需自接。
- 结论：只适合「短消息、低频更新」场景，不适合 Agent 长回复流式。**不推荐**。

### 4.4 `SnipMeDev/highlights`（Apache-2.0，197★）—— 推荐直接依赖

- repo: https://github.com/SnipMeDev/highlights ｜ `dev.snipme:highlights:1.2.0` ｜ Maven Central ｜ KMP
- 能力：词法级组件分析（Keyword/Comment/...）、多语言、主题（含 atom）、结果缓存、**支持增量变更**、同步/异步。
- 实测使用者：`gpt_mobile` 的 `ChatMarkdown.kt` 用 `Highlights.Builder().theme(SyntaxThemes.atom(isDarkTheme))` 生成 `AnnotatedString`。
- 注意：README 明确「实例内部缓存分析结果，小改动请复用同一实例」——**不要每次重组 new 一个 Builder**。
- 建议：**首选依赖**。ZCode 现有 `MarkdownHighlighter.kt` 是自研，若语言覆盖不足或要主题化，用它可以零风险替换（Apache-2.0 + Maven Central）。

### 4.5 `io.github.java-diff-utils:java-diff-utils`（Apache-2.0，1551★）—— 推荐按需依赖

- 纯 JVM 文本 diff（Myers），RikkaHub 用 `4.18` 版本算文件变更，再交给 `DiffView.kt` 渲染。
- 建议：ZCode 的 `ArtifactRow` 目前只显示文件名/大小/版本，**没有 diff**。若要做「文件变更预览」，这是最省事的算法层（自己做 Myers 也可，约 100 行，但边界情况多）。

### 4.6 `halilozercan/compose-richtext`（Apache-2.0，994★）

- 提供 `RichText`（AnnotatedString 里嵌 Composable）、`RichTextEditor`（所见即所得编辑）。
- 适用：需要在文本中嵌入「不可分割的原子组件」（如引用 chip、内联按钮）。
- 风险：最后提交 2026-06，维护节奏放缓；`RichTextEditor` 与 Compose 版本耦合较紧。
- 建议：若只做**只读**内联组件，可用；若要编辑器，谨慎。

### 4.7 Termux `terminal-view` / `terminal-emulator`（Apache-2.0 子模块）

- 仓库整体 **GPLv3**，但 `LICENSE.md` 明确：`terminal-view` 与 `terminal-emulator` 源自 jackpal/Android-Terminal-Emulator，为 **Apache-2.0**（`gh search code "Licensed under the Apache License" --repo termux/termux-app` 命中 `terminal-view/src/.../PopupWindowCompatGingerbread.java` 等文件，可佐证）。
- 现实成本：需要 PTY JNI（`libtermux.so` 在 app 模块，非 Apache 部分）、`TerminalView` 是 `View` 而非 Compose，需 `AndroidView` 包装；键盘/手势/字体度量都要自己接。
- 建议：**除非 ZCode 要内置真实 shell，否则不要引入**。工具调用的终端输出用等宽 `Text` + ANSI 解析即可（Operit 的 `CanvasMonospaceCodeBlockBody` 就是这条路）。

### 4.8 `androidx.compose.material3.adaptive`（Apache-2.0，官方）

- `adaptive` / `adaptive-layout` / `adaptive-navigation3`，提供 `ListDetailPaneScaffold`、`SupportingPaneScaffold`、`calculateWindowSizeClass`。
- RikkaHub 在版本目录里声明了但未使用；三个头部客户端 `windowSizeClass` 代码搜索全为空 —— **这是空白区**。
- 建议：**直接引入**，用于「手机单栏 / 平板双栏（会话列表 + 时间线）」。

### 4.9 反面案例

- `GetStream/stream-chat-android`：**专有许可**（LICENSE 首行 "PROPRIETARY PRODUCT"），无论多好用都不能进开源项目。
- `Termux` 整体：GPLv3，链接即传染。

---

## 5. 横切专题：六个真问题的行业做法

### 5.1 长列表性能

| 做法 | 出处 | 评价 |
|---|---|---|
| `itemsIndexed(key = { _, item -> item.id })`，稳定 id | RikkaHub `ChatList.kt:345` | 必需项，ZCode 已做（`Timeline.kt` 的 `unitKeys`，且对缺失 id 兜底 `#n`，比 RikkaHub 更严谨） |
| **未设 `contentType`** | RikkaHub | 反面：混排列表应给每类行一个 contentType |
| 顶部留白用 `contentPadding`，不占 item | RikkaHub `ChatList.kt:329-340` | 值得抄，减少一次组合 |
| 预取：距顶阈值触发向前加载 | ZCode `Timeline.kt`（自带，按 LazyColumn 可见项估算） | 已是自研优势 |
| 测量高度缓存 + prune | Operit `ChatMessageHeightMemory.kt` | 适合「切换会话恢复位置」，成本极低（41 行） |
| 稳定性配置集中管理 | LibreChat-Mobile `compose-stability.conf` | 长列表项目的加分项 |

**结论**：ZCode 在列表层面已不落后，唯一明确可补的是 `contentType` 分桶与「高度记忆」。

### 5.2 流式更新

三条技术路线，可叠加：

1. **限流重解析**（ZCode 现状）：`StreamTextThrottle`，80ms 窗口，纯追加时缓冲、换消息立即渲染、超时补最后一帧。优点：实现简单、正确性容易保证；缺点：长文本仍全量 parse。
2. **增量解析**（Operit / bettertext / mikepenz）：只重解析不稳定尾部，已闭合节点标 stable。优点：CPU 与文本长度近似线性；缺点：解析器必须支持「部分输入」，语法未闭合时行为要专门设计（bettertext 明确「临时闭合或隐藏」）。
3. **合并刷新**（Operit `RenderBatchCoordinator`）：revision 计数 + 固定间隔 flush，保证最后一次变更不丢。可与 1/2 叠加。

**给 ZCode 的具体建议**：保留现有采样器（它是正确的），在 `MarkdownParser` 上增加「尾部不稳定区间」概念——记录最后一个已闭合块边界，流式时只 parse 边界之后的部分，前面的 block 直接复用缓存对象。这是从「限流」升级到「增量」的最小改动，不需要换引擎。

### 5.3 工具调用分组

三种成熟范式：

| 范式 | 出处 | 形态 |
|---|---|---|
| **思维链步骤流** | RikkaHub `ChainOfThoughtScope` + `ChainOfThoughtStep` | reasoning 与 tool 共用一条带图标的垂直流，每步可展开，显示耗时；连续的多个 tool 自动聚成一组 |
| **工具轨迹块** | gpt_mobile `ToolTraceBlock.kt` | 一个可折叠容器内含事件卡片列表，支持搜索/过滤/导出 Markdown/长文本折叠 |
| **分组行** | ZCode `ToolGroupRow.kt`（`ToolFamily` 枚举按家族分组） | 按工具家族（编辑/搜索/终端）聚合，最省纵向空间 |

**结论**：ZCode 的 `ToolFamily` 分组已经解决了「刷屏」问题；缺的是 RikkaHub 的**「按工具名注册专用渲染器」**（`ToolUIRegistry`）。当前 `ToolIcons.kt` 只映射图标，正文是通用 JSON 渲染。建议加一层 `ToolRendererRegistry: Map<String, @Composable (JsonObject) -> Unit>`，对 `read/write/edit/bash/grep` 等高频工具给专用视图（文件路径 + 行号 + 摘要），未命中回落通用卡片。

### 5.4 代码块横向滚动

行业统一做法（RikkaHub 与 ZCode 一致）：

- **整行（行号列 + 代码列）一起横向滚动**，而不是只滚动代码列 —— 否则行号会与代码错位；
- `softWrap = false`；
- 等宽字体**关闭连字**（`"calt" 0, "liga" 0, "clig" 0`），否则 `->`、`!=` 会被合成字符，复制出错；
- 提供 `autoWrap` 开关（Operit `EnhancedCodeBlock`）；
- 行号列不参与选择，代码区可多行选择（RikkaHub 用 `SelectableContent` 包住代码列）。

**ZCode 现状（已核对源码）**：`CodeBlock.kt` 里 `Box(Modifier.fillMaxWidth().horizontalScroll(scrollState))` 包住代码 `Text`，`softWrap = false`，已有横向滚动与折叠/展开（`展开全部 N 行`）。**没有行号列**，所以不存在行号错位问题；字体是 `FontFamily.Monospace`（`Type.kt:89`）且未设置 `fontFeatureSettings`，当前不启用连字。若将来为了观感换成 JetBrains Mono 并加行号，必须把行号列放进同一个 `horizontalScroll` 容器，并显式关闭 `calt/liga/clig`。

### 5.5 深浅色主题

- **代码配色必须独立于 App 主题**：RikkaHub `CodeColor.kt` 定义了 Atom One Dark / Light 两套完整调色板（keyword/string/number/comment/function/operator/punctuation/className/property/boolean/variable/tag/attrName/attrValue/fallback 共 15 类），按 `darkTheme` 切换。
- **主题预设 + 动态取色 + AMOLED 三方共存**：RikkaHub `Theme.kt` 里 `ColorMode.SYSTEM → isSystemInDarkTheme()`，`dynamicColor && SDK >= S → dynamicDark/LightColorScheme`，再叠加 `amoledDarkMode` 把背景压到纯黑，并通过 `LocalDarkMode` 向下传递。
- gpt_mobile 用 `SyntaxThemes.atom(isDarkTheme)` 一行搞定（highlights 内置主题）。

**建议**：ZCode 的 `ZaiColors.kt` 若只有一套代码色，补一套 Light 变体；这是低成本高感知的改动。

### 5.6 平板/手机自适应

- 事实：**RikkaHub、Operit、gpt_mobile 均无 `windowSizeClass` 使用**（代码搜索验证）；只有 Operit 有 `ui/main/layout/TabletLayout.kt` 这一处手写平板布局。
- LibreChat-Mobile 的 `Adaptive*` 是「玻璃拟态 vs M3」的样式适配，不是断点布局。
- 可行方案：`material3-adaptive` 的 `ListDetailPaneScaffold`（手机=单栏，平板=左会话列表 + 右时间线），配合 `SupportingPaneScaffold` 放「工具详情/文件预览」。

**结论**：这是 ZCode 最容易做出差异化的点，且官方库 Apache-2.0、零风险。

---

## 6. 许可证矩阵与可复用性分级

### 6.1 分级定义

| 级别 | 含义 | 允许动作 |
|---|---|---|
| **L0 直接依赖** | Apache-2.0 / MIT，Maven Central 发布 | 加依赖即可 |
| **L1 可复制片段** | Apache-2.0 / MIT，源码可读 | 可复制代码，需保留版权与许可声明 |
| **L2 仅借鉴思路** | LGPL-3.0 | 读架构与交互，**代码自写**，不搬文件 |
| **L3 禁止参考实现** | AGPL-3.0 / GPL-3.0 / 专有 | 最多看产品行为，**不读不抄代码** |

### 6.2 逐项定级

| 项目 | License | 级别 | 说明 |
|---|---|---|---|
| `SnipMeDev/highlights` | Apache-2.0 | **L0** | Maven Central，`dev.snipme:highlights` |
| `java-diff-utils` | Apache-2.0 | **L0** | Maven Central |
| `mikepenz/multiplatform-markdown-renderer` | Apache-2.0 | **L0/L1** | Maven Central；代码亦可复制 |
| `halilozercan/compose-richtext` | Apache-2.0 | **L0/L1** | 维护放缓 |
| `androidx.compose.material3.adaptive` | Apache-2.0 | **L0** | 官方 |
| `re-ovo/bettertext` | MIT | **L0/L1** | 仅 JitPack；风险在维护而非许可 |
| `termux terminal-view/emulator` | Apache-2.0 | **L1** | 子模块可复制；但依赖 app 模块的 JNI，实际不可独立使用 |
| `Operit` | LGPL-3.0 | **L2** | 链接可以，复制文件不行 |
| `rikkahub/rikkahub` | AGPL-3.0 | **L3** | 网络传染；一行都别抄 |
| `Cocolalilal/LastChat` | AGPL-3.0 | **L3** | 同上 |
| `CherryHQ/cherry-studio` | AGPL-3.0 | **L3** | 同上 |
| `Taewan-P/gpt_mobile` | GPL-3.0 | **L3** | 分发即传染 |
| `chatboxai/chatbox` | GPL-3.0 | **L3** | 同上 |
| `Chevey339/kelivo` | AGPL-3.0 | **L3** | 同上 |
| `lobehub/lobehub` | LobeHub Community License | **L3** | 非 OSI，含商用限制 |
| `open-webui/open-webui` | 自定义 BSD 派生 | **L3** | 含品牌/规模条款 |
| `OpenHands/OpenHands` | MIT | L1（前端） | 可复制，但技术栈不同 |
| `slopus/happy` | MIT | L1 | 同上 |
| `GetStream/stream-chat-android` | 专有 | **禁用** | LICENSE 首行即 "PROPRIETARY PRODUCT" |

> 法律提示：以上为工程判断，不构成法律意见。AGPL/GPL 项目的「读代码后自己重写相同架构」在不同司法辖区风险不同，保守做法是**只看产品行为与公开文档，不逐行对照源码**。

---

## 7. 优先阅读的 10 个文件/模块

按「对 ZCode 的边际收益」排序：

| # | 文件 | 为什么读 | 对应 ZCode |
|---|---|---|---|
| 1 | `rikkahub/rikkahub: ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt` | 消息部件的密封类建模，决定 UI 能否穷举 | `ui/chat/timeline/*` 的行模型 |
| 2 | `rikkahub/rikkahub: app/.../ui/components/message/ChatMessageTools.kt`（529 行） | 工具调用 = 思维链步骤 + 审批状态机 + 耗时 | `ToolGroupRow.kt`、`RowActions.kt` |
| 3 | `rikkahub/rikkahub: app/.../ui/components/message/tools/ToolUI.kt` | 按工具名的渲染器注册表 | `ToolIcons.kt`（只有图标） |
| 4 | `rikkahub/rikkahub: app/.../ui/components/richtext/HighlightCodeBlock.kt`（534 行） | 横向滚动 + 行号 + 选择 + 复制/下载的完整闭环 | `markdown/CodeBlock.kt` |
| 5 | `rikkahub/rikkahub: ui/src/main/java/me/rerere/ui/richtext/DiffView.kt`（123 行） | 最小可用 diff 渲染（解析统计 + 逐行着色 + 横滚） | 缺失（`ArtifactRow` 只有元信息） |
| 6 | `AAswordman/Operit: app/.../ui/common/markdown/RenderBatchCoordinator.kt`（41 行） | 合并刷新且不丢尾帧的正确写法 | `markdown/MarkdownStream.kt` 对照 |
| 7 | `AAswordman/Operit: app/.../ui/common/markdown/StreamMarkdownRenderer.kt`（1249 行，重点读前 700 行） | 稳定节点 / 不稳定尾部的增量解析 | `markdown/MarkdownParser.kt` |
| 8 | `Taewan-P/gpt_mobile: app/.../ui/chat/StreamPresentation.kt`（76 行） | 字素安全的逐字呈现 | 若做打字机效果 |
| 9 | `Taewan-P/gpt_mobile: app/src/androidTest/.../ChatBottomAutoScrollerInstrumentedTest.kt` | 「贴底跟随」的可执行契约（4 个用例） | `timeline/Timeline.kt` 缺测试 |
| 10 | `garfiec/Librechat-Mobile: settings.gradle.kts` + `core/ui/src/commonMain/.../components/Adaptive*.kt` | Agent 客户端的模块切分与自适应组件族 | 整体架构预留 |

补充两个「便宜但有用」的：`AAswordman/Operit: ui/features/chat/components/ChatMessageHeightMemory.kt`（41 行，高度记忆）、`rikkahub/rikkahub: ui/theme/CodeColor.kt`（双套代码配色）。

---

## 8. 可直接引入的依赖/组件清单

### 8.1 建议立即引入（零/低风险）

| 依赖 | 坐标 | License | 用途 | 备注 |
|---|---|---|---|---|
| 自适应布局 | `androidx.compose.material3.adaptive:adaptive` / `adaptive-layout` | Apache-2.0 | 手机单栏 / 平板双栏 | 官方；同赛道无人使用，差异化点 |
| 语法高亮 | `dev.snipme:highlights:1.2.0` | Apache-2.0 | 替换/补齐自研 `MarkdownHighlighter` | 内置 atom 主题，支持增量缓存；**复用实例** |
| 文本 diff | `io.github.java-diff-utils:java-diff-utils:4.18` | Apache-2.0 | 文件变更预览 | 仅在实现 diff 视图时引入，约 100 KB |

### 8.2 条件引入（需评估）

| 依赖 | 坐标 | License | 触发条件 |
|---|---|---|---|
| Markdown 渲染全套 | `com.mikepenz:multiplatform-markdown-renderer` + `-m3` + `-code` | Apache-2.0 | 若决定放弃自研 parser（**不建议**，会丢掉 RichBlock/Mermaid/公式块能力） |
| LLM 富文本引擎 | `com.github.re-ovo.bettertext:text/markdown/highlight/latex`（JitPack） | MIT | 若决定重写渲染层以支持**行内公式换行 + 行内 composable**；需接受单作者 + JitPack 风险 |
| 富文本内联组件 | `com.halilibo.compose-richtext:richtext-commonmark` 等 | Apache-2.0 | 仅需只读内联组件时；编辑器场景慎用 |
| 终端渲染 | Termux `terminal-view` + `terminal-emulator` | Apache-2.0（子模块） | 仅当需要真实 PTY；否则用等宽 Text + ANSI 解析 |

### 8.3 明确不建议

- `stream-chat-android`：专有许可。
- 复制 RikkaHub / Operit / gpt_mobile 的任何源码文件。
- 用 `jeziellago/compose-markdown` 承载 Agent 流式长回复（无增量、无表格）。
- 用 WebView + MathJax 作为**主力**公式方案（手势冲突、首屏慢）；可作为 `$$` 块级公式的兜底（ZCode 现有 `RichBlock` 已是此路线，行内公式才是缺口）。

---

## 9. 对 ZCode 的具体行动建议（按性价比排序）

1. **补一组「贴底跟随」仪器测试**（照 gpt_mobile 的 4 个用例语义），锁住 `Timeline.kt` 的现有行为。成本半天，防止后续重构回归。
2. **给 `Timeline` 的行加 `contentType`**（文本/工具/推理/产物/子代理各一类），提升 LazyColumn 复用效率。
3. **加 `ToolRendererRegistry`**：按工具名映射专用 Composable，未命中回落通用卡片。这是 UI 观感提升最大的一处。
4. **代码块**：现有横向滚动与折叠已可用（无行号列）；若要加行号，务必与代码同处一个滚动容器，并显式关闭连字。可补 `autoWrap` 开关。
5. **补 Light 代码配色**（对齐 RikkaHub 的 Atom One Light 15 类色板）。
6. **流式解析升级为「尾部增量」**：在 `MarkdownParser` 记录最后闭合块边界，只重解析尾部；保留现有 80ms 采样器。
7. **引入 `material3-adaptive` 做平板双栏**：左会话、右时间线，工具详情走 `SupportingPane`。
8. **（可选）文件变更 diff**：`java-diff-utils` + 照 `DiffView.kt` 的 123 行思路自写渲染。

---

## 10. 附录：证据链接

- RikkaHub：https://github.com/rikkahub/rikkahub
  - 消息契约 https://github.com/rikkahub/rikkahub/blob/master/ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt
  - 时间线 https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
  - 工具调用 https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
  - 代码块 https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt
  - diff 视图 https://github.com/rikkahub/rikkahub/blob/master/ui/src/main/java/me/rerere/ui/richtext/DiffView.kt
  - 代码配色 https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/theme/CodeColor.kt
  - 主题 https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt
  - 依赖清单 https://github.com/rikkahub/rikkahub/blob/master/gradle/libs.versions.toml
- Operit：https://github.com/AAswordman/Operit
  - 流式渲染 https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/ui/common/markdown/StreamMarkdownRenderer.kt
  - 合并刷新 https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/ui/common/markdown/RenderBatchCoordinator.kt
  - 代码块 https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/ui/common/markdown/EnhancedCodeBlock.kt
  - 高度记忆 https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/ChatMessageHeightMemory.kt
- gpt_mobile：https://github.com/Taewan-P/gpt_mobile
  - 贴底跟随测试 https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBottomAutoScrollerInstrumentedTest.kt
  - 渐进呈现 https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/StreamPresentation.kt
  - 工具轨迹 https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ToolTraceBlock.kt
  - Markdown+高亮 https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatMarkdown.kt
- LibreChat-Mobile：https://github.com/garfiec/Librechat-Mobile （`settings.gradle.kts`、`core/ui/src/commonMain/kotlin/com/garfiec/librechat/core/ui/components/Adaptive*.kt`）
- bettertext：https://github.com/re-ovo/bettertext
- mikepenz renderer：https://github.com/mikepenz/multiplatform-markdown-renderer
- compose-markdown：https://github.com/jeziellago/compose-markdown
- highlights：https://github.com/SnipMeDev/highlights
- java-diff-utils：https://github.com/java-diff-utils/java-diff-utils
- compose-richtext：https://github.com/halilozercan/compose-richtext
- Termux（许可证说明 https://github.com/termux/termux-app/blob/master/LICENSE.md ）
- 其余对照项目：https://github.com/chatboxai/chatbox ｜ https://github.com/lobehub/lobehub ｜ https://github.com/CherryHQ/cherry-studio ｜ https://github.com/open-webui/open-webui ｜ https://github.com/OpenHands/OpenHands ｜ https://github.com/Chevey339/kelivo ｜ https://github.com/slopus/happy
