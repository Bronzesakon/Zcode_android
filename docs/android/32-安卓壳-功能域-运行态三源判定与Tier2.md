# 运行态判定（三源）+ 原生承载（Tier2） —— 死代码候选的功能影响分析

> 核验基线：2026-09-19 当前工作树（b627610 之后）。
>
> 本域回答一个用户每天会问的问题：**「我这个任务现在到底还在跑吗？」**
> 这个答案决定通知卡上那个「运行中」芯片（README「任务通知」：状态词只有 `运行中`/`等待确认` 两个）、决定任务完成后卡片什么时候改成「已完成」、决定流体云（ColorOS 上的实时更新卡片）会不会被系统提前收回。
> 本域三条候选里，**有两条其实是同一件事的两面**：一条是"哪部分代码白留了"（D3），一条是"文档说错了、照它做会误删活功能"（D4）。

---

## A. 本域功能概览（给非工程师看）

**要解决的问题**：任务是在桌面端那边跑的，手机壳只能**听**桌面端说话。壳必须知道"此刻哪些任务在跑"，否则：

- 通知卡不会出现（壳只有在看到"有任务在跑"时才建卡）；
- 已经出现的卡会**冻在上一句话**（没人告诉壳任务已经结束，或已经重新开始）；
- 任务结束后卡片不会改成「已完成」；
- 后台时更严重：网页被系统冻结，壳连"听"都听不到了，卡片直接停更。

**用户什么时候感知到它**：把 ZCode 退到后台、去干别的事。这时手机上会挂着几张卡片（在 ColorOS 上提升成流体云）。用户会在锁屏/通知栏/状态栏芯片上瞄一眼，判断"我的任务跑完没有"。**这个判断的唯一依据就是运行态。** 运行态判错，卡片就会长期显示"运行中"（用户以为还在跑，其实早停了），或者反过来把还在跑的任务显示成已完成。

**为什么有三条"消息来源"**——因为三条各有各的盲区，缺一条就有场景看不见：

| 源 | 一句话 | 它的盲区 |
|---|---|---|
| ① **controller 流**（页面自己订的 `controller/tasks-index`） | 网页自己本来就订阅了"全局任务清单"，上面写着每个任务的即时状态（`liveStatus`）。壳现在**搭个便车**：网页收到这份清单的同时，顺手转发一份给壳 | 这条流**骑在网页自己的连接上**。后台原生承载一接管，网页就被顶掉，这条流随之死掉 |
| ② **sessions-index**（持久相位） | 桌面端推来的"有哪些任务、什么状态、什么标题"清单 | **按工作区订阅**——网页停在 A 工作区，壳就只看得到 A。用户在 B 工作区起任务，壳完全不知道（真机现场：网页 41 个任务，壳只看到 2 个） |
| ③ **会话流**（`turnHeader.state`） | 承载期间原生自己订阅的那几条对话，每帧都带"这轮在跑还是停了" | **只在后台承载里**，且**只覆盖订阅的那几条**会话 |

（2026-09-19 核实：三源结构不变；原"原生自己去订 controller 流"的第四条腿已随 D3 整体删除，见 D3 条目。）

**判定规则**：三条源每条报到时都盖一个"报到时刻"，最终谁的时刻最新，就听谁的（`TaskStore.phaseOverlay`，"谁后到谁算数"，`TaskStore.kt:643-648`）。这条规则是 2026-09-18 真机现场逼出来的：网页被暂停后，"全局清单"和"会话流"都停在旧的 `running`，而桌面端数据库已经写了 `completedSuccess`——固定优先级会让旧的 `running` 盖住新到的"结束"，卡片就永远显示"运行中"。改成按时刻判定之后才对。（2026-09-19 核实仍然成立）

```
网页自己的 socket ──①controller 流（页面本来就收，壳顺手转发）──┐
桌面端 sessions-index ──②持久清单（按工作区）──────────────────┼─→ 三条源各盖时刻
原生承载的会话流 ──③turnHeader.state（后台才有）──────────────┘        ↓
                                                    谁后到谁算数 → 卡片上的「运行中/已完成」
```

