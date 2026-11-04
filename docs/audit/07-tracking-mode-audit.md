# 07 · 对话页底部「追踪模式」审计

- 审计日期：2026-11-03
- 审计范围：`android/app/src/main/kotlin/app/zemote/`（对话页底部工具条）、`rust/crates/protocol/`、`Reference_code/`（Flutter 旧实现）、`Reference_web/3.17.1/`
- 审计方式：只读代码走查 + 18 条静态断言 + Rust 单测 + 真机 UI dump / logcat（未改任何代码）
- 审计基线：工作区未提交改动（HEAD `e982d62`，工作区另有大量未提交改动）。**行号以 2026-11-03 21:40 的工作区为准**；审计期间 `ChatViewModel.kt` 被外部进程改写过一次（mtime 21:36:05），`switchMode` 函数体与 HEAD 一致，但其他区域行号可能继续漂移，引用以函数名为准。

---

## 0. 结论

**结论：部分正确（不是纯 UI 假功能，但也远未“正确运行”）。**

| 维度 | 判定 | 说明 |
| --- | --- | --- |
| UI 是否存在 | 是 | 输入框底部工具条第二个图标按钮（`<>` 图标，无可见文字），运行时 contentDescription 为「模式：变更前确认」 |
| 点击是否有回调 | 是 | `onMode` → `ChatScreen.onSwitchMode` → `AppNavHost` → `ChatViewModel::switchMode` |
| 是否真的发到桌面端 | **是（代码路径 + 真机日志均已证实）** | `conversation_call op=switchCollaborationMode` → Rust 生成 `[v4] command switchCollaborationMode`，带 CAS `baseRevision` |
| 现有会话切换是否可靠 | **否（实测超时）** | 真机上该会话的 `subscribe` 也已连续超时；模式命令 30 s 无 ack，最终报超时 |
| 新会话（草稿态）能否切模式 | **否（静默 no-op）** | `selectedTaskId == null` 直接 `return`，无提示、无本地状态、不下发 |
| 桌面端确认后是否回填 UI | 理论可以，实际不保证 | 只靠 `config` 快照/patch 回填；无乐观更新，无成功提示，存在竞态 |
| 被桌面端拒绝是否提示 | **否（静默）** | `switchMode` 丢弃 ack 返回值，只处理异常（超时/传输失败） |
| 失败能否重试该动作 | **否** | 错误条「重试」绑定的是整页 `refresh()`，不会重发模式切换 |
| 是否影响下一条消息 | 是（有条件） | `sendText` 每次携带 `config.mode` + `planEnabled`；但 config 未及时回填时会带旧模式 |
| 与 Web 3.17.1 语义是否一致 | 否 | Web 有 Plan 入口、模式描述、图标+名称、yolo 二次确认；Android 全缺 |
| 与 Flutter 旧实现是否一致 | 部分一致（且同样有缺口） | Flutter 支持草稿态选模式、ack 拒绝提示、乐观回填；Android 均缺 |

一句话：**按钮背后的协议链路是真的，但“用户看到它生效”这件事不成立**——草稿态完全无效，已存在会话切模式没有即时反馈、拒绝无提示、Plan 不可选；本次真机实测中该会话本身已失联，模式命令 30 s 超时。

---

## 1. 术语澄清：仓库里没有「追踪模式」

在以下位置做了全量检索，均未找到名为「追踪 / 跟踪 / tracing / tracking / trace」的对话模式：

- `android/app/src/main/kotlin/`：命中仅为 `ZLog` 堆栈 trace、`workflowTrace` 主题色、滚动条 track、git untracked，与对话模式无关。
- `rust/crates/protocol/` 的 `ConversationTransport::OPS`（`conversation_transport.rs:945-993`）：没有 trace/tracking op。
- `Reference_code/lib/`（Flutter）与 `Reference_web/3.17.1/assets/*.js`：没有任何「追踪」字符串。
- Web 3.17.1 的模式集合只有 `build / edit / plan / yolo / guarded`（`src-BSClmRM9.js` 偏移 38278、38627）。

**因此，用户所说的「追踪模式」在本仓库中不存在同名字面功能。** 对话页底部工具条唯一的模式控件是「协作模式」按钮：运行时下拉仅有 **变更前确认 / 自主模式 / 完全访问** 三项。下文按这个控件审计；如果用户看到的是另一个按钮，则它不在当前源码/安装包里，需要用户提供截图再定位。

