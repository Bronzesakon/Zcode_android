# 会话与任务索引（sessions-index）+ 壳内任务/工作区清单 —— 死代码候选的功能影响分析

> 核验基线：2026-09-19 当前工作树（b627610 之后）
>
> 分析范围：`core/TaskStore.kt`。本域候选：B2 `removeWorkspace()`、B3 `hasRunningTasks`（⛔ 已随第 4 批清理删除）、C4 `Workspace.{path, identity, source}`。
> 第一阶段技术判定不再复核（见 `.dsh-audit/FINAL-REPORT.md` B2/B3/C4）；本文只回答"这属于哪个功能、那功能还要不要、删了会怎样"。
>
> **本次取证方式（便于复核）**：全仓 `grep` 三次（`removeWorkspace|hasRunningTasks`、`\bsource\b`、
> `buildUpdate(|applyWorkspace(`）+ 阅读 `TaskStore.kt` 全文、`ShellRuntime.kt` 的 `onSessions`（现 `:2057-2093`）/`carrierCoverageTargets`（现 `:1179-1195`）/`onPageReloaded`（现 `:2298-2303`）、
> `SessionsIndexState.kt`、`NotifyState.kt:233`、`WebAppBridge.kt:42-63`、`RelayBridge.kt:242-251` 与 `keyOf :814-838`、
> `zcode-protocol.js` 的 `_emitSessions :1181-1200`/`_emitSessionsStale :1226-1241`/被动路径 `:1585-1625`、README、`docs/18` §3.7 / §4。
> **2026-09-19 逐断言复核并就地更新**：行号已对齐当前工作树；B3 已删除；C4 的注释建议已落实。
> **未执行任何改动，因此也未跑测试**；下文的"本机自动"指"改完之后本机就能验"，基线按 README
> （JS 97 项 + Kotlin 111 项全绿）与 `docs/18` §6 的记录。

## A. 本域功能概览（给非工程师看）

**手机壳里没有自己的任务数据库。** 任务是桌面端在跑的，手机想显示"哪个任务在跑、跑到哪了、刚完成了什么"，
只能靠桌面端推来的一份清单——这份清单在协议里叫 **sessions-index（会话与任务索引）**：
"现在有哪些任务、每个是什么状态、标题是什么、最后一段正文是什么"。

数据是这样走到手机上的：

```
桌面端 →（relay 帧：任务清单被包在 rpc-frame 里，base64 + 校验 + 分片）
      → 网页里的 zcode-protocol.js 拆包解码
      → 注入层（inject.js，被塞进网页里运行的那段脚本）转手 post('sessions', …)
      → 壳原生 ShellRuntime.onSessions
      → TaskStore（本域的主角：手机侧唯一的一份"任务记账本"）
      → 通知模块按记账本的结论出卡片
```

用户能感知到的就是最后一步：**通知栏/锁屏上的任务卡片**（在 ColorOS 上会提升成流体云）——
"运行中 · 任务名"、正文那一行最新进展、"已完成 · 任务名"、以及"撤回某张卡"。
卡片上的一切文字与去留，都由这本记账本说了算。**记账本算错，用户就直接看到错卡片**
（历史现场：卡片宣布"已完成"而用户刚点的其实是"中断"；卡片宣布"运行中"而任务早停了）。

第二个入口：**原生承载（Tier2）**。手机退到后台、网页被系统冻结时，注入层死了、清单断了，
这时壳自己开一条连接顶上。为了让两条路喂给记账本的**东西长得一模一样**，
壳里还放了一份 `core/SessionsIndexState.kt`——它是网页那份解码器的**镜像**，
连输出的字段名都逐字对齐。这就是本域"为什么有很多看起来没人读的字段"的根源。

## B. 本域架构小结