**原生承载（Tier2）是什么**：正常情况壳只是"站在旁边听"。但如果用户把 App 退到后台，系统会冻结网页（Chromium 的网络与定时器都停），网页连不上桌面端了——卡片就停更。这时**原生自己另开一条连接顶上**（原生 WebSocket 直连 relay），把网页那条顶掉，卡片继续更新。这就是「后台原生承载」。它已经在真机上端到端验收通过（README「实现要点」16、`docs/18` §3.10 的 162 轮提前接管验收）。（2026-09-19 核实仍然成立）

---

## B. 本域架构小结

**本体（保住功能必须留的）**：

| 块 | 职责 | 位置（2026-09-19 核实） |
|---|---|---|
| **帧解析状态机 `ControllerTasksState`** | 把"任务清单"帧（快照/增量/缺口）解成一份任务表 | `core/ControllerTasksState.kt`，带 12 条单测 |
| **三源合并（`TaskStore`）** | 存三份相位+时刻表，按"谁后到谁算数"算出有效相位 | `TaskStore.applyLiveTasks`（`:565`）/ `applyConversationRunState`（`:663`）/ `phaseOverlay`（`:644`） |
| **第三条源的实际入口（页面那条路）** | 网页自己的 controller 帧 → 注入层转发 → 原生 → 合并 | `zcode-protocol.js:1545-1546`（识别）→ `:1155-1160`（`_forwardControllerTasks`）→ `inject.js:819-821` → `ShellRuntime:1950-1959` → `:2038-2054` → `TaskStore.applyLiveTasks:565` |
| **原生承载本体** | 配对、开桥、订阅对话、心跳、覆盖刷新、交还 | `core/Tier2Probe.kt`、`core/RelayBridge.kt` 的 `BridgeSession`（`:113`）/`BridgeManager`（`:879`） |
| **会话流出口** | 承载里的会话流运行态出口 | `Tier2Probe.turnStateSink`（`:212`，挂载 `:503-508`）→ `BridgeManager.turnStateSink` |

**脚手架（留着不是为了让它跑，是为了"别再试一遍"）**：

| 块 | 为什么现在不跑 |
|---|---|
| ~~`RelayBridge` 里**原生自己去订阅** controller 流的那一套~~ | **⛔ 已于 2026-09-18 整体删除（第 4 批 4a 子批，b627610；`docs/18` §4 第 759 行留有完整取证）**。当年封死的原因：那次订阅会让**桌面端 host 进程当场崩掉**（`rpc:listen … onDynamicControllerFrame FAIL` → `uncaughtException` → host 退出 code 1），配对到崩的间隔固定 4.3 秒、三次复现。现在**没有代码可打开**了——重开该能力需要重新实现订阅并先复验桌面端（还要注意 `docs/18` §3.7 的更正：channel 当年可能就打错了）。 |
| ~~`RelayBridge` 里 controller 订阅方的**下游接线**（`liveTaskSink` 承载侧消费者）~~ | **同轮已删**（`BridgeSession.onLiveTasks` + `BridgeManager.liveTaskSink` + `Tier2Probe.liveTaskSink` + `ShellRuntime` 的空操作交还臂 + `RelayBridgeTest` 的 6 处死脚本臂）。 |

**读者只要记住这一句**：**网页那条 controller 流（第三条源）是活的、真机验过的、有单测钉着的；原生自己去订阅同一条流的那套代码已经不存在了（2026-09-18 删除）——它当年是"会把对面打崩"的死路，取证留在 `docs/18` §4。** 这两件事在 `docs/18` §4 里曾被写成同一件事，D4 纠正过、现已被文档接受。

---

## C. 逐条分析

### D3. 运行态判定（三源）—— RelayBridge 里的"原生订阅 controller 流"这一套接线  [`D 级·需决策`]