> 若“追踪模式”实指 **自主模式（guarded / Autonomous mode）**，结论同样适用：它走的就是本文审计的 `switchCollaborationMode` 链路。

---

## 2. UI 定义与点击路径

### 2.1 定义位置

| 元素 | 证据 |
| --- | --- |
| 工具条结构说明 | `android/app/src/main/kotlin/app/zemote/ui/chat/ChatComposer.kt:81`：「工具条（附件 / 模型 / 模式 / 思考 / 发送或停止）」 |
| 按钮挂载 | `ChatComposer.kt:190` `ModeButton(mode = mode, onSelect = onMode)` |
| 按钮实现 | `ChatComposer.kt:270-317`（图标按钮 + `DropdownMenu`） |
| 当前值 | `ChatComposer.kt:273` `mode?.takeIf { it.isNotBlank() } ?: "build"` |
| 无障碍文案 | `ChatComposer.kt:284` `contentDescription = "模式：${modeLabel(current)}"` |
| 菜单选项 | `ChatComposer.kt:547-554` `base = listOf("build", "guarded", "yolo")`，未知当前值追加到末尾 |
| 文案映射 | `ChatComposer.kt:557-564`：build=变更前确认、guarded=自主模式、plan=计划模式、yolo=完全访问、edit=编辑 |
| 图标映射 | `ChatComposer.kt:566-572`：build→`Code`、guarded→`Lock`、plan→`Description`、yolo→`Warning`、edit→`Edit` |
| 颜色 | `ChatComposer.kt:285` 仅 `yolo` 用 `colors.warning`，其余统一 `foregroundSubtle` |

UI 特征：**图标按钮、无可见名称、无 chevron**。真机截图/UI dump 中它就是一个 `<>`（Code 图标）；用户只能长按/读屏或点开菜单才知道当前模式。这直接解释了“只看到 UI、不知道是否生效”的观感。

### 2.2 点击链

```
ModeButton.onSelect(id)
  → ChatComposer(onMode = onMode)                     ChatComposer.kt:190 / 104
  → ChatScreen(onMode = onSwitchMode)                 ChatScreen.kt:314
  → AppNavHost(onSwitchMode = viewModel::switchMode)  AppNavHost.kt:420
  → ChatViewModel.switchMode(mode)                    ChatViewModel.kt:1635-1653
```

`ChatScreen` 传给 composer 的当前值来自对话 config：

```kotlin
mode = state.config?.get("mode").asString(),   // ChatScreen.kt:292
```

### 2.3 真机核对（只读）

真机：`3B6F5RE8GCL3LYY7`（PLC110），安装包 `app.zemote.debug`，`versionName=0.1.0-local-debug`，`lastUpdateTime=2026-11-03 21:24:56`。

- `uiautomator dump`：`desc="模式：变更前确认"`，bounds `[259,2555,315,2611]`，位于输入框底部工具条。
- 点开后菜单节点：`变更前确认`（带「当前模式」勾选）、`自主模式`、`完全访问`；**没有「计划模式」**。
- 点“返回”关闭菜单后，当前模式仍为「变更前确认」，未发生切换（本次刻意只点当前模式，见 §6）。

---

## 3. 协议链路：确实发到桌面端

### 3.1 Kotlin 侧

```kotlin
fun switchMode(mode: String) {
    val sessionId = _state.value.selectedTaskId ?: return      // 1636：草稿态静默退出
    viewModelScope.launch {
        try {
            chatOps.call(
                "switchCollaborationMode",                         // 1641
                buildJsonObject { put("sessionId", sessionId); put("mode", mode) },
                conversationScope(),
            )
            refreshConversationState()                            // 1644
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }  // 1647
        }
    }
}
```

- `ChatOps.call` 是对话层唯一入口（`ui/chat/ChatOps.kt:119-126`）；`EngineChatOps` 从注册表取句柄后调用 `engine.conversationCall`（`ChatOps.kt:130-171`）。
- `EnginePort.conversationCall` → `ZemoteEngine.conversationCall`（`ui/EnginePort.kt:561-592`、`engine/ZemoteEngine.kt:426-431`），最终发出 §13.2 信封 `{"method":"conversation_call","session":N,"key":"...","op":"switchCollaborationMode","args":{...}}`。
- **返回值被丢弃**：`chatOps.call(...)` 的 ack 没有赋给任何变量；只有抛异常才会进 `error`。

