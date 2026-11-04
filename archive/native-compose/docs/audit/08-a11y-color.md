# 08 · 颜色令牌与无障碍审计

> 范围：默认主题（`:root,:host` + `.dark`，**排除** `.theme-zai-*`）的语义色令牌、对话页小字号可读性、Compose 触摸目标与语义。
> 输入：`archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BD2BL4Cc.css`（SHA-256 `40894faf…ebc2f`）、`android/app/src/main/kotlin/app/zemote/ui/theme/Color.kt`、`Theme.kt`、`docs/chat/07-css-tokens.md`（另引用 `docs/chat/06-composer.md` 的控件尺寸）。
> 方法：WCAG 2.x 相对亮度公式；半透明令牌按 sRGB 与实底背景 alpha 合成后计算；比值保留两位小数。阈值：正文 4.5:1（AA）、大字号 3:1（AA Large）、非文本 UI 边界 3:1（1.4.11）。12/14px 不属于大字号（大字号 = ≥24px 常规或 ≥18.66px 粗体），因此 AA Large 在对话页小字号场景**不适用**。

---

## 1. 结论摘要

1. **Compose 令牌与网页默认主题一致**：亮 `background #FAFAFA / foreground #404040 / brand #00BCFF / destructive #E7000B`，暗 `#171717 / #E5E5E5 / #00A6F4 / #FB2C36`，与 CSS `:root,:host` / `.dark` 及 `docs/chat/07:201-202` 完全对得上。对比度问题来自上游默认调色板本身，不是移植偏差。
2. **主表 14 个「令牌 × 主题」单元格中 8 个不达 AA**；叠加实际使用面（错误横幅、发送按钮图标）另有 4 项不达，**合计 12 项对比度失败**。
3. **最严重的是 `foreground-subtlest`**：亮 2.08:1 / 暗 2.40:1，连 3:1 都不到，却被用于工具行标题、工具摘要、推理正文（14px）、队列状态（9px）、输入占位符（16px）。
4. **`foreground-subtle` 亮色 3.31:1** 不达 4.5:1，被大量用于 12px 状态文本（`docs/chat/07:95-99,153`）。
5. **亮色 `brand` 作文字仅 2.09:1**；白图标压 `brand` 填充仅 2.18:1（亮）/ 2.71:1（暗），发送按钮图标本身不达 3:1。
6. **触摸目标：24 个可交互控件中 23 个小于 48dp**，只有轮次头（69dp）达标；工具行仅 21dp，发送/停止 32dp，Mode/Effort chip 约 23dp，附件删除 28dp。
7. **语义：整个 `app/zemote` 无一处 `Modifier.semantics` / `Role` / `stateDescription` / `liveRegion`**；聊天图片用 `pointerInput` 接管点击，TalkBack 完全无法聚焦或激活；折叠状态、模型选中态、上传进度均未暴露。
8. 另有口径瑕疵：`Color.kt:773`、`:958` 注释写「`.theme-zai-*` 口径」，但实际取值是默认主题（zai 亮色 brand 为 `#000`，而代码是 `#00BCFF`），注释会误导后续改色。

---

## 2. 对比度表

### 2.1 令牌主表（文字令牌按 4.5:1，边框/输入面按 3:1）

| 令牌 | 亮色（合成后） | 暗色（合成后） | 亮比值 | 暗比值 | AA 结论 |
|---|---|---|---|---|---|
| `foreground` / `background` | `#404040` / `#FAFAFA` | `#E5E5E5` / `#171717` | **9.93** | **14.23** | 双主题通过（且达 AAA 7:1） |
| `foreground-subtle` / `background` | `#8A8A8A` / `#FAFAFA`（`#40404099`） | `#939393` / `#171717`（`#E5E5E599`） | **3.31** | **5.84** | 亮色**不达 AA**（3.31 < 4.5；仅过 3:1，但 12/14px 不适用 AA Large）；暗色通过 |
| `foreground-subtlest` / `background` | `#B0B0B0` / `#FAFAFA`（`#40404066`） | `#555555` / `#171717`（`#E5E5E54D`） | **2.08** | **2.40** | **双主题均不达 AA**，且连 AA Large / 非文本 3:1 都不到 |
| `brand` / `background`（作文字） | `#00BCFF` / `#FAFAFA` | `#00A6F4` / `#171717` | **2.09** | **6.63** | 亮色**不达 AA**；暗色通过 |
| `destructive` / `background`（作文字） | `#E7000B` / `#FAFAFA` | `#FB2C36` / `#171717` | **4.57** | **4.71** | 双主题通过（亮色仅余量 0.07，压线） |
| `border` / `background`（非文本 3:1） | `#E2E2E2` / `#FAFAFA`（`#0A0A0A1A`） | `#2E2E2E` / `#171717`（`#FAFAFA1A`） | **1.24** | **1.32** | **双主题不达 3:1**（作装饰线可接受，作控件边界不可接受） |
| `input` / `background`（非文本 3:1） | `#FFFFFF` / `#FAFAFA` | `#262626` / `#171717` | **1.04** | **1.18** | **双主题不达 3:1**；输入卡边界另靠 `inputBorder`，仅 1.25 / 1.35 |