> **⛔ 已于 2026-09-18 清理（第 4 批 4a 子批，b627610）**：整块删除——`RelayBridge.kt` 的
> `subscribeControllerTasks` / `onControllerWire` / `resyncController` / `unsubscribeController`、
> 5 个私有字段、开关 `controllerStreamEnabled`（删除前在 `:364`）、`BridgeSession.onLiveTasks`（`:124`）、
> `BridgeManager.liveTaskSink`；`Tier2Probe.liveTaskSink`（删除前 `:199`，挂载 `:502-508`）与它在
> `BridgeManager(...)` 的命名实参；`ShellRuntime` 的交还臂消费者（删除前 `:425-437`、`:471`）；
> `RelayBridgeTest.kt` 里 6 处永不触发的 controller 脚本臂（原 `:200-205`/`:213-224`/`:335-339`/
> `:346-357` 等，含 `controllerSnapshotWire`）。**协议名册按建议保留**：`RelayWire.kt:76-80` 的
> `TOPIC_CONTROLLER_TASKS` / `EVENT_CONTROLLER_FRAME` / `METHOD_SUBSCRIBE/UNSUBSCRIBE/RESYNC_CONTROLLER`
> 五个常量仍在。崩溃取证完整转存 `docs/18` §4（`:759`）。下文为删除前的原始分析（行号是删除前快照），
> 留作档案——它记录的"为什么这条流不能碰"仍然有效。

- **功能归属**：**运行态判定（三源）**（主体），并牵连 **原生承载（Tier2）**（它是承载桥握手流程的最后一步）。
- **这个功能是干什么的**：运行态决定"卡片上写不写运行中、任务结束后卡片改不改状态"。D3 涉及的这条来源是"**原生自己在后台去订阅全局任务清单**"。
- **这条代码的角色**（删除前）：它是**承载桥握手流程的最后一步**（开桥 → 订对话 → 订索引 → 订 controller 流），以及这一步的"到货处理"。**入口的第一个判断就是"总开关已关，直接返回"**，所以这条链一次都不会走。
- **功能现状**：~~`已被政策封死`~~ **已删除**。删除前的证据链（存档）：`controllerStreamEnabled = false`
  全仓无第二处赋值、`private val` 无调试命令可开；唯一调用点 `BridgeSession.runHandshake` 末尾
  `:241 subscribeControllerTasks()` → `:371-377` 打一行日志即 `return`，其后 `:378-486` 全部不可达；
  下游 `onLiveTasks`（`:124`）唯一触发点 `:422` 在不可达区里；`RelayBridgeTest` 备好的 controller 应答
  永不执行、假任务 id `s-live`/`s-ctl` 无任何断言引用（"连假装验证都没做到"）；对 controller 流的
  5 个 `RelayWire` 常量引用全部落在不可达区间。反面证据（**活**的那条）：`ControllerTasksState` 在生产里
  的真实调用方是页面那条路（`ShellRuntime` 的 `pageControllerTasks`，与 D3 无关）——**这条路至今仍在**
  （见 D4）。
- **删了会怎样**：当时判断"用户无感知、行为与现在完全一样"——**删除后经本机与文档面复核未见回归**
  （真机回归记录见 `docs/18` §4 修订说明）。
- **建议**：~~先调查再定 → 倾向"只加注释标明"~~ **实际执行**：第 4 批整体删除（含测试死脚本），
  取证转存 `docs/18` §4。当时最担心的两件事都有交代：①"别再试"的路标由 `docs/18` §4 第 759 行
  （完整崩溃四行日志 + 4.3s + 三次复现 + "channel 可能打错了"的 §3.7 更正）承担；② `RelayWire`
  协议名册五个常量保留。
- **验证方式**（删除时适用过的提醒，留档）：删这类恒不执行的代码，**"测试全绿"不能作为安全的证据**
  ——那些分支本来就不触发。最终以"Kotlin 编译 + 21 项 `RelayWireTest`/5 项 `RelayBridgeTest` 全绿 +
  真机交接表（★接管配对成功 → bridge ready → 活订阅 → 活进展）"收口。