### 3.2 Rust 协议层

| 环节 | 证据 |
| --- | --- |
| op 白名单 | `conversation_transport.rs:961`（`OPS` 列表含 `switchCollaborationMode`） |
| op 分发 | `conversation_transport.rs:1402-1413`：必填 `sessionId`、`mode`，`enqueue_command(..., "switchCollaborationMode", payload, ...)` |
| payload | `conversation.rs:456-459`：`{"mode": mode}` |
| CAS 命令集 | `conversation.rs:107-123`：`switchCollaborationMode` 在 `CAS_COMMANDS` 中，会带 `baseRevision` |
| 信封构造 | `conversation.rs:240-272`：CAS 命令写 `baseRevision` |
| FFI 透传 | `rust/crates/ffi/src/conversation.rs:377-412`：`conversation_call` 校验 op 后原样驱动 |
| 失败映射 | 超时/传输错误 → `completed.outcome=failed` → Kotlin `EngineException` |

结论：**Rust 侧不是空实现，命令真实进入 V4 传输层并带 CAS 修订号发往桌面端。**

### 3.3 真机日志（关键证据）

```
21:33:57.120 [FFI/conv] #15 → conversation_call op=switchCollaborationMode
             session=1 key=E:\UUremote args={mode:str5,sessionId:str41}
21:33:57.124 [PROTO/conv] event session=1 key=E:\UUremote opAccepted
21:33:57.125 [PROTO/conv] event session=1 key=E:\UUremote log [v4] command switchCollaborationMode
21:34:27.275 [FFI/conv] #15 ← conversation_call op=switchCollaborationMode failed
             ms=30154 kind=timeout : EngineException: timeout:
             zcode-agent.sendConversationCommandV4 timed out
```

`mode:str5` = `build`（5 字符）。这说明：

1. 点击确实触发了 ViewModel → ChatOps → `conversation_call`；
2. Rust 确实生成了 V4 命令；
3. **桌面端没有回 ack**，30 s 后命令超时；
4. UI 随后显示错误条：`操作超时：zcode-agent.sendConversationCommandV4 timed out`（`ChatScreen.kt:246` 的 `StatusNotice`，带「重试」和「×」）。

同一会话的订阅也早已失联：

```
21:33:09.563 #14 → conversation_call op=subscribe ...
21:34:09.619 #14 ← conversation_call op=subscribe failed ms=60055 kind=timeout
```

应用日志显示 `E:\UUremote` 会话从 21:27 起 `subscribe` 连续 ack 超时，而另一个会话（`session=2 key=E:\Zcode_android`）仍在持续收到 `rowsUpdated` 帧。**因此本次超时的根因是该会话/桥在桌面端已不可用，模式命令只是跟着一起超时；不能据此断定桌面端会拒绝模式切换。** 但这也暴露了 Android 端不检查会话健康、按钮照常可点、把 30 s 超时直接甩给用户的问题。

---

## 4. 状态回读、失败提示、重试、下一条消息

### 4.1 回读：只靠服务端 config，无本地乐观状态

`config` 的三条写入路径：

| 来源 | 证据 |
| --- | --- |
| 主动拉快照 | `ChatViewModel.refreshConversationState()`（`ChatViewModel.kt:1750-1813`），`config = conversation?.get("config")`（1812） |
| `rowsUpdated` 首帧快照 | `applyConversationFields`（1915-1925），`config = snapshot["config"]`（1924） |
| `state.updated` 增量 patch | `applyStatePatch`（1964-1974），`config = patch["config"]`（1973） |

`switchMode` 在 ack 后调用一次 `refreshConversationState()`（1644），但：

- 没有任何**乐观更新**（不像 Flutter 的 `state.optimisticPatch`）；
- 没有 loading 态、没有成功提示；
- 菜单在点击瞬间就 `open = false`（`ChatComposer.kt:309-311`），之后界面毫无变化，直到 config 快照/patch 回来；
- 如果桌面端没有推 patch（或 patch 不含 `config`），模式图标永远停在旧值，**与“点了没反应”完全不可区分**。

### 4.2 失败提示：只覆盖异常，不覆盖“被拒绝”