主表失败单元格：**8 / 14**。

### 2.2 实际使用面（对话页真实叠色）

| 场景 | 证据 | 亮比值 | 暗比值 | 结论 |
|---|---|---|---|---|
| 错误横幅：`destructive` 文字压在 `destructive` 10% 叠层上 | `ChatScreen.kt:401,410` | **3.83** | **4.34** | 双主题不达 AA（12px 文本） |
| 发送/停止图标：`foregroundInverse`（白）压在 `brand` 填充上 | `ChatScreen.kt:777,785` | **2.18** | **2.71** | 双主题不达非文本 3:1 |
| 占位符：`foregroundSubtlest` 压在 `input` 上 | `ChatScreen.kt:690-695` | **2.10** | **2.37** | 双主题不达 AA |
| 上传百分比：`brand` 文字压在 `surface` 上 | `ChatScreen.kt:654` | **2.09** | **5.59** | 亮色不达 AA |
| 队列卡片文本压在 `surface`（亮 `#F2F2F2` / 暗 `#232323`）上 | `ChatScreen.kt:496,528,543` | 3.21（subtle）/ **2.05**（subtlest） | 5.38（subtle）/ **2.39**（subtlest） | subtlest 双主题不达 AA |
| `destructive` 文字压在 `card` 上 | 暗色卡片 `#262626` | 4.77 | **3.97** | 暗色不达 AA |
| 禁用发送：`brand` 40% 叠在 `input` 上，白图标 | `ChatScreen.kt:777` | **1.41** | 7.67 | 禁用态 WCAG 豁免，仅记录（亮色几乎不可见） |

实际使用面新增失败：**4 项**（错误横幅亮/暗、发送图标亮/暗）。合计失败 **12 项**。

### 2.3 对话页 12/14px 小字号可读性

| 场景 | 字号 | 令牌 | 亮 | 暗 | AA（4.5） | AA Large（3.0） |
|---|---|---|---|---|---|---|
| 工具行标题 / 工具摘要 / `·` 分隔 | 14sp（`uiBase`，行高 21） | `foregroundSubtlest` | 2.08 | 2.40 | **失败** | **失败**（且 14px 不属大字号） |
| 推理正文（展开） | 14sp | `foregroundSubtlest` | 2.08 | 2.40 | **失败** | **失败** |
| 工具状态 / 子行 / 回答文本 | 12sp（`uiSm`） | `foregroundSubtle` | 3.31 | 5.84 | **亮色失败** | 亮色过 3:1，但 12px 不适用 |
| 队列状态「排队中/待发送」 | 9sp（`ui2xs`） | `foregroundSubtlest` | 2.08 | 2.40 | **失败** | **失败** |
| 输入占位符 | 16sp（`mobileInputSafe`） | `foregroundSubtlest` | 2.10 | 2.37 | **失败** | **失败** |
| 模型 / Effort chip | 10sp（`uiXs`） | `foregroundSubtle` | 3.31 | 5.84 | **亮色失败** | 不适用 |
| 上传百分比 | **7sp**（`ChatScreen.kt:835`） | `foreground` / `brand` | 9.93 / 2.09 | 14.23 / 5.59 | 亮色 brand 失败 | — |

结论：**12/14px 小字号在亮色主题下没有一档达到 AA**；`foreground-subtlest` 在两种主题下均不可读。另：`ui2xs = 9sp`、`uiXs = 10sp` 已低于 Android/Material 建议的 12sp 下限，`7sp` 进度文本属于纯装饰级尺寸。