| 模块 | 职责 | 性质 |
| --- | --- | --- |
| `assets/zcode-protocol.js` 的 `SessionsIndexState` | 真正拆包/重组分片/判"丢了一帧要重同步" | 功能本体（网页侧） |
| `core/SessionsIndexState.kt` | 上者的 Kotlin 镜像，**只给 Tier2 接管期间用**（M3c 数据面）；`buildUpdate()`（`:198-224`）手工拼出与网页同形的帧 | 功能本体（原生侧） |
| `core/TaskStore.kt` | **手机侧的记账本**：把快照折进来，算出"哪个在跑 / 哪个刚结束 / 哪个要用户确认 / 哪些卡该撤" | **功能本体**（本域核心） |
| `core/NotifyState.kt` | 相位转移判定与完成观察窗（3 秒静默才算结束），被 TaskStore 调用 | 功能本体 |
| `notify/Notifier`、`notify/PromotionPolicy` 等 | 按 TaskStore 的结论渲染卡片、决定哪几张被提升（不参与相位语义判定） | 渲染层 |
| `TaskStore.Workspace` 里的 `key/title/tasks` | `key`＝通知与桥的地址；`title`＝卡片上的工作区名；`tasks`＝任务清单 | 功能本体 |
| `TaskStore.Workspace` 里的 `path/identity/source` | 帧里带着的原始字段，壳内无消费方（2026-09-19 核实仍然成立；`TaskStore.kt:19-35` 已有"要留一起留、要删一起删"的注释把口径钉死） | **脚手架/记账（wire 形状）** |
| `TaskStore.removeWorkspace()` / `reset()` | 拆卸入口：前者按单个工作区（`:777-795`），后者全清 | 记账（清理） |
| `TaskStore.runningTaskRefs()`（`:204-206`） | "现在有在跑的任务吗/是哪些"的**唯一**查询口（`hasRunningTasks` 已删，见 B3） | 记账（查询） |
| 4c 防降级簇（`staleSince`/`guardStaleDowngrade`/`staleGateHold`，详见 F 节） | 陈旧读数防护：可信窗口内在跑读数只增不减、闸门不许仅凭"没在跑"关闸 | 功能本体（2026-09-18 加，本文档原未覆盖） |

要保住这个功能，必须留的是：`zcode-protocol.js` 的解码、`TaskStore` 的折叠与判定、`NotifyState` 的相位语义，
以及**工作区键（key）**——它是通知 id 与桥的地址（见 C4 的因果链）。

## C. 逐条分析

### B2. 会话与任务索引 —— `TaskStore.removeWorkspace()`  [`B 级 / 仅测试调用`]

- **功能归属**：会话与任务索引（sessions-index）；它服务的是**任务通知与实时更新卡片**的"拆卡/清理"侧。
- **这个功能是干什么的**：桌面端推来的任务清单被折进手机侧的记账本；当**整座工作区消失**（不再订阅、桥没了），
  记账本必须把它的任务连同"曾经跑过"的历史一起忘掉，并告诉通知模块"这些卡片撤掉"。
  用户看到的现象就是：**某座工作区的常驻卡片消失，别的正常工作区的卡片不受影响**。
- **这条代码的角色**：**按单个工作区拆卸**的唯一入口（当前实现 `TaskStore.kt:777-795`）。收一个工作区键，吐出 `Update`——其中 `removedIds`
  就是它的合同内容"要撤的卡片"（`removedIds` 由 `buildUpdate` 的"上一轮运行 id 集合减去这一轮"算出，`TaskStore.kt:824`，从而卡片被撤回而不是被改状态）。
  它同时是**唯一**会做"按工作区清掉运行态覆盖层"（`livePhasesAt`/`livePhases`/`liveTitles`/`siPhasesAt` 等，
  `:782-789`，4c 后还加清 `staleSince`/`staleReadings` `:791-792`）的地方——别的清理路径要么是全清（`reset()`），要么是按任务清（`forgetStaleLivePreviews`）。
  它在数据流里的位置是**末端**：上游是"某座工作区没了"这个事实（本该来自桥的生命周期），下游是通知模块的撤卡。
- **功能现状**：所属功能`活`（sessions-index 是壳存在的理由，README「项目现状」）；
  **这条函数本身在生产侧零调用**（2026-09-19 核实仍然成立）——`grep removeWorkspace` 只命中 `NotifyStateTest.kt:492`；
  真机的生产拆卸走 `ShellRuntime.kt:2298-2303`（`onPageReloaded` → `store.reset()`，页面重载即全清）。
  它**不是被政策封死的**，而是"还没有人接线的能力"。
  **（2026-09-19 补）** 本文"保留并说明理由"的建议已被采纳：`removeWorkspace` 头上现在有一大段注释（`TaskStore.kt:756-775`）
  写明"保留，别因为生产没人调就删；它是一把还没有探测器的工具"，并且**明确否决**了本文"替代方案②把它接到桥回收上"——
  注释原话："轮换是调度事件，与任务是否完成无关……用户看到的就是卡片闪断"。将来想接线的人请先读那段。