- `switchMode` 只 `catch` 异常；超时/传输失败会写入 `state.error`，真机上确实显示了错误条。
- **ack 的业务拒绝是静默的**：`switchCollaborationMode` 的 ack 形如 `{status, reasonCode, message, revisionAtDecision}`。Rust 把 ack 原样作为 `Completion::Value` 返回（`conversation_transport.rs` 的 `on_command_response` → `emit_completion(Completion::Value(res))`），Kotlin 拿到的是一个普通 `JsonElement`，**`switchMode` 不看 `status`**。
- 对比 Flutter：`_apply` 会检查 `res['status'] != 'accepted'` 并弹「被拒绝: reason」（`Reference_code/lib/ui/chat_page.dart:3998-4019`）。
- 后果：桌面端返回 `rejected / stale / badRequest` 时，Android 既不提示、也不回滚、也不重试，用户只会看到模式没变（或更糟：本地看似没变，桌面端其实变了）。

### 4.3 重试：按钮重试的不是这个动作

- `ChatScreen.kt:246` 把错误条的 `onRetry` 固定绑到 `onRefresh`（`AppNavHost.kt:412` `viewModel::refresh`）。
- `refresh()` 只做「重开工作区/重连会话」（`ChatViewModel.kt:426-445`），**不会重发 `switchCollaborationMode`**。
- 用户点「重试」后，模式仍然需要重新点一遍；错误条文案也没有说明这一点。

### 4.4 对下一条消息的影响

`sendText` 两条路径都会调用 `putSendSubmission()`：

- 已有会话：`ChatViewModel.kt:527`；
- 新会话首条：`ChatViewModel.kt:882`。

`putSendSubmission`（`ChatViewModel.kt:1058-1086`）：

```kotlin
val rawMode = config?.get("mode")...
val plan = rawMode == "plan" || config?.get("planEnabled") == true
val mode = when (rawMode) { "edit", "yolo", "guarded" -> rawMode; else -> "build" }
put("mode", mode)
put("planEnabled", plan)
```

结论：

- 模式**会**随下一条消息发到桌面端（Rust `sendText` 也透传 `mode/planEnabled/modelSelection`，`conversation_transport.rs:1335-1363`）。
- 但取值来自 `state.config`。若切换后 config 尚未回填，下一条消息仍带旧模式；而桌面端会话模式可能已是新模式，存在**消息级 mode 覆盖会话级 mode**的竞态风险（未实测桌面端合并语义，标注为风险）。

### 4.5 新会话（草稿态）：完全无效

- 新会话路由 `Route.Chat(taskId = null)`（`AppNavHost.kt:191-201`，`onNewConversation`）。
- `switchMode` 第一行 `selectedTaskId ?: return`（`ChatViewModel.kt:1636`）：**不发命令、不改状态、不提示**。
- `ChatUiState` 没有 `draftMode`/`draftConfig` 字段（`ChatViewModel.kt:194-251` 附近的 state 定义），`createConversation` 调用 `createSession` 时只传 `workspaceId` + `timeoutSecs`（`ChatViewModel.kt:838-841`），没有 `config`。
- 对比 Flutter：有 `_draftConfig`（`chat_page.dart:255`），草稿态点模式走 `onDraftChange('mode', ...)`（3908-3914），发送时用 `_buildDraftConfig()` 把 `{mode}` 放进 `createSession(config:)`（839-857、686-693）；Rust 的 `createSession` 也支持 `config`（`conversation_transport.rs:1491-1503`）。
- 结论：**在“新对话”页面，模式按钮是彻底的假功能**；用户点任何一项都不会有任何反应（连错误都没有）。这是本次审计最确定的功能缺口。

---

## 5. 与 Flutter / Web 3.17.1 的语义对照

### 5.1 模式集合

| 端 | 可选集合 | 证据 |
| --- | --- | --- |
| Android | build / guarded / yolo（`plan` 仅当当前已是 plan 才追加；`edit` 同理） | `ChatComposer.kt:547-554`；真机菜单只有三项 |
| Flutter | prep 提供的 mode options；无 prep 时回退 `[build, edit, plan, yolo]` | `chat_page.dart:3884-3940`（回退列表 3925） |
| Web 3.17.1 | `build / guarded / plan / yolo`（+ 兼容 `edit`），另有 plan 独立复选项 | `src-BSClmRM9.js` 偏移 38278 `R=[...]`、38627 `ai=new Set([...R.map(id),"edit"])`；`docs/audit/05-composer.md:168-182` |