---

## 3. 触摸目标表

> 判定线：≥48×48dp（任务口径；Android 建议值）。附注 WCAG 2.5.8 AA 的最低 24×24dp：仍有 21dp 工具行、23dp chip、22/26dp 文本展开低于该线。
> 「热区」按修饰符链上 `clickable` 附着盒计算；M3 `IconButton` 内部 48dp `minimumInteractiveComponentSize` 只扩布局、不扩命中盒，故以传入 `Modifier.size(N)` 为准（待实测复核，见 §6）。

| 控件 | 视觉尺寸 | 热区 | 结论 |
|---|---|---|---|
| 工具调用行摘要（`ToolCallRow.kt:138-141`） | 高 21dp × 满宽 | 21dp | **不足**（<48，且 <24） |
| 子代理合并行（`ToolCallRow.kt:85-96`） | 高 21dp × 满宽 | 21dp | **不足** |
| 推理触发器（`ReasoningRow.kt:71-73`） | 高 21dp × 满宽 | 21dp | **不足** |
| 工具分组行（`ToolGroupRow.kt:99-104`） | 高 37dp（21 + 8+8） | 37dp | **不足** |
| 轮次头（`TurnHeaderRow.kt:57-61`） | 高 69dp（40 + 21 + 8） | 69dp | 达标 |
| 发送按钮（`ChatScreen.kt:772-779`） | 32×32dp | 32×32dp | **不足** |
| 停止按钮（`ChatScreen.kt:753-759`） | 32×32dp | 32×32dp | **不足** |
| Mode chip（`ChatScreen.kt:852-856`） | 约 23dp 高（10sp×1.5 + 8） | 23dp | **不足** |
| Effort chip（同一 `ChipMenu`） | 约 23dp 高 | 23dp | **不足** |
| 模型按钮（`ChatScreen.kt:730-733`，M3 `TextButton`） | 40dp 高（M3 最小） | 40dp（48dp 仅布局预留） | **不足** |
| 附件添加入口（`ChatScreen.kt:710`，`IconButton` 28） | 28×28dp | 28×28dp | **不足** |
| 附件删除（`ChatScreen.kt:657-659`，`IconButton` 28） | 28×28dp | 28×28dp | **不足** |
| 顶栏返回（`ChatScreen.kt:341`，36） | 36×36dp | 36×36dp | **不足** |
| 顶栏更多（`ChatScreen.kt:364`，36） | 36×36dp | 36×36dp | **不足** |
| 错误横幅关闭（`ChatScreen.kt:415`，28） | 28×28dp | 28×28dp | **不足** |
| 代码块复制（`CodeBlock.kt:128-132`） | 28×28dp | 28×28dp | **不足** |
| 代码块展开/收起（`CodeBlock.kt:171-176`） | 约 26dp 高 × 满宽 | 26dp | **不足** |
| shell「展开命令」（`ToolCallRow.kt:284-291`） | 约 22dp 高 × 文本宽 | 22dp | **不足** |
| 用户消息展开（`UserInputRow.kt:193-205`） | 24×24dp | 24dp | **不足** |
| 回到底部（`Timeline.kt:305-323`） | 32×32dp | 32dp | **不足** |
| 模型列表行（`ModelSheet.kt:179-182`） | 41dp 高（21 + 10+10） | 41dp | **不足** |
| 模型面板关闭（`ModelSheet.kt:108`，36） | 36×36dp | 36×36dp | **不足** |
| 子代理面板关闭（`ChatScreen.kt:928`，默认 40） | 40×40dp | 40dp | **不足** |
| 全部 M3 `TextButton`（重试 `ChatScreen.kt:414`、继续 `:511`、交互选项 `:466`、对话框按钮 `:282,297`） | 40dp 高 | 40dp | **不足**（一类问题） |

**统计：24 个可交互控件，23 个不足 48dp，仅 1 个达标。** 视觉尺寸与热区基本相等（未使用 `minimumInteractiveComponentSize` 包裹外层），因此不存在「看着小但好点」的补偿。

---

## 4. Compose 语义 / contentDescription 缺失