- **关联**：原文提到"它不是那个'机器还在跑但什么都不做'的看门狗（那是 D1）"——**D1 的卡死看门狗
  决策机也已删除**（`ShellRuntime.kt:1964` 注释："旧值 arm / cancel 已随看门狗决策机器删除，别再按
  它们检索"）；现存 `ShellRuntime.kt:1716` 的"Tier1 静默看门狗"（5s 一跳）是**另一台、活的**，别混。

---

### D4. 运行态判定（三源）—— `docs/18` §4 的过期结论「`livePhases` 永不写入、恒空」  [`D 级·文档纠错·危险`]

- **功能归属**：**运行态判定（三源）**。这条不是代码，是**一份写错了的文档**，而它错在一句会让人删掉活功能的话上。
- **这个功能是干什么的**：controller 这一档运行态数据由**网页自己收到的那份全局任务清单**喂进来，天天在写、天天在用。
- **这条代码（这句话）的角色**：它原是 `docs/18` §4 表里给"下一轮审计的删除授权书"的一句话。
- **功能现状**：**文档结论已于 2026-09-18 被改正（`docs/18` §4 第 760 行现明写"更正：不是恒空，它一直在写入"、并列明"别删 `applyLiveTasks` 或这几张覆盖层表"）；被它描述的那部分代码是`活`的，且有真机取证。**（2026-09-19 核实：文档修正已落地，代码链路完整。）完整证据链（2026-09-19 核实行号）：
  1. 页面本来就订阅了 `controller/tasks-index`（`zcode-protocol.js:89` `CONTROLLER_TASKS_TOPIC`），并把它转给原生：`zcode-protocol.js:1265 acceptObservedPayload` → `:1506 _observeInboundRpc` → `:1545-1546`（识别 `data.topic === CONTROLLER_TASKS_TOPIC`，`:1538` 注释写明"第三条源（2026-09-18）：**页面自己订的**"）→ `:1155-1160 _forwardControllerTasks` → `onControllerTasks({kind:'complete', frame:data})`（"纯被动，零写入"）→ `inject.js:819-821` `next.onControllerTasks = function (wire) { post('controllertasks', wire); }`。
  2. 原生接收：`ShellRuntime.kt:1950` `"controllertasks" ->` → `:1959 onPageControllerFrame(data)` → `:2038-2054`：`pageControllerTasks.applyWire(wire)`（状态机 `:2034`）→ `:2054 applyUpdate(store.applyLiveTasks(tasks))`。
  3. **`livePhases` 就在这里被写**：`TaskStore.kt:565-576` —— `applyLiveTasks` 里 `livePhases.clear()` 之后 `livePhases[key] = task.phase`（`:574`）、`liveTitles[key] = task.title`（`:575`）、`livePhasesAt[key] = nowMs`（`:576`）。
  4. **而且被真读**：`TaskStore.kt:615`（`livePhases.isEmpty()` 参与早退判断）、`:625`（拿 `livePhases.keys` 补出"SI 里还没有"的任务条目）、`:630`（用 `liveTitles` 填标题）、`:644-648`（`phaseOverlay` 读 `livePhases[id]` 与 `livePhasesAt[id]` 决定这一档的相位与时刻）、`:782/:785/:786`（工作区移除时清理）。
  5. **真机取证 + 单测钉子（不是推理）**：`docs/18` §3.11 B 与 J 记录了这条路的现场日志——`页面运行态：89 个任务 · 在跑 1 个（…default）` → `提升集合变化: [#394010]`（该日志行现于 `ShellRuntime.kt:2050`）；README「实现要点」22 把它列为三条源之一。**单测护栏仍在**：`node tools/inject.test.js` → **47 项全绿**（2026-09-19 实跑），其中 `✔ the page controller stream is forwarded to native as a passive frame`（`tools/inject.test.js:791`）就是这条路的护栏。
  6. ~~确实被"封死"的只有 `RelayBridge.controllerStreamEnabled = false` 那一套原生订阅~~ ——**该套已于 2026-09-18 整体删除**（见 D3），两者"同一状态机类 `ControllerTasksState`、入口完全不同"的关系不变：D3 走原生 socket（已不存在），第三条源走网页 socket（活的）。
  7. **`README` 要点 16 与 `ShellRuntime.kt:683` 的过期兜底描述已改正（2026-09-18/19）**：README 现：467 明写"运行态**不靠'优先级'**——**两条现存源各记报到时刻、谁后到谁算数**"，要点 22（`:551`）"三源、谁后到谁算数"与之不再矛盾；`ShellRuntime` 同类注释已移至 `:996-1001`，明写三源与"谁后到谁算数"。原文档建议的第 4 处修改**已执行完毕**。