**Android 缺 Plan 入口**：`ChatComposer.kt:546` 的注释「网页端 Mode 菜单不含 plan（plan 由独立开关控制）」在当前 Android 上不成立——Android 并没有那个“独立开关”。结果是用户无法从 build/guarded/yolo 切到 plan。真机菜单已确认无「计划模式」。

### 5.2 切换行为

| 行为 | Android | Flutter | Web 3.17.1 |
| --- | --- | --- | --- |
| 草稿态可选模式 | 否（静默 return） | 是（`onDraftChange` + createSession config） | 是（草稿配置） |
| ack 拒绝提示 | 否（静默） | 是（SnackBar「被拒绝」） | 是（错误提示） |
| 乐观更新 | 否 | 是（`optimisticPatch`） | 是（即时 UI） |
| 成功反馈 | 无（等 config 回填） | 关闭面板 + 本地 patch | 即时 |
| 失败重试 | 整页 refresh（不重发） | 用户重开面板再点 | 用户重开面板再点 |
| yolo 二次确认 | 无 | 无 | **有**（`index-Dwhx77cK.js` 偏移 1872525：非 yolo→yolo 时弹 `mode.yoloConfirmation`，含风险勾选） |
| 菜单项描述 | 无 | 有（来自 prep option） | 有（名称+描述，52px 行） |
| 触发器外观 | 16px 图标、无文字、无 chevron | 列表/ChoiceChip | 图标+名称+chevron，28px 高 |

### 5.3 文案/图标差异

- Web 文案：`mode.label.glm.build=变更前确认`、`guarded=自主模式`、`plan=计划模式`、`edit=自动编辑`（`IntlProvider-CneXitqp.js` 偏移 321457-321546）。Android 的 `edit` 文案是「编辑」，与 Web 的「自动编辑」不一致。
- Web 图标：build=hand、plan=notepad-text、guarded=shield-check、yolo=shield-alert（`docs/audit/05-composer.md:168`，JS 偏移 2242036）。Android 用 Material 的 Code/Lock/Description/Warning，语义不一致（例如 build 显示成 `<>`，容易被误读为“代码/追踪”）。
- Web 对未知值做归一化：`oi(e) = ai.has(e) ? e : 'build'`（`src-BSClmRM9.js` 偏移 38666）。Android 反而把未知值（如 `default`）追加为可选项（`ChatComposer.kt:549-553`），用户可能选中并下发 Web schema 不允许的 `mode=default`（`parts-Ba2kLaRj.js` 偏移 39023 的枚举不含 `default`）。这是一个边缘但真实的非法值风险。

---

## 6. 验证记录

### 6.1 静态断言（18/18 通过）

使用内联 Python 对源码做只读断言（未写脚本文件）：

- `switchMode` 存在，且无 `selectedTaskId` 时静默 `return`；
- `switchMode` 调用 `switchCollaborationMode`，且函数体内不出现 `status` / `reasonCode`（ack 被丢弃）；
- `switchMode` 无 `optimistic` / 本地 config 更新；
- `sendText` 两条路径均带 `putSendSubmission()`，其中写 `mode` 与 `planEnabled`；
- `ChatScreen` 从 `state.config` 读 mode；
- `modeOptions` 基集不含 plan；
- Rust `OPS`、`dispatch`、`CAS_COMMANDS` 均含 `switchCollaborationMode`；
- Web schema 允许 plan，Web 模式清单含 plan；
- Flutter 支持草稿态 `onDraftChange('mode')`，且 `_apply` 检查 ack `status`；
- Android 无 yolo 二次确认。

结果：**18/18 PASS**。

### 6.2 Rust 单测（实际运行）

```
cargo test -p zemote-protocol --lib --offline ops_list_covers_every_dart_method
  1 passed

cargo test -p zemote-protocol --lib --offline cas_command
  3 passed (non_cas_command_omits_base_revision / accepted_ack_bumps_revision_floor /
            cas_command_uses_subscription_revision)
```

说明：op 白名单与 CAS 信封机制有测试覆盖；**没有针对 `switchCollaborationMode` 分发成功/拒绝语义的专门测试**（Android 侧也没有任何 `switchMode` 测试）。

### 6.3 真机验证（实际执行）