全仓 `app/zemote` 检索 `Modifier.semantics`、`clearAndSetSemantics`、`Role.`、`stateDescription`、`liveRegion`：**0 命中**。

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| 1 | `Images.kt:109-118`（`RemoteImage`） | 用 `pointerInput + detectTapGestures` 实现点击，未加 `clickable`/`semantics`；图片 `contentDescription = null`（`:125`） | TalkBack/开关控制**无法聚焦或打开聊天图片**；用户附件与 markdown 图片均受影响 |
| 2 | `Images.kt:165-181`（全屏查看器） | 图片 `contentDescription = null`，无关闭按钮，仅点击/系统返回退出，无缩放语义 | 读屏用户不知道当前是什么、如何关闭 |
| 3 | `ToolCallRow.kt:138-141`、`ReasoningRow.kt:71-73`、`ToolGroupRow.kt:99-101`、`TurnHeaderRow.kt:57-61` | `clickable` 无 `role`、无 `stateDescription`（展开/收起） | 只读出文本，不读「可展开/已展开」；无按钮角色 |
| 4 | `ChatScreen.kt:852-856`（`ChipMenu`） | 可点击 `Row` 无 `Role`、无展开态、无当前选中值；chevron `contentDescription = null` | Mode/Effort 只被读成一段文字，用户不知道可切换及当前值 |
| 5 | `ChatScreen.kt:753-759,772-779`（发送/停止） | `clickable` Box 无 `role = Role.Button`；禁用原因未暴露 | 可读「发送」，但不播报按钮角色/禁用原因 |
| 6 | `ChatScreen.kt:657-663`（附件删除） | 描述固定为「移除附件」，不含文件名 | 多附件时无法区分删哪一个 |
| 7 | `ModelSheet.kt:179-182`（模型行） | 可点击 `Row` 无 `Role.RadioButton`、无 `selected` 语义；选中仅靠颜色 + 勾图标（勾的 `contentDescription` 是兄弟节点） | 读屏无法得知当前选中模型 |
| 8 | `ChatScreen.kt:905-909`（子代理面板遮罩） | 满屏 `clickable` 无标签、无 `Role` | 出现一个无名称的可点击节点 |
| 9 | `ChatScreen.kt:396-418`（错误横幅）、全仓流式文本 | 无 `liveRegion` | 新错误、流式回答、队列变化不会被主动播报 |
| 10 | `ChatScreen.kt:808-839`（`UploadProgress`） | Canvas 绘制进度，无 `progressBarRangeInfo`；唯一文本为 7sp | 读屏拿不到上传进度 |
| 11 | `ChatScreen.kt:690-696`（输入占位符） | 占位符是 `decorationBox` 里的独立 `Text`，`BasicTextField` 无 label 语义 | 空输入框可能无标签或被读成两个节点 |
| 12 | `MarkdownText.kt` 标题块 | 无 `heading()` 语义 | 读屏无法按标题跳转长回答 |
| 13 | `CodeBlock.kt:130-142` | 复制后无 `liveRegion`/提示播报 | 操作结果不可感知 |
| 14 | `ToolCallRow.kt:128,163` 等 | `Text("·")` 分隔符被读作「点」 | 语音冗余（低风险） |

> 说明：装饰性图标（`StatusGlyph`、`ExpandChevron`、列表 leadingIcon）用 `contentDescription = null` 是正确的，不计入缺失；但状态图标若承担唯一状态表达（如 `RowComponents.kt:52-78`）需确保同一行文本已含状态。

---

## 5. 建议修法（保持 T1 视觉）

原则：**填充/图标/分隔线继续用现有令牌，只给「小字号文字」增加可达标变体**，避免整页换色。

1. **新增文本专用 token，不改现有 token 值**
   - `foregroundSubtleText`：亮 `#6B6B6B`（5.11:1）/ 暗 `#A3A3A3`（7.11:1）。
   - `foregroundSubtlestText`：亮 `#6B6B6B`（5.11:1）/ 暗 `#9A9A9A`（6.37:1）。
   - 现有 `foregroundSubtle` / `foregroundSubtlest` 保留给图标、`·`、marker、chevron、边框；工具行/推理正文/队列状态/占位符改用 `*Text`。视觉上只是灰字略深，主色块与布局不变。
   - 若必须保留透明度写法：亮色 `subtle` alpha 提到 0.73（`#727272`，4.61:1）；暗色 `subtlest` alpha 提到 0.51（`#808080`，4.54:1）。