- **删了会怎样**（不变，仍是最重要的警告）：如果把"controller 那一档"当死代码删掉，三件事同时发生：
  1. **"在别的工作区里跑的任务"手机上看不到了**（第一条源是唯一**全局**的源）；
  2. **卡片正文会退回"只剩工作区名"**（承载覆盖目标靠"哪些工作区有在跑任务"来定）；
  3. **前台就彻底没有运行态了**。
  代码视角：删 `livePhases`/`liveTitles`/`livePhasesAt` 这一档会连带 `applyLiveTasks` 写入段与
  `phaseOverlay` 一条分支，`ControllerTasksStateTest` 至少 3 处会红——**测试会拦住这次误删**。
- **建议**：~~只加注释标明（= 改正文档），并明确写"不可删"~~ **已执行（2026-09-18）**：
  `docs/18` §4 第 760 行已改为事实版并写明"**别删**"；第 1 行（`:759`）已拆开（订阅链=已删除留取证、
  状态机+applyLiveTasks=活）；README/`ShellRuntime` 的过期兜底描述已同步更正。
- **验证方式**：本机自动可完全验证——`grep -n "applyLiveTasks" app/src/main/java/com/zcode/remote/ShellRuntime.kt`
  命中 `:2054`（生产调用）、`TaskStore.kt:574-576`（写入）；`node tools/inject.test.js` 47 项全绿
  （2026-09-19 实跑）。真机对照：退后台后看 `页面运行态：N 个任务 · 在跑 M 个（工作区…）`
  （`ShellRuntime.kt:2050`）与 `提升集合变化: […]`。

---

### C5. 原生承载（Tier2）—— `Phase.CONNECTING` 这个"连接中"状态  [`C 级·建议只标注`]

- **功能归属**：**原生承载（Tier2）**。
- **这个功能是干什么的**：原生承载建连阶段的状态标签：`IDLE`→`CONNECTING`（拨号）→`AUTHENTICATING`
  （认证）→`PAIRED`（配对）→`CLOSED`。用户会间接感知到它：`isRunning()`（"承载是否在跑"）被前台/后台
  切换、接管触发、交还判据等多处生产代码拿去当开关用（2026-09-19 复核：现 **10 处**——
  `ShellRuntime.kt:525,676,1087,1096,1149,1249,1352,1632,1732,1982`），还出现在诊断指令 `stall_state`
  的 `Tier2在跑=` 那一栏（`ShellRuntime.kt:1627-1632`；原 README:1015 的 `Tier2在跑` 表述已随交接表
  改版消失）。
- **这条代码的角色**：**记账/标签**，不是功能本体。它是那个 5 状态机里的一个取值，被写在
  `connectNow()`（`Tier2Probe.kt:353`）里，然后没有任何分支去区分它。
- **功能现状**：`活`（外层功能活着、有真机验收），但**这个取值本身是只写不读的记账**。
  1. `Phase.CONNECTING` 全仓只有 1 处赋值（`Tier2Probe.kt:353`；枚举声明 `:63`），没有任何
     `== Phase.CONNECTING` 的比较。
  2. 判定函数 `isRunning()`（`Tier2Probe.kt:191`）写的是 `phase != Phase.IDLE && phase != Phase.CLOSED`
     —— "连接中"与"认证中"被它一视同仁地算作"在跑"。
  3. 这个值唯一"露面"的地方是 `:297` 那条重复启动警告里的 `phase=$phase` 文本——**纯诊断字符串**。
  4. 外层功能确实活：承载本身有真机验收（README P0/P8、`docs/18` §3.10），`Tier2Probe` 在生产侧
     有大量真实调用点。