设备/包：`3B6F5RE8GCL3LYY7` / `app.zemote.debug` 0.1.0-local-debug（21:24:56 安装）。

步骤与观察：

1. UI dump 定位模式按钮：`desc=模式：变更前确认`。
2. 点开菜单并 dump：仅 `变更前确认 / 自主模式 / 完全访问`，无 `计划模式`。
3. **点当前模式（build→build）**，避免改动用户会话权限；观察日志：
   - `#15 → conversation_call op=switchCollaborationMode ... {mode:str5, sessionId:str41}`
   - `[v4] command switchCollaborationMode`
   - 30 s 后 `#15 ← failed kind=timeout`，UI 出现「操作超时：zcode-agent.sendConversationCommandV4 timed out」。
4. 同一会话 `#14 subscribe` 也 60 s 超时；因此本次超时是会话级失联，不是模式参数被拒。
5. 模式按钮文案在失败后仍为「变更前确认」，无变化。

**限制**：为避免改动用户正在运行的真实会话权限，没有执行 build→guarded/yolo 的实际切换；也没有在新会话页面点模式（需要切换工作区并可能触发桌面端 prepare，风险高于收益）。因此“健康会话下桌面端是否接受并回填”标注为 **未实测**。

### 6.4 未实测项

- 桌面端对 `switchCollaborationMode {mode:"guarded"/"yolo"/"plan"}` 的实际接受与 config 回推；
- 桌面端对 `sendText.mode` 与会话级 mode 的合并优先级（是否存在旧 mode 覆盖新 mode）；
- 模式切换在 turn 运行中是否被桌面端拒绝；
- 新会话草稿态（代码已明确 early return，静态结论已足够）。

---

## 7. 复现步骤

### 7.1 已有会话（本次真机路径）

1. 安装 debug 包并连上桌面端，打开任一**已有**对话。
2. 看输入框底部左侧工具条：`+` 右边是一个 `<>` 图标按钮。
3. 点它：弹出「变更前确认 / 自主模式 / 完全访问」。
4. 选任意一项：
   - 界面立即关闭菜单，**没有任何 loading / 成功提示**；
   - `logcat | grep switchCollaborationMode` 能看到 `conversation_call` 和 `[v4] command`；
   - 若该会话订阅已失联（本次即如此），30 s 后顶部出现「操作超时：zcode-agent.sendConversationCommandV4 timed out」；
   - 点错误条「重试」只会 `refresh()` 重连，不会重发模式切换。
5. 观察改为：如果桌面端拒绝（`status != accepted`），界面**不会有任何错误**，模式也不会变。

### 7.2 新会话（静态可判定）

1. 首页 → 某工作区「新建对话」→ `taskId=null` 的聊天页。
2. 点模式按钮并选「自主模式」。
3. 预期：无任何命令、无提示、按钮仍显示 build 默认态（`ChatViewModel.kt:1636` 直接 return）。
4. 发送首条消息：`createSession` 只带 `workspaceId/timeoutSecs`（838-841），不带模式；`sendText` 的 `putSendSubmission()` 用 `config`（此时可能为 null）→ 实际发送 `mode=build`。

---

## 8. 风险

| 级别 | 风险 | 依据 |
| --- | --- | --- |
| P0 | 新会话无法选模式，用户以为选了、实际首条消息仍按 build 发送 | `ChatViewModel.kt:1636`、838-841；无 `draftMode` 状态 |
| P0 | 切换被桌面端拒绝时完全静默，用户无法区分“已生效/被拒绝/没发出” | `switchMode` 丢弃 ack（1640-1644）；Flutter `_apply` 有检查 |
| P1 | 30 s 超时前无任何反馈；失败后错误条「重试」不重发该动作 | `ChatScreen.kt:246`、`ChatViewModel.kt:426-445` |
| P1 | config 回填存在竞态：切换后立刻发消息可能带旧 mode，可能覆盖桌面端新模式 | `putSendSubmission` 读 `config`；无乐观更新 |
| P1 | Plan 模式不可达，且代码注释与 Web 3.17.1 事实不符 | `ChatComposer.kt:546-554`；Web `R` 含 plan / 05 审计 |
| P1 | 会话已失联时模式按钮仍可点，命令必然 30 s 超时，错误还会覆盖“订阅失败”提示 | `subscribeConversation` 失败只设 `SUBSCRIBE_FAILED_MESSAGE`；`switchMode` 无条件覆盖 `error` |
| P2 | yolo 一触即发，无 Web 的二次确认/风险勾选 | Web `index-Dwhx77cK.js:1872525`；Android `ChatComposer.kt:309-311` |
| P2 | 未知 mode（`default`/`auto`）被当作可选项下发，可能违反 Web schema | `ChatComposer.kt:547-554`；Web schema 枚举 |
| P3 | 图标/文案与 Web 不一致，`<>` 容易被误认为代码/追踪按钮 | `ChatComposer.kt:566-572`；真机截图 |
| P3 | 触发器无可见模式名，用户无法一眼确认当前模式 | `ChatComposer.kt:270-288` |