2. **brand 文字**：亮色改用 `sky-700 #0069A8`（5.61:1）作文字色；暗色维持 `#00A6F4`（6.63:1）。`brand` 填充值不动。
3. **destructive 文字**：亮色在 10% 叠层上改用 `red-700 #C10007`（叠层上约 5.2:1）；暗色改用 `#FF8A8A`（叠层上 7.29:1）。按钮填充仍用 `red-600/500`。
4. **发送/停止图标**：保持 `brand` 填充，把图标色从白改为黑（亮 9.64:1、暗 7.76:1）；若坚持白图标，则亮色填充需降到 `#0084D1`（4.02:1）、暗色降到 `#0090D0`（3.56:1），仅改按钮不改 `brand` 令牌。
5. **输入卡边界**：`input` 与 `background` 差仅 1.04/1.18，`inputBorder` 1.25/1.35。给 composer 外框单独加一档 ≥3:1 的描边（亮 `#8A8A8A`、暗 `#6B6B6B`），或加深现有 `inputBorder` 到 20%–25% 并复测；否则 1.4.11 的控件边界不成立。
6. **触摸目标**：对每个交互控件在**最外层**套 `Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable{…}, contentAlignment = Center)`，不要只在内部 `IconButton` 上写 `Modifier.size(N)`。
   - 时间线行改为 `heightIn(min = 48.dp)`，同时把 `Timeline.kt` 的 `Arrangement.spacedBy(16.dp)` 降到 8dp，保持视觉节奏与总高度近似。
   - 发送/停止 32dp 视觉不变，外层扩到 48dp；chip 的 23dp 视觉不变，外层扩到 48dp。
   - 代码复制、附件删除、返回、更多等 `IconButton` 改为 `Modifier.size(48.dp)`，图标仍用原尺寸（14/16/18/20dp）；不要依赖 M3 的 48dp 布局预留，因为 `clickable` 附着在内部更小的盒子上。
7. **语义**：
   - 工具/推理/分组/轮次行加 `role = Role.Button` + `stateDescription = if (open) "已展开" else "已收起"`。
   - 模型行加 `role = Role.RadioButton` + `selected = selected`。
   - `RemoteImage` 的点击改为 `Modifier.clickable`，并给 `contentDescription`（附件名/图片 alt，缺省「图片」）。
   - 错误横幅加 `liveRegion = LiveRegionMode.Assertive`；流式回答尾部加 `Polite`。
   - `UploadProgress` 加 `progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)`。
   - 附件删除描述改为 `"移除附件 ${attachment.name}"`。
8. **字号下限**：`ui2xs`（9sp）与 `uiXs`（10sp）在对话页承担状态/标签信息，建议至少提到 12sp；`7sp` 上传百分比改为图标或 12sp。

---

## 6. 未验证项

1. **未在真机/模拟器上做 TalkBack 实听**：语义缺失项来自静态代码与 Compose 语义规则推导，实际播报顺序（尤其 `clickable` 是否合并子节点）需用 Accessibility Scanner / TalkBack 复核。
2. **`IconButton` 命中区边界**：M3 `minimumInteractiveComponentSize` 与调用方 `Modifier.size(N)` 的先后顺序决定最终命中盒。本报告按「命中盒 = 传入尺寸」判定；需用 Layout Inspector 或 `onGloballyPositioned` 实测确认 28/36dp 按钮是否被系统扩到 48dp。
3. **半透明令牌合成误差**：CSS 的 `color-mix(in oklab, …)` 与 sRGB 直接 alpha 合成在感知上可能有 ≤0.05 的比值差异；本报告按 sRGB 合成计算。
4. **系统字体缩放**：所有比值与尺寸按 `fontScale = 1.0` 计算；用户放大字号后行高会增大，但对比度不变。
5. **网页端实际渲染**：未启动浏览器渲染 `index-BD2BL4Cc.css` 取样，颜色值取自 CSS 声明与 `docs/chat/07` 的令牌表，二者一致。
6. **`destructive` 在暗色 `card` 上的使用点**：代码中主要在 `background` 与 10% 叠层上使用，`card` 上的 3.97 仅作为潜在场景记录，未逐屏确认。
7. **触摸目标的 48dp 口径**：任务指定 48dp；WCAG 2.5.8 AA 实际最低为 24×24 CSS px，两者结论方向一致但严格程度不同。