- **删了会怎样**：**无感知**（没人按它分支）。`Tier2Probe` 没有直接单测（`Tier2ProofTest` 仅 2 条且与
  `Phase` 无关），不会有测试变红。唯一实际损失：诊断日志里少一个中间状态词。
- **建议**：~~只加注释标明~~ **已执行（2026-09-18）**：枚举上方的 KDoc（`Tier2Probe.kt:55-63`）现明写
  "`Phase.CONNECTING`……是**真实状态，不是漏删**：今天没有任何分支区分它与 AUTHENTICATING……保留是
  为了与页面侧那台状态机逐步镜像——少一格，将来要按阶段分支时就得回头补。"（2026-09-19 核实。）
- **验证方式**：本机自动（改注释，跑 Kotlin 单测全绿即可）；无需真机。

---

## D. 本域小结表

| 编号 | 功能名 | 功能现状 | 建议动作 | 风险 | 验证方式 |
|---|---|---|---|---|---|
| **D3** | 运行态判定（三源）—— RelayBridge 的 controller 订阅方 `[D级]` | **⛔ 已删除**（2026-09-18 第 4 批 4a，b627610；取证转存 `docs/18` §4:759；`RelayWire` 五常量保留） | ~~倾向只加注释~~ → 已整块删（含 `Tier2Probe.liveTaskSink` 下游与 6 处死测试脚本） | — | 删除时"测试全绿≠安全"的提醒已留档；收口看真机交接表 |
| **D4** | 运行态判定（三源）—— 「`livePhases` 永不写入」`[D级·文档]` | **代码：`活`**（`TaskStore.kt:565-576` 写、`:615-648` 读；inject.test 47 项全绿）；**文档结论已改正**（`docs/18` §4:760"不是恒空"） | ~~改正文档并写"不可删"~~ → **已执行**（`docs/18` §4 两行 + README 要点16 + ShellRuntime 注释全部改妥） | 低（已改后） | 本机自动可完全验证（grep + 单测）；真机对照可选 |
| **C5** | 原生承载（Tier2）—— `Phase.CONNECTING` `[C级]` | `活`；此取值**只写不读**；**标注已加**（`Tier2Probe.kt:55-63` KDoc） | ~~只加注释标明~~ → **已执行** | 低 | 本机自动（Kotlin 单测）；若要真删才需真机看 `stall_state` |

---

## E. 与既有结论的关系

**1. 修正了 `docs/18` §4 的一条结论（本域最重要的一条）——已闭环**

§4 第 760 行现明写"**更正（2026-09-18）：不是恒空，它一直在写入**"，并给出与本文档 D4 相同的证据链
（行号按删除前快照：`inject.js:823` → `ShellRuntime.kt:1529/1538` → `:1631` → `TaskStore.kt:249-251/
281/289/294/309-311`，现行为 `inject.js:820` → `:1950/1959` → `:2054` → `TaskStore.kt:574-576/
615/625/630/644-648`）。第 759 行（原第 1 行）已拆成"订阅链=删除留取证 / 状态机+applyLiveTasks=活"，
**D4 建议的全部四处修改均已落地**（2026-09-19 逐条核实）。

**2. §4 对 D3 段落的保留理由（"单测依据"）——已被接受并随删除作废**

本文档当时论证：那 4 段 controller 测试脚本"看起来在测、其实连断言都没有"，"单测依据"不成立；
真正要留作协议名册的是 `RelayWire` 的五个常量。**第 4 批执行结果与论证一致**：6 处脚本臂删除、
五个常量保留（`RelayWire.kt:76-80`）、"别再试"取证转存 `docs/18` §4。

**3. README「实现要点」16 的过期描述——已修正**

README 现：467"运行态不靠'优先级'——两条现存源各记报到时刻、谁后到谁算数"与要点 22 不再矛盾；
`ShellRuntime` 的同类注释（现 `:996-1001`）同口径。原文档第 3 条建议**已执行完毕**。