- **删了会怎样**：
  - 用户视角：**完全无感知**（没有任何生产路径调用它）。
  - 代码视角：三处连带——① 单测 `NotifyStateTest.kt:488-496`（`removing a workspace cancels its running notifications`）
    整条作废；② `NotifyState.forget()`（`core/NotifyState.kt:233`）**只有这一个调用方**（2026-09-19 复核：仍然唯一，`TaskStore.kt:780`），会一起变成死代码
    （`reset()` 走的是 `notifyState.reset()`，不经过它）；③ 从此壳内**再也没有**"丢掉一座工作区但保住其它卡片"的办法，
    只剩 `reset()`（全清 ⇒ 其余在工作区的卡片会被撤掉再重建 ⇒ 卡片闪断、正文丢失）。
- **建议**：**保留并说明理由**（风险：低）——理由：它不是历史遗留的接线，而是**多工作区功能里唯一缺失的那一步接线**；
  桥有上限 5 座、会被回收/冷却，一旦哪轮决定"某座工作区从此不管了"，删掉这个函数的人就得从零再写一遍。
  ✅ **（已于 2026-09-18 落实"①把注释补上"**：`TaskStore.kt:756-775` 的注释写明了保留理由与"绝不可接桥回收"的裁定，见上）
  原文对"接到桥回收上"的讨论保留如下，供对照——但那是**行为变更，不是清理**，且现已被源码注释否决。
  **如果团队就是要瘦身**：可以删，但必须同时删 `NotifyState.forget` 与那条单测，
  并在 `docs/18` §4 记一笔"按工作区拆卸能力已移除"——**不能只删函数**（会留下一个永远没人调的 `forget`）。
- **验证方式**：本机自动即可。`cd android-shell; ./gradlew :app:testReleaseUnitTest`（删/改后必须绿）。
  若将来真的把它接到桥回收上（**这是行为变更，不是清理**），就必须真机：上机后看
  「桥被回收/冷却时，只有那座工作区的卡片消失，另一座在工作区的卡片与正文不动」。

### B3. 会话与任务索引 —— `TaskStore.hasRunningTasks`  [`B 级 / 仅测试读取`] ⛔ 已于 2026-09-18 清理（第 4 批）

> **⛔ 已于 2026-09-18 清理（第 4 批）**：`hasRunningTasks` 已从 `TaskStore` 删除。现源码只留 `runningTaskRefs()`
> （`TaskStore.kt:197-206`，其文档注释写明"旁边原本还有个 hasRunningTasks……同一件事给两个出口，迟早口径分叉，遂删；
> 要问『有没有在跑』请用 `runningTaskRefs().isNotEmpty()`"）。两条测试断言按本文建议**改写而非删除**：
> `NotifyStateTest.kt:481-483`（跨工作区合并，断言改 `runningTaskRefs().isNotEmpty()`）与 `:505`（reset 后清空，`runningTaskRefs().isEmpty()`）。
> 以下原文存档，供回溯判定依据。

- **功能归属**：运行态判定（三源）＋会话与任务索引——它就是 "此刻有没有活干" 的查询口。
- **这个功能是干什么的**：判断"某任务此刻是否在跑"有三条信息来源（controller 流 / sessions-index 持久态 / 会话流），
  取最新报到的那条；`TaskStore` 把这个结论摊成两个查询口。用户感知到的是
  **"有没有在跑的任务"这件事决定了卡片、以及承载（Tier2）该覆盖到哪座工作区**。
- **这条代码的角色**：`runningTaskRefs()`（返回"工作区键 + 会话 id"的清单）的**布尔简写**。
  数据来源与它完全一致（都走 `runningIn()`，含 3 秒完成观察窗语义）。
  它预言的那个生产决策今天确实存在、只是由清单形式承担：承载覆盖目标 `carrierCoverageTargets()`
  就是"**把有在跑任务的工作区**开进覆盖名单"（`ShellRuntime.kt:1179-1195`；README 强调这一档
  "不受多工作区开关管，那是卡片的本体"）。所以它不是一个虚构的问题，而是一个**已经有活的问法**的问题。