---

## 9. 修复建议（具体到文件/函数）

### P0-1 草稿态模式（新会话）

- `ChatViewModel.kt`：在 `ChatUiState` 增加 `draftMode: String?`（约 194-251 的 state 字段区）。
- `ChatViewModel.kt::switchMode`（1635）：当 `selectedTaskId == null` 时写 `draftMode = mode` 并返回（不要静默 return），同时清理 `error`。
- `ChatScreen.kt:292`：改为 `mode = state.config?.get("mode").asString() ?: state.draftMode`。
- `ChatViewModel.kt::createConversation`（798）/ `createSession` 参数（838-841）：仿 Flutter `_buildDraftConfig()`，在 `createSession` 的 args 里增加
  `put("config", buildJsonObject { draftMode?.let { put("mode", it) } })`。
- 发送首条消息时 `putSendSubmission()`（1058）优先用 `draftMode`，避免 config 尚未返回时丢模式。

### P0-2 ack 语义与拒绝提示

- `ChatViewModel.kt::switchMode`（1635）：把 `chatOps.call` 的返回值接住：
  ```kotlin
  val ack = chatOps.call("switchCollaborationMode", args, scope)
  val status = (ack as? JsonObject)?.get("status").asString()
  if (status !in setOf("accepted", "noop", "duplicate")) {
      // reasonCode/message 写入 error，并保留旧模式
  }
  ```
  与 Flutter `_apply`（`chat_page.dart:3998-4019`）对齐。
- 同函数：成功后再 `refreshConversationState()`，失败不要刷新（避免用旧快照覆盖）。

### P0-3 乐观回填

- 成功 ack 后调用 Rust 已支持的 `optimisticPatch` op（`conversation_transport.rs:1708-1720`）：
  `chatOps.call("optimisticPatch", {sessionId, patch:{config:{...当前 config, mode}}})`。
  这样 `refreshConversationState`/UI 立即看到新模式，消除 §4.1 的“无反馈”和 §8 的竞态。
- 参考 Flutter `state.optimisticPatch({'config': {...state.config, 'mode': v.value}})`。

### P1-1 Plan 入口

- `ChatComposer.kt::modeOptions`（547）：把 `plan` 加入基集（或按 Web 结构在菜单首项渲染 plan 复选框）。`switchCollaborationMode` 的 Web schema 明确允许 `plan`（`parts-Ba2kLaRj.js:39023`），Rust 也不做枚举校验，可直接下发。
- 同步修正 `ChatComposer.kt:546` 的注释（Android 没有“独立开关”）。

### P1-2 未知值归一化

- `ChatComposer.kt:547-554`：对 `current` 先做 `when (current) { "build","edit","plan","guarded","yolo" -> current; else -> "build" }`，不要再把 `default`/`auto` 追加为可选项；或至少把它们映射成 build，避免下发 schema 非法值。

### P1-3 错误重试语义

- `ChatScreen.kt:246`：`StatusNotice` 的 `onRetry` 不要固定 `onRefresh`。给 `ChatUiState` 增加 `lastFailedAction`（或 `retry: (() -> Unit)?`），
  `switchMode` 失败时登记 `{ retry = { switchMode(mode) } }`，错误条优先执行该闭包；无动作时才回退到 `refresh()`。
- 失败文案明确写出“模式未切换”。

### P1-4 会话健康门禁

- `ChatScreen.kt` / `ChatComposer.kt`：当 `state.lastSubscribeFailedSessionId == state.selectedTaskId` 或订阅在飞/失败时，禁用 ModeButton 并给 contentDescription「对话未连接，模式不可切换」；或点击时直接提示，不要发必然超时的命令。
- `ChatViewModel.kt::switchMode`：发送前检查该会话是否已订阅/健康，失败时给可行动文案，而不是等 30 s。