**4. 未推翻/未涉及（2026-09-19 复核仍成立）**
- README「实现要点」22（三源不可合并、谁后到谁算数）与 `docs/18` §3.11 B/D 均与本域分析一致。
- README P0/P8 与 `docs/18` §3.10 支持"原生承载本体是活的"，未推翻。
- 本域未发现 D3 之外的"原生自己去订阅 controller 流"的残留入口（2026-09-19 全量 grep
  `controllerStreamEnabled|subscribeControllerTasks|onLiveTasks|liveTaskSink` 于 `app/src` 零命中）。

**5. 原文"存疑"条目的结局**
- 原文担心"若整块删，后台场景的运行态是否只剩会话流一条腿、质量是否下降，本机无法验证"。**实际
  删除未动第三条源的任何代码**（删的是原生侧永不可达的订阅机器与空挂载），后台运行态仍由
  ②sessions-index + ③会话流 + 页面流转发的残余缓存供给，未观察到回归。该存疑随删除关闭。

---

## F. 补遗（2026-09-19 新增）：防降级 stale 标记帧规则（4c，2026-09-18 落地）

原文档撰写时它还是"工程前置"提案，**现已完整落进源码**，且是"三源判定"的重要护栏，必须记入本域：

- **触发（JS 侧）**：sessions-index 状态机检出**序号缺口**（`zcode-protocol.js:846-853`）时，`seq`
  有意不动、后续每帧增量都判失败，同时置 `stale = true`、记 `gapFromSeq`；**从缺口检出那一拍起**，
  状态机按**跃迁**额外发一帧"**仅标记帧**"——`RemoteClient.prototype._emitSessionsStale`
  （`zcode-protocol.js:1226-1243`），载荷 `stale: true, staleOnly: true`。它怀里那份 `sessions` 是
  **冻结的旧读数，不许落地**（若照常落地，旧 SI 会重盖时刻表、反过来压过 controller/会话流覆盖层
  ——这一帧自己就会制造一次它要防的降级）。`stale` 只由**整窗快照**解除（`:838-839`）。
- **透传（原生接收）**：`ShellRuntime.onSessions`（`:2077-2084`）把 `stale` / `staleOnly` 两个新字段
  原样传给 `TaskStore.applyWorkspace`（缺字段 = false，老载荷语义不变）。
- **记账（TaskStore）**：`staleSince`（工作区键 → 检出时刻，`TaskStore.kt:117`、`markStale:459`）记
  "陈旧的事实"；`trustDeadlineMs` + `STALE_TRUST_WINDOW_MS = 120_000L`（**约 2 分钟**，`:878`，
  锚点是**用户离开**）构成"可信窗口"（`armTrustWindow:366`）；回前台提前收掉保护（`:350-351`，
  `sweepTrustWindow:426` 负责到期记账）。
- **两条保护（只在可信窗口内生效）**：
  1. `guardStaleDowngrade`（`:509`）——卡片/运行态**不许被陈旧读数拉低**（"运行中"不许被冻结旧 SI
     改写成"已完成"之类）；
  2. `staleGateHold`（`:402`）——**提前接管闸门**的查询：可信窗口内存在陈旧工作区时，接管判定按
     "可能有工作区实际在跑"收紧。
- **单测钉子**：`tools/protocol.test.js:484` `被动：缺口把该工作区标为陈旧，别的工作区不受牵连，日志只记一行`
  等 4c 新增测试（含"缺口期间一帧都不上报读数""多出来的那条只能是仅标记帧""自愈那一帧是普通整表帧"），
  2026-09-19 实跑 38 项全绿。
- **与已删机制的衔接**：它**替代**了原 D2 死段里"缺口后靠 `needsResync` 静默丢弃"的旧路（见
  multiws.md E.2），也是删除 D2 的前提性补偿。**别把 `stale`/`staleOnly`/`staleSince`/可信窗口当
  死代码删**——它们是运行态判定在"序号缺口/页面失联"场景下的防降级护栏。