- **功能现状**：所属功能`活`（真机 2026-09-18 三条源定案）。
  **这条属性生产侧零调用**——证据：全仓 `grep hasRunningTasks` 只命中 `NotifyStateTest.kt` 两条断言；
  同族的 `runningTaskRefs()` 被生产路径大量使用（`ShellRuntime.kt:664/745/789/1187/1195/1303`、`WebAppBridge.kt:55`）。
  另注：`docs/18` §4（`:381`）把这一族的风险写作 `hasWorkspaceTasks`，与现名 `hasRunningTasks` 不一致，属文档笔误。
- **删了会怎样**：
  - 用户视角：**什么都不会变**（生产路径没有它）。
  - 代码视角：只需改两处**测试内部**的断言（`NotifyStateTest` 的跨工作区合并与 reset 清空两条），两条断言的**测试价值不变**，
    改完即绿。——这正是最终执行的方案。
- **建议**：~~删除并改测试（风险：低）~~ → **✅ 已照此执行**。理由存档：同一个问题留两种问法只会漂移；
  生产侧真实的问法是 `runningTaskRefs()`（它要的从来不是"有没有"，而是"是哪些"）。
  删的是重复的拼写，不是能力。**注意必须改断言而不是删掉断言**，否则白丢两条钉子的覆盖。
- **验证方式**：本机自动。`./gradlew :app:testReleaseUnitTest`（纯 Kotlin 纯逻辑，无需真机）。

### C4. 会话与任务索引（＋多工作区承载与轮换）—— `Workspace` 的 `path` / `identity` / `source` 三个字段  [`C 级 / wire 形状字段`]

- **功能归属**：会话与任务索引（sessions-index）；同时是**多工作区承载与轮换**里"工作区键"算法的原料。
- **这个功能是干什么的**：`Workspace` 是记账本里"一座工作区"的记录：`key`＝地址（通知 id 与桥都用它寻址）、
  `title`＝卡片上显示的工作区名、`tasks`＝任务清单。另外三个字段（`path` 磁盘路径、`identity` 远程工作区身份、
  `source` 这份快照从哪来）是**帧里原样带着**的。用户只在卡片第一行看到 `title`，看不到这三个。
- **这条代码的角色**：**wire 形状记账**，不是功能本体。数据由 `ShellRuntime.onSessions` 喂入
  （`ShellRuntime.kt:2082-2084` path/identity/source，三者都是 `data.optString(...)` 直取），
  再由 Tier2 那条路补齐（`SessionsIndexState.buildUpdate()` `TaskStore` 之外的 `core/SessionsIndexState.kt:220-222` 亲手把 `workspacePath`/`workspaceIdentity`/`source` 写进帧）。
  **它不给任何人数据**。
- **为什么当初会有它们（这是判断能不能删的关键）**：因为**网页那份清单里本来就有这三个字段**，
  而壳里的 `core/SessionsIndexState.kt` 是网页解码器的**逐字段镜像**（类文档 `:7-13` 明说：输出字段形状要与
  `ShellRuntime.onSessions` 的消费形状完全一致，Tier2 接管期间的任务事件因此能直接进同一条通知链路）。
  也就是说，它们不是"为了某个功能加的、后来功能没了"，而是**"照抄一个更大的信封"带进来的多余维度**。
  另外要讲清一条常被误读的因果链：**工作区键确实是 `workspaceIdentity?.trim() || workspacePath` 算出来的**
  （页面 `GD`/`Co`；壳侧同规则的实现是 `RelayBridge.kt:814-838` 的 `keyOf`，注释 `:762`/`:815` 同口径；
  远程工作区必须用 identity，**顺序不能反**）——
  但**这个算法不在 `TaskStore` 里**：`onSessions` 收到的 `key` 已经是算好的成品（`ShellRuntime.kt:2059`），
  `TaskStore` 从不重算键。所以"留着 path/identity 是为了保住键算法"这句话**不成立**；
  真正需要这两个字段的活代码在 `RelayBridge`（它拿的是原始 JSON，不是 `Workspace` 记录）。
  真正成立的保留理由是另一条：**同名字段在一层之外确实是承重的**（承载发现链靠
  `workspaceIdentity?.trim() || workspacePath` 认出在跑的工作区），说明它们不是"作废数据"，只是"这一层暂时用不上"。
  再补一条关于 `source` 的事实（**2026-09-19 按当前源码更新取值枚举**）：它不是占位字符串，而是**真值的枚举**——
  `'active'`＝"这份清单是我们主动订阅来的"（**今天只剩 Tier2 一处**：`RelayBridge.kt:251` 的 `onSessionsWire`；
  网页桥那路 `'active'` 已随 D7（2026-09-17）/第 4 批 4c 的主动开桥删除而不复存在），
  `'passive'`＝"页面自己已经订了，我们只是旁听"（`zcode-protocol.js:1616` `_emitSessions(..., 'passive')` 与
  `:1236` `_emitSessionsStale`）、`'controller'`＝`TaskStore.applyLiveTasks` 为在跑任务凭空补出的工作区（`TaskStore.kt:597`，2026-09-19 复核新增枚举）。
  也就是说它记的是**这份快照的来路（主动性）**，而"来自哪条路"恰好是判断卡片新鲜度时最想问的问题；
  它在**协议层是被测试钉住的**（`RelayBridgeTest.kt:37` 断言 Tier2 帧里 `source == "active"`），只是壳收到之后没人再看。