### P2 视觉/安全对齐

- `ChatComposer.kt:270-288`：触发器显示短名称（如「变更前确认」）或至少加 chevron；图标按 Web 语义改为 hand / shield-check / shield-alert（`docs/audit/05-composer.md:168`）。
- `ChatComposer.kt:557-564`：`edit` 改为「自动编辑」；补 `default`/`auto` 的真实文案或归一化。
- `ChatComposer.kt:309-311`：选择 `yolo` 前弹确认（可复用 `AlertDialog`，文案对齐 Intl `mode.yoloConfirmation.*`）；若产品决定与 Flutter 保持一致而不加，需在文档中明确这是有意偏离 Web。

---

## 10. 证据索引（file:line / 偏移）

### Android

- `android/app/src/main/kotlin/app/zemote/ui/chat/ChatComposer.kt:81, 104, 190, 270-317, 546-572`
- `android/app/src/main/kotlin/app/zemote/ui/chat/ChatScreen.kt:246, 281-315, 292, 314, 501-540`
- `android/app/src/main/kotlin/app/zemote/ui/nav/AppNavHost.kt:412, 420`
- `android/app/src/main/kotlin/app/zemote/ui/chat/ChatViewModel.kt:426-445, 501-544, 527, 798-886, 838-841, 858, 872, 1058-1086, 1635-1653, 1750-1813, 1915-1925, 1964-1974, 2185, 2202`
- `android/app/src/main/kotlin/app/zemote/ui/chat/ChatOps.kt:119-126, 130-171`
- `android/app/src/main/kotlin/app/zemote/ui/EnginePort.kt:561-592`
- `android/app/src/main/kotlin/app/zemote/engine/ZemoteEngine.kt:426-431`

### Rust

- `rust/crates/protocol/src/conversation_transport.rs:961, 1335-1363, 1402-1413, 1491-1503, 1708-1720`
- `rust/crates/protocol/src/conversation.rs:107-123, 240-272, 456-459`
- `rust/crates/ffi/src/conversation.rs:377-412`

### Reference Web 3.17.1（字节偏移，压缩单行 JS）

- `assets/src-BSClmRM9.js` 38278（模式 `R` 列表）、38627（允许集合 `ai`）、38666（归一化 `oi`）
- `assets/parts-Ba2kLaRj.js` 35981（`sendText` schema）、39023（`switchCollaborationMode` mode 枚举）
- `assets/index-Dwhx77cK.js` 1872525（yolo 确认函数）、6509199（yolo 确认对话框）
- `assets/IntlProvider-CneXitqp.js` 321457-321546（mode 文案）
- 既有审计：`docs/audit/05-composer.md:168-182`（Plan 首项复选框、图标、文案、Plan 入口缺失 P1）

### Reference Flutter

- `Reference_code/lib/ui/chat_page.dart:255, 839-857, 686-693, 3742-3744, 3884-3940, 3908-3914, 3925, 3998-4019`
- `Reference_code/lib/protocol/conversation.dart:407-409, 1555-1557`
- `Reference_code/integration_test/full_suite_test.dart:190-194`

### 真机证据

- 设备 `3B6F5RE8GCL3LYY7` / `app.zemote.debug` / `0.1.0-local-debug`
- UI dump：`模式：变更前确认`（bounds 259,2555 → 315,2611）；菜单仅 变更前确认/自主模式/完全访问
- logcat：`#15 → conversation_call op=switchCollaborationMode ...`（21:33:57.120）
- logcat：`[v4] command switchCollaborationMode`（21:33:57.125）
- logcat：`#15 ← ... failed ms=30154 kind=timeout`（21:34:27.275）
- logcat：`#14 subscribe failed ms=60055 kind=timeout`（21:34:09.619）
- UI：`操作超时：zcode-agent.sendConversationCommandV4 timed out`

---

## 11. 附：本次审计未做的事

- 未修改 app / Rust / 既有文档；仅新增本文件。
- 未在真机上执行 build→guarded/yolo 的真实权限切换（避免影响用户正在运行的会话）。
- 未验证桌面端对模式参数、sendText 覆盖语义的实际实现；相关结论标注为“未实测/风险”。
- 行号可能因工作区并发编辑漂移；以函数名 + 代码片段为准。