- **功能现状**：sessions-index 本身`活`（真机出卡、多工作区真机验收）；
  这三个字段本身**零消费**（2026-09-19 核实仍然成立）——证据：Kotlin 侧 `grep "\.source\b"` 生产代码零命中；
  `path`/`identity` 唯一的"读取"是 `TaskStore.kt:306-307` 把它们**回写给自己**
  （`path.ifEmpty { previous?.path ?: "" }`、`identity.ifEmpty { previous?.identity ?: "" }`），
  即"这一帧没带就沿用上一帧的值"——**值确实被存住了，但没有任何下游去看**，所以这个"记忆"不可观测；
  `source` 连这个回退都没有（`source = source`，`:308`，纯直通）。
  上一轮审计（`docs/18` §4 `:718`）已刻意保留 `source`，理由写作"形状字段（与 wire 同构），本轮未动"；
  复核确认它**同样零读取**，与 `path`/`identity` **完全同类**。
  另一个佐证：`Workspace` 记录在 `TaskStore` 之外的消费者 `WebAppBridge.config()`（`WebAppBridge.kt:42-63`），
  它遍历所有工作区**只取 `workspace.key`**——本域外部读者用到的仍是键，`path`/`identity`/`source` 连一次都没被碰过。
  **（2026-09-19 补）** 本文"整组保留 + 注释标明"的建议已被采纳：`TaskStore.kt:19-35` 的字段文档写明
  "来源三件套 path/identity/source——**要留一起留、要删一起删**，绝不接受'三缺一'……审计时别按'有没有读'逐个判"，
  并把 `source` 三枚举与"诚实口径：今天没有任何消费读点"一并写进了注释。
- **删了会怎样**：
  - 用户视角：**零感知**。帧内容不变、通知内容不变、桥的寻址不变（键来自 `key`）。
  - 代码视角：删三个字段要同步改**生产 1 处 + 测试 2 个文件**的调用点——
    `ShellRuntime.kt:2079-2091`（`onSessions` 的实参，4c 后还多了 `stale`/`staleOnly` 两个新形参）、
    `TaskStore.applyWorkspace` 的形参与 `:303-310` 的构造、
    `applyLiveTasks` 里给新工作区凭空建记录的那处（`:591-599`，现在写 `path=""`、`identity=""`、`source="controller"`）、
    `NotifyStateTest.kt` 与
    `ControllerTasksStateTest.kt`（`source` 参数现位于 `:146/:184/:221/:233/:250/:262/:278/:285` 等处，全部是位置参数或具名参数）；
    协议侧**一个字都不用改**（去掉的只是 Kotlin 侧的接收维度，网页与 Tier2 帧照样带这三个键，
    `RelayBridgeTest.kt:37` 对 `source` 的协议断言照旧通过）。编译无风险，测试改完即绿。
  - **只删两个、留下第三个**的后果：`source` 会变成一个**没有同伴的孤例**——上一轮保留它的理由
    （"与 wire 同构"）恰好要靠 `path`/`identity` 一起成立；留下它、删掉另两个，等于把"这是形状字段"的证据删掉一半，
    后人只会看到"一个没人读的 `source` + 上一轮一句'刻意保留'"，无法自洽。**这是三个方案里最差的一个。**
- **建议**：~~三者作为一组「保留并加注释标明」~~ → **✅ 已照此执行（2026-09-18/19）**：整组保留，且 `TaskStore.kt:19-35`
  已把"要留一起留、要删一起删"的口径与 `source` 语义写进字段文档。原文理由存档：
  ① 三个字段属于同一个"照抄 wire 信封"的族，拆开就失去存在理由的可读性；
  ② 保留成本＝3 个字段（无运行时代价、无内存增长、无行为差异），收益＝在**快照折进来的那一行**留下了帧形状的书面记录；
  ③ 同名字段在 `RelayBridge` 一层之外确实承重（键算法、承载发现链）。`TaskStore` 自己若要核对
  "controller 派生的键与页面给的键是否同一座工作区"（现用一个兜底标题 `workspaceTitleOf(key)` 近似，`:698-701`），
  这两个字段就是现成原料。
  **如果团队要极致瘦身**：可以删，但请**三个一起删**（源码注释同口径），并在 `docs/18` §4 把 `source` 那行一并划掉——
  不要留下一个孤零零的形状字段。**两种做法都比"删两个留一个"诚实。**
- **验证方式**：本机自动即可，无需真机（无运行时行为变化）。
  加注释：`./gradlew :app:testReleaseUnitTest` 绿即可；
  整组删除：`./gradlew :app:testReleaseUnitTest` + `./gradlew :app:lintRelease`（改动纯 Kotlin，`node tools/*.js` 不涉及）。

## D. 本域小结表

| 编号 | 功能名 | 功能现状 | 建议动作 | 风险 | 验证方式 |
|---|---|---|---|---|---|
| B2 | 会话与任务索引（按工作区拆卸） | 所属功能`活`；此函数生产侧零调用（仅 `NotifyStateTest.kt:492`） | ✅ 保留并补注释（2026-09-18 落实；`TaskStore.kt:756-775` 写明理由并否决"接桥回收"） | 低 | 本机 `testReleaseUnitTest`；若将来接线桥回收则需真机看撤卡 |
| B3 | 运行态判定（三源）/ 会话与任务索引 | ⛔ **已删**（2026-09-18，第 4 批）：`hasRunningTasks` 不复存在，断言已改用 `runningTaskRefs()` | 已执行：删除并改测试 | 低 | 本机 `testReleaseUnitTest` |
| C4 | 会话与任务索引（wire 形状） | 所属功能`活`；`path`/`identity`/`source` 三者壳内零消费（仅 `:306-308` 自我回退/直通） | ✅ 只加注释标明（2026-09-18 落实；`TaskStore.kt:19-35`） | 低 | 本机 `testReleaseUnitTest` |

## E. 与既有结论的关系

1. **`docs/18` §4（`:381`）的写法有笔误**：把本族风险项写成 `TaskStore.hasWorkspaceTasks`，
   而这个名字全仓不存在，实际符号曾是 `hasRunningTasks`——**该符号本身也已于 2026-09-18（第 4 批）删除**，
   现在的正确问法只剩 `runningTaskRefs()`（`core/TaskStore.kt:204-206`）。读旧文档时请按这个名字换算。
2. **`docs/18` §4（`:718`）对 `TaskStore.Workspace.source` 的处理**：原结论"形状字段，本轮未动"是正确的，
   `path`/`identity` 与它完全同类（Kotlin 侧没有任何
   `某对象.source` / `某记录.path` / `某记录.identity` 形式的读取表达式；`path`/`identity` 只被 `:306-307` 回写给自己），
   决策粒度应按"**一组三个字段**"走——这一条已被源码注释（`TaskStore.kt:19-35`"要留一起留、要删一起删"）固化。
   `source` 的真实语义已随 4c 扩为三枚举：`'passive'`（网页旁听，现在网页侧**只有**这一个取值）、
   `'active'`（Tier2 主动订阅）、`'controller'`（store 为在跑任务凭空补出的工作区）。
3. **不推翻**任何 README 结论。特别地：README 关于工作区键
   `workspaceIdentity?.trim() || workspacePath` 的结论**仍然成立且必须遵守**（2026-09-19 核实：实现与注释在 `RelayBridge.kt:814-838`）——
   但要注意它约束的是 `RelayBridge` 与页面，**不是** `TaskStore`；`TaskStore` 收到的是算好的 `key`。
   这条区分是 C4 能否安全处置的前提。
4. **`docs/18` §4（`:714`）"`livePhases` 永不写入、恒空"已被第一阶段 D4 判定过期**（页面 controller 流会真实写入）。
   这条与本域直接相关（B2 清理的正是这几个 map）：**不要因为信了那句旧话就顺手删掉本域任何运行态代码**——
   按 D4 的口径走。2026-09-19 复核：`livePhases`/`liveTitles`/`livePhasesAt` 仍由 `applyLiveTasks`（`:569-577`）真实写入，
   三源"谁后到谁算数"的时刻表（`livePhasesAt`/`conversationPhasesAt`/`siPhasesAt`，`:157-159`）也是活的。
5. **（2026-09-19 补）** 本域 2026-09-18 新增了一条重要机制（4c 防降级），本文档最初版本没有覆盖，见 F 节。

## F. 4c 防降级（陈旧读数 + 可信窗口）—— 2026-09-18 新增机制，原文档未覆盖

本域在第 4 批 4c 里长出了**一块新的功能本体**，全部在 `core/TaskStore.kt`，另有注入层与 `zcode-protocol.js`
两个配合点。它防的是真机上真实发生过的用户痛点：**被动路径漏掉一个增量帧后，该工作区读数不可信，
若按"没在跑"处理会提前关掉接管闸门（`ShellRuntime.maybeStartNativeCarrier` 的 `runningRefs.isEmpty()`），
后台卡片从此不更新**。

- **陈旧检出标记帧（仅标记帧）**：注入层被动路径检出序号缺口（`fromSeq !== seq`）那一拍，
  `zcode-protocol.js` 额外发一帧 `_emitSessionsStale`（`:1226-1241`，`stale:true, staleOnly:true`，
  `sessions` 是**冻结的旧读数、不得落地**）。普通整表帧则带 `stale:false`（`:1197-1198`）。
  为什么必须单独一帧：缺口期间 `seq` 有意冻结，"成功应用"与"缺口自愈"是同一件事，把 `stale` 挂在整表帧上它永远不会有 true 的那天
  （对照表见 `zcode-protocol.js:1217-1223`）。原生侧按字段分流，**字段缺失＝不陈旧**（向后兼容）。
- **`TaskStore.applyWorkspace` 的守卫**（`:285-294`）：`stale || staleOnly` 任一为真 ⟹ 只 `markStale`、**整帧不落地**，
  返回 `recompute(nowMs)`（不能返回空 Update，否则待办的完成回灌会被取消）；普通整表帧落地 ⟹ `clearStale`（缺口自愈）。
- **`staleSince` / `staleReadings` / 可信窗口**（`:117-121`）：`staleSince` 是"陈旧"这个**事实**（不随时间去伪，只有整窗快照能清）；
  `staleReadings` 是缺口前最近一次可信的在跑读数；`trustDeadlineMs` 是**可信窗口**（锚点＝用户离开那一拍，
  长度 `STALE_TRUST_WINDOW_MS = 2 分钟`，`:878`），由 `setBackgrounded`（`:340-363`）与 `markStale`（检出时已在后台那一臂，`:473-475`）武装。
- **`guardStaleDowngrade`**（`:509-535`，唯一的合成点 `effectiveTasks` 出口 `:640`）：可信窗口内，陈旧工作区的**在跑读数只增不减**——
  被抹空的会话整条补回、被翻成终态的相位顶回可信值，同时不挡"窗口内新开始的任务"（不挡好消息）。
  这就是 2026-09-14"SI 相位把 store 覆盖成全部完成、流体云卡片冻死"事故的制度化防线。
- **`staleGateHold` / `hasStaleWorkspaceInTrustWindow`**（`:391-410`）：接管闸门的第二句判据——窗口内存在陈旧工作区时，
  **不许仅凭"没有在跑任务"关闸**。ShellRuntime 三处调用与 store 的"已离开"状态同源（`ShellRuntime.userIsAway`）。
- **测试**：`NotifyStateTest.kt` 有整节 4c 用例（`:516` 起，Q12/Q13/Q14/Q15 逐条口径）。
- 读者面：`ShellRuntime.kt` 的闸门与 5s 同步点、`TaskStore.removeWorkspace`/`reset` 的清理（工作区整条消失时
  `staleSince`/`staleReadings` 跟着走，`:790-793`）。**清理任何 TaskStore 状态表时别漏了这对新表。**
