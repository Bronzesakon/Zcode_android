# 08 · 对话时间线滚动 / 跟随 / 进度条审计

- 审计对象（未提交工作区）：
  - `android/app/src/main/kotlin/app/zemote/ui/chat/timeline/Timeline.kt`
  - `android/app/src/main/kotlin/app/zemote/ui/chat/timeline/TimelineScrollIndicator.kt`
  - `android/app/src/main/kotlin/app/zemote/ui/chat/ChatScreen.kt`（时间线与 dock 布局）
  - `android/app/src/main/kotlin/app/zemote/ui/chat/ChatViewModel.kt`（`loadOlder` / `fetchOlderPage`）
- Compose 版本：`compose-bom:2026.06.01` → `androidx.compose.foundation:foundation-android:1.11.4`（Gradle 缓存实查）。
  本报告对 Compose 语义的结论来自该版本源码（从 Google Maven 下载 `foundation-android-1.11.4-sources.jar` 到临时目录只读解压，未写入仓库；审计后已删除）。
- 真机证据（仓库内已有日志，只读引用）：
  - `build-logs/pin-0922.log`（09:22 的贴底实验，日志 26/64 行是核心证据）
  - `build-logs/live-0847.log` / `live-0851.log`（08:44–08:46 的交互日志）
- 标注约定：**[已验证]** = 源码或日志直接证明；**[推断]** = 由源码/日志推导但未在真机复现，附验证方法。
- 本轮未改任何源码；adb 两台设备均为 `offline`，因此没有新增真机实测。

---

## 1. 结论速览

### 1.1 坐标语义（所有结论的前提，先记住这一张图）

`reverseLayout = true` 时，Compose 的「滚动方向」与屏幕方向相反，这一点在下面四个层面同时成立：

```
                屏幕坐标 y
   ┌───────────────────────────────┐  y=0
   │  16dp top contentPadding      │
   ├───────────────────────────────┤  ← 内容顶端 = maxOffset（滚动坐标最大端）
   │  item N (最旧)                │      index 最大 = 视觉最上
   │            …                  │
   │  item 2                       │
   │  item 1                       │
   │  item 0 (最新)                │  ← 内容底端 = 滚动坐标 0
   ├───────────────────────────────┤  ← viewport start（视觉底边）
   │  20dp bottom contentPadding   │
   └───────────────────────────────┘  y=H
```

| 量 | 非 reverseLayout | reverseLayout（本项目） |
| --- | --- | --- |
| index 0 | 视觉顶部 | **视觉底部（最新行）** |
| `viewportStartOffset` | `0`（或 `-topPadding`） | **`-bottomContentPadding`** |
| `viewportEndOffset` | `H - bottomPadding` | **`H - bottomContentPadding`** |
| `LazyListItemInfo.offset` | 距视觉顶部（可负） | **距视觉底部（item 0 贴底时 = 0，向上滚为负）** |
| `firstVisibleItemIndex/ScrollOffset` | 视口顶部那一项 + 已向上滚的像素 | **视口底部那一项 + 已向上滚的像素（`scrolling forward is positive`）** |
| `canScrollBackward` | 还能往下（更旧/更后）滚 | **还能往下（更旧/更后）滚**，`(index,offset)==(0,0)` 时为 false |
| `visibleItemsInfo` 顺序 | 按 index 升序 | **仍按 index 升序**：`first()` = 视觉最底，`last()` = 视觉最顶 |

来源（均已核对）：
- `LazyList.kt:232-239`：vertical + reverseLayout 时 `beforeContentPadding = bottomPadding`、`afterContentPadding = topPadding`。
- `LazyListMeasure.kt:451-452`：`viewportStartOffset = -beforeContentPadding`、`viewportEndOffset = maxOffset + afterContentPadding`。
- `LazyListMeasuredItem.kt:231-236`：reverse 下实际放置坐标 `mainAxisLayoutSize - offset - size`，故 `offset=0` 的项底边贴视觉底。
- `LazyListMeasure.kt:588-593`：reverse 下 `relativeOffset = mainAxisLayoutSize - absoluteOffset - item.size`。
- `LazyLayoutMeasuredItem.kt:82-91`：`visibleItemsInfo` 最后按 index 升序排序，**与 reverse 无关**。
- `LazyListState.kt:221-235`：`firstVisibleItemScrollOffset` 文档「Scrolling forward is positive」；reverse 下即「已从底部向上滚了多少像素」。
- `LazyListMeasureResult.kt:69-70`：`canScrollBackward = firstVisibleItem.index != 0 || firstVisibleItemScrollOffset != 0`。
- `ScrollableArea.kt:58-70`：reverseScrolling=true 时上滑手势产生反向 delta。

### 1.2 问题清单（按严重度）

| 级别 | 问题 | 位置 | 状态 |
| --- | --- | --- | --- |
| **P0-1** | 贴底用 `scrollOffset = Int.MAX_VALUE/2`，reverse 下实际跳到**最旧端（视觉顶）**，不是底部 | `Timeline.kt:797`（死常量）、`Timeline.kt:804-807` | [已验证] 日志 `pin-0922.log:26,64` |
| **P0-2** | `atBottom` 判据方向错：`viewportEndOffset - (first.offset+first.size)` 在 reverse 下量的是「距视觉顶」，导致**贴底时为 false、读历史到顶时为 true** | `Timeline.kt:141-150` | [已验证] 源码+日志算术，详见 §3.2 |
| **P0-3** | 跟随效果以「任意内容签名变化」为触发，且只要 `following` 就贴底；叠加 P0-1 形成「跳顶 → nearTop → loadOlder → 再跳顶」死循环 | `Timeline.kt:203-221`、`Timeline.kt:239-267` | [已验证] `pin-0922.log:14→27、52→65→90` |
| **P1-1** | 进度条方向整体反了；`insideItem` 恒为 0（`-top.offset` 在 reverse 下恒 ≤0） | `TimelineScrollIndicator.kt:58-62,85` | [已验证] 源码算术 + §4.6 数值例 |
| **P1-2** | `nearTop` 用「行数 16」近似像素阈值，且不判可滚性：短列表/一屏内会一开始就预取 | `Timeline.kt:152-160,486` | [已验证] 源码；真机行为 [推断] |
| **P1-3** | 按钮/渐隐用 `!atBottom` 而非 `!following`；插入新行后有一帧 `(1,0)` 状态会让按钮闪一下 | `Timeline.kt:409-449` | [推断] 附验证方法 |
| **P1-4** | `loadOlder` 失败重试是 1.5s 无限循环；同步失败（sessionId 为空）时 `loadingOlder` 不会置真，循环永不退出 | `Timeline.kt:239-267` | [已验证] 源码 |
| **P2-1** | 非跟随时的大批量 index-0 插入/折叠超过 LazyList 的 key 索引窗口（约 ±100~130）时，key 锚定静默回退到旧 index → 视口跳一下 | `Timeline.kt:275-285` + Compose `LazyLayoutKeyIndexMap.kt:52-95` | [已验证] 源码；触发频度 [推断] |
| **P2-2** | `normalizeTimelineRows` 把缺 `rowId` 的行全部移到列表末尾（视觉最顶/最旧端），位置错乱 | `Timeline.kt:555-576` | [已验证] 源码 |
| **P2-3** | `TimelineUnit.Group.key` 用 `rows.last()`：运行中的组每新增一条同族工具就换 key，条目重组、展开态丢失 | `Timeline.kt:520-529`、`Timeline.kt:363-392` | [已验证] 源码 |
| **P2-4** | 每个 delta 都做 4 遍 O(n) 归约 + 全量签名求和（n 可达 1300+ 行），易掉帧 → 竖条/滚动视觉上「飘」 | `Timeline.kt:109-139,203-208` | [推断] 日志 `merged=1308`，附验证方法 |
| **P2-5** | `atBottom` 的 `remember` 捕获了首次 composition 的 `density` | `Timeline.kt:141-149` | [已验证] 源码 |
| **P2-6** | 进度条轨道未扣 contentPadding（16/20dp），thumb 两端与内容端点不齐 | `TimelineScrollIndicator.kt:71-99` | [已验证] 源码 |
| **P2-7** | 非跟随时发送消息不会跳到底（用户看不到自己刚发的消息） | `ChatScreen.kt:302-308`、`Timeline.kt`（无发送联动） | [推断] 附验证方法 |
| **P2-8** | 代码注释里「reverseLayout 自动底锚定、插入新行同一回合顶上去」的假设**不成立**（见 §5.D） | `Timeline.kt:68-82,198-202,273-275` | [已验证] 源码 |

---

## 2. 根因展开（对应四个真机症状）

### 2.1 症状一：向底部滑动，右侧竖条反而向上跑

**根因：进度条把 reverse 的滚动坐标当普通坐标用，方向与原点同时错。**

`TimelineScrollIndicator.kt:58-62`：

```kotlin
val top = visible.last()                                  // 视觉最顶（index 最大）——选对了
val insideItem = ((-top.offset).toFloat() / topSize)      // reverse 下 top.offset ≥ 0，取负恒 ≤0 → 永远 0
val scrollableUnits = (total - estimatedVisible)…
val progress = ((top.index + insideItem) / scrollableUnits)…
```

- reverse 下 `top.offset` 是「该项底边距视觉底」的距离，越往上越大；`-top.offset` 恒为负，`coerceIn` 后永远 0，`insideItem` 是死代码。
- `top.index` 在**向上滚（看更旧）时增大**，所以 `progress` 随「向上」增大。
- `thumbTop = (track - thumb) * progress`（`:85`）又是从**屏幕顶部**往下量。
- 合起来：处于底部时 `progress ≈ 可见项数/total`（很小）→ thumb 在顶部；向底部滑（index 减小）→ thumb 继续向上跑。与用户描述完全一致。

`pin-0922.log` 的数值可以复算：`total=604`、可见跨度约 15、viewport≈1942px、`72dp≈191px` → `estimatedVisible≈10.2`、`scrollableUnits≈594`。视觉最底时 `top.index≈10` → `progress≈0.017`（thumb 贴顶）；视觉最顶时 `top.index=603` → `progress≈1.015→1`（thumb 贴底）。整条进度条是反的。

**修法**：见 §4.6（用「距底 index + 项内比例」归一化，再从顶部画）。

### 2.2 症状二：点击「回到底部」按钮无反应

**根因 A（主）：贴底函数传了 `Int.MAX_VALUE/2`，reverse 下等价于「跳到最旧端」。**

`Timeline.kt:797` 仍留着 `BOTTOM_SCROLL_OFFSET = Int.MAX_VALUE / 2`；实验版本 `scrollToTimelineBottom()` 用它当 `scrollOffset`。反编译不到旧 APK，但机制可以由 1.11.4 源码完整解释：

`LazyListMeasure.kt:132-238` 在 `scrollOffset` 极大时：

1. `currentFirstItemScrollOffset = Int.MAX/2`，`currentMainAxisOffset = -Int.MAX/2`；
2. 前向铺满循环里每项都满足 `currentMainAxisOffset <= minOffset`（`LazyListMeasure.kt:227-231`），于是 **除最后一项外全部被跳过**，`currentFirstItemIndex` 一路推进到 `itemsCount-1`；
3. 之后「视口没填满」分支把 offset 拉回一个正常量，最终状态落在 `(lastIndex, ~0)` → 视觉最顶。

日志完全对上：

```
pin-0922.log:26  pin(0): total 604→604 first 540@-129+57→589@-174+57 vpEnd=1889 off=47 canBack=true canFwd=false
pin-0922.log:64  pin(0): total 650→650 first 589@-174+57→649@-2952+4785 vpEnd=1889 off=2952 canBack=true canFwd=false
```

`first 589`（=604 的最后一项）且 `canFwd=false`，就是「落在最旧端」。`BOTTOM_SCROLL_OFFSET` 现在是死代码，但注释仍会诱导下一个人再填回去——**必须删除**。

**根因 B：`atBottom` 在真底部恒为 false，按钮永远不消失，点击后视觉上什么也没变。**

`Timeline.kt:141-150` 的判据在真底部 `(index=0, offset=0)` 时：

```
viewportEndOffset - (first.offset + first.size)
= (H - bottomPadding) - (0 + size0)     // size0 是普通气泡/文本行高
≈ 视口高度 - 几十 px  ≫ 48dp
→ false
```

用户即使已经在底部，按钮仍然显示；点它（旧构建）跳到最旧端或原地不动，按钮还在 → 「无反应」。

**结论**：`scrollToItem(0, scrollOffset = 0)` 才是正确贴底（源码推导见 §4.1）；当前源码已改成 0，但**尚未在真机验证**（本轮设备 offline）。

### 2.3 症状三：停留在前几轮对话时界面固定不住（竖条飘、底部新内容把视口往上顶）

这是三个缺陷叠加，日志能完整还原因果链。

1. **`atBottom` 在视觉顶部为 true（旧公式）**  
   在视觉最顶时，最上面一项的 `offset + size` 正好等于内容末端 `maxOffset`，而
   `viewportEndOffset = maxOffset + afterContentPadding`，所以
   `viewportEndOffset - (last.offset + last.size) = topContentPadding = 42px ≤ 48dp(≈127px)` → **true**。
   日志中反复出现：

   ```
   live-0847.log:3833  drag end → following=true atBottom=true
   live-0847.log:3841  drag end → following=true atBottom=true
   live-0847.log:3843  drag end → following=true atBottom=true
   ```

   用户明明在读最旧的历史，拖拽结束却把 `following` 恢复成 true。当前未提交版本把 `last` 换成 `first` 后，顶部不再误判，但底部也永远判不到（两个版本各错一半）。

2. **`following=true` + 内容签名变化 → 立即贴底（而贴底是 P0-1 的跳顶）**  
   `Timeline.kt:209-221` 的 `snapshotFlow` 观察「单元数 / 最后单元 lastRowId / 全量流式签名」，任何一次 delta 都会命中；旧构建的 `scrollToTimelineBottom()` 一执行就跳到最旧端。于是每来一条新内容，视口就往「上（旧）」跳一次——正是「底部追加的新内容把视口往上顶」。

3. **跳顶又把 `nearTop` 点亮，触发 `loadOlder`，形成闭环**：

   ```
   pin-0922.log:14  09:22:29.389  follow drift → pin units=604
   pin-0922.log:26  09:22:30.224  pin(0): … →589… canFwd=false        ← 跳到最旧端
   pin-0922.log:27  09:22:30.224  loadOlder trigger rows=1188 firstIndex=590
   pin-0922.log:52  09:22:30.530  follow drift → pin units=650
   pin-0922.log:64  09:22:31.421  pin(0): … →649… canFwd=false
   pin-0922.log:65  09:22:31.422  loadOlder trigger rows=1248 firstIndex=649
   pin-0922.log:90  09:22:31.731  follow drift → pin units=652
   ```

   每轮 `loadOlder` 合并 60 行 → 单元数增长 → 签名变化 → 再 pin → 再跳顶 → 再触发历史加载。用户看到的就是「停不住 + 竖条一直飘 + 历史被一口气拉完」。

4. **次要放大器**：非跟随时，若单批在 index 0 插入超过约 100~130 个单元（重连后的 snapshot 合并、批量 row.appended、或上方轮次折叠），LazyList 的 key 锚定会失效（`getIndex` 只查 nearest-range 窗口，找不到就返回 -1，`findIndexByKey` 静默回退旧 index），视口会一次性跳一段。见 §5.D。

**一句话**：`following` 的进入条件（atBottom）是错的，退出条件（drag）是对的；错入后又被错误的贴底函数放大成跳顶循环。

### 2.4 症状四：输入法弹起时时间线底部要跟输入框上沿同步

**现状（布局层，基本正确）**：
- `ChatScreen.kt:214` 时间线在 `Box(Modifier.weight(1f))` 里；`ChatScreen.kt:262-274` 的 dock 用 `windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))`。
- `enableEdgeToEdge()`（`MainActivity.kt:23`）+ `adjustResize`（`AndroidManifest.xml:40`）下，窗口不 resize，IME inset 由应用消费；`union` 取 nav/IME 的较大值，不会叠两条高度。
- IME 弹起 → dock 变高 → 时间线 Box 变矮 → `reverseLayout` 下 `(0,0)` 的锚点在**新的**视觉底边，最新内容自动停在输入框上沿。这是布局自然结果，不需要额外滚动。

**问题**：
- `Timeline.kt:226-234` 在视口高度每次变化时都调贴底。旧构建会借机跳到最旧端；修好 P0-1 后它变成冗余调用——`atBottom` 已是 true 时不应再 `scrollToItem`，否则会清掉 `lastKnownFirstItemKey`（`LazyListScrollPosition.kt:81-86`），在同一帧紧接着的数据变化上破坏 key 锚定。**应加 `!atBottom` 门控（或直接删除该效果）。**
- 非跟随时，视口从底部缩，内容整体被顶上去（底锚定），顶部正在读的几行会被裁掉。若希望「键盘弹起不打断阅读」，需要在高度变化时补偿 `scrollBy(-Δ)`（见 §4.8）。
- `minSdk=26`：API 26–29 的 IME inset 兼容路径未在本轮验证 [推断]。

---

## 3. 边界情况矩阵

> 状态列：`✅` 已验证（源码/日志）；`△` 推断（附验证方法）。

### A. reverseLayout 的排序与 offset 语义、贴底写法

| 子问题 | 结论 | 证据 | 状态 |
| --- | --- | --- | --- |
| `visibleItemsInfo` 排序 | **始终按 index 升序**，与 reverse 无关；reverse 下 `first()`=视觉最底（最新），`last()`=视觉最顶（最旧） | `LazyLayoutMeasuredItem.kt:82-91` | ✅ |
| `LazyListItemInfo.offset` | 距「滚动起点」的主轴偏移；reverse 下滚动起点=视觉底部，故 item 0 贴底时 `offset=0`，向上滚为负 | `LazyListItemInfo.kt:32-36`、`LazyListMeasuredItem.kt:126,231-236` | ✅ |
| `firstVisibleItemIndex/ScrollOffset` | 视口起点（视觉底）那一项 + 「forward（向上）已滚像素」；`(0,0)` 即绝对底部 | `LazyListState.kt:221-235`、`LazyListMeasureResult.kt:69-70` | ✅ |
| `viewportEndOffset` 的真实值 | `H - bottomContentPadding`；`viewportEndOffset - afterContentPadding = maxOffset`（内容顶端） | `LazyList.kt:232-239`、`LazyListMeasure.kt:451-452`；日志 `vpEnd=1889`、`1847=1889-42` 复算吻合 | ✅ |
| 贴底正确写法 | `scrollToItem(0, scrollOffset = 0)`；已是 `(0,0)` 时直接 no-op | 源码推导 + 日志反证（大 offset 跳顶） | ✅（源码）/ △（offset=0 真机） |
| `scrollToItem(0, 大 offset)` | 会跳过除最后一项外的全部项 → 落在最旧端；**任何 >0 的 offset 都不是贴底** | `LazyListMeasure.kt:146,149-152,227-231` + `pin-0922.log:26,64` | ✅ |
| 贴底要不要 offset 补偿 | 不要。reverse 下 index 0 的项底边天然对齐 `viewportStartOffset`，offset=0 即贴底；`Int.MAX/2` 是反模式 | 同上 | ✅ |

### B. `atBottom` 的正确判据

| 子问题 | 结论 | 状态 |
| --- | --- | --- |
| 正确表达式 | `listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 48.dp.toPx()`（等价于 `!canScrollBackward` 加 48dp 容差） | ✅ |
| 内容不足一屏 | 状态恒为 `(0,0)`，`atBottom=true`；不要用 `totalItemsCount` 或可见项数判断 | ✅ |
| 最后一行高于视口 | `index=0`，`offset` 随上滚增大；超过 48dp 即 false，正确 | ✅ |
| 底部 contentPadding | 测量时 `+= minOffset` 再 `-= minOffset` 抵消（`LazyListMeasure.kt:158-195`），`offset` 不会被 padding 污染；`(0,0)` 仍是真底部 | ✅ |
| IME 改变视口 | 高度变化不改变 `(0,0)` 的含义；跟随时继续为 true，非跟随时 `offset` 不变 | ✅（源码）/ △（真机） |
| 旧实现（`last` vs `viewportEndOffset`） | 真底部 true、**视觉顶部也 true**（顶部 padding 42px < 48dp）→ 顶部误恢复 following | ✅ 日志 `live-0847.log:3833` |
| 未提交实现（`first` vs `viewportEndOffset`） | 真底部 false（普通行高远小于视口）、顶部 false → 按钮常驻、跟随无法恢复 | ✅ 源码算术 |
| 直接用 item 几何判断 | 不要。`visibleItemsInfo.first()` 可能包含被 contentPadding 跳过的项，与状态里的 first item 不是同一项（日志 `first 589@-174` 但 `off=47` 即此现象） | ✅ |

### C. `following` 状态机

| 环节 | 结论 | 状态 |
| --- | --- | --- |
| 初始 | true；空列表不滚动 | ✅ |
| 进入 | ① 首次有内容/换会话；② 用户松手（含惯性结束）后 `atBottom`；③ 点「回到底部」；④ 发送消息后（建议补） | ①②③ ✅ / ④ △ |
| 退出 | **手指开始拖动的瞬间**（不等到移动），无论方向；不要用 `atBottom` 退出（程序化滚动/预取会瞬时为 false） | ✅ |
| 用户 vs 程序化 | 触摸用 `collectIsDraggedAsState`；`scrollToItem` 是自己的，必须不当作退出信号；滚轮/无障碍/键盘滚动不产生 DragInteraction，需要额外的「非程序化 `isScrollInProgress`」判据 | 触摸 ✅ / 其余 △ |
| 惯性/回弹 | 退出发生在 drag start；恢复必须等 `isScrollInProgress == false` 再留 100–200ms 稳定期；绝不在 fling 中途恢复 | ✅（当前实现方向正确） |
| 一次手势多次 Start/Stop | 日志显示同一手势会多次 `drag start/end`（多指/嵌套 scroll）；中途的 drag end 可能过早恢复 following → 恢复条件必须同时要求 `!isScrollInProgress` 且 `atBottom`，并考虑加长稳定期 | ✅ 日志 `live-0847.log:3799-3843` 区间 |
| 切会话/空列表 | 时间线离开组合时 `remember` 状态自然重置；若将来 ChatScreen 不再切骨架，必须用 `key(sessionId)` 包住时间线重置 `listState` | ✅ 当前路径 / △ 未来 |
| 重新贴底 | 只在 `following && !atBottom` 时贴；已是 `(0,0)` 时 no-op（否则会清 key 锚点） | ✅ |
| 发送消息 | 非跟随时发送不会跳底，用户看不到自己的消息；建议发送时 `following = true` + 贴底 | △ |

### D. 非跟随时，追加/流式增长/折叠不得改变视口

| 子问题 | 结论 | 状态 |
| --- | --- | --- |
| **reverseLayout 是否自动跟随 index 0 插入？** | **不会。** LazyList 默认按「第一个可见项的 key」保持位置（`LazyListScrollPosition.kt:94-105`、`LazyListItemProvider.kt:94-109`、`LazyListState.kt:397-404` 文档）。在 `(0,0)` 插入新 index 0 后，旧项 key 移到 index 1，状态变 `(1,0)`，**新行留在视口下方**，直到显式贴底才可见。代码注释「底锚定让追加在同一布局回合顶上去」只对「锚点项自身长高」成立，对「新行插入」不成立 | ✅ 源码 |
| 因此跟随是否必须显式滚？ | 必须。当前 `Timeline.kt:209-221` 的 pin 是**承重结构**，不能当兜底删掉；但它必须只在 `!atBottom` 时执行（修复 atBottom 后才有意义） | ✅ |
| 流式文本增长（锚点项） | 锚点项 index 不变、`offset=0`，长高向上扩，旧内容被顶上去，无需滚动 → reverse 的真正收益 | ✅ |
| 流式增长（非锚点、视口内） | 只影响其上方内容，锚点位置不变 | ✅ |
| 新增行（单个）在非跟随时 | key 锚定把旧锚点从 index j 改到 j+1，`offset` 不变 → 视口不动 | ✅ |
| 新增行（批量 > ~100–130） | 超出 `NearestRangeKeyIndexMap` 窗口（`LazyLayoutKeyIndexMap.kt:52-95`；窗口≈`30*(i/30)±(30+100)`），`getIndex` 返回 -1，`findIndexByKey` **静默回退旧 index** → 视口一次性跳（跳向量 = 批量大小） | ✅ 源码 / △ 触发频率 |
| 折叠/展开导致上方大量单元消失 | 同上：锚点 index 下移超过窗口 → 回退旧 index → 跳到更新的内容 | ✅ 源码 / △ |
| 匿名行（缺 rowId） | `normalizeTimelineRows` 把匿名行拼到列表末尾 = 视觉最顶/最旧端；流式行一旦缺 rowId 会「跳上去」 | ✅ 源码 |
| Group 追加同族工具 | `Group.key = rows.last().rowId` 变化 → LazyColumn 视为新 item：重组、`openGroups` 展开态丢失（运行中默认展开，通常看不出来；非默认展开的组会突然折回） | ✅ 源码 |
| 建议的兜底 | 非跟随时记录 `(anchorKey, firstVisibleItemScrollOffset)`，数据变化后用 `requestScrollToItem(newIndex, offset)` 校正（`LazyListState.kt:397-419`，不触发动画、只影响下一次 measure） | 推荐 |

### E. 向前翻历史 prepend 的视口稳定与预取阈值

| 子问题 | 结论 | 状态 |
| --- | --- | --- |
| reverse 下 prepend 会不会顶动视口？ | **不会。** 更旧的行插入到高 index 端，现有 item 的 index 全部不变，state 的 `(index, offset)` 仍指向同一项 → 不需要 `scrollBy`/`requestScrollToItem` 补偿（与网页 DOM 的 prepend 完全不同） | ✅ 源码；日志 `pin-0922.log` 中 units 604→650 期间 state index 未被负载改动（589→590 是 pin 造成的） |
| 需要什么前提？ | ① 新页只含 `rowId < cursor`（`ChatViewModel.kt:787-790` 已过滤）；② 合并后仍按 rowId 升序（`fetchOlderPage` `sortedBy` + `normalizeTimelineRows` 快路径）；③ Group 身份在 prepend 时不变（`rows.last()` 满足，见 P2-3 的权衡） | ✅ |
| 预取阈值（反向坐标） | 距内容顶端像素 = `(viewportEndOffset - afterContentPadding) - (lastVisibleItem.offset + lastVisibleItem.size)`；阈值 = `max(64.dp, 2 × viewportHeight)`（网页同口径） | ✅ 公式 / △ 真机 |
| 可滚性门控 | `nearTop` 必须要求 `canScrollBackward || canScrollForward`；否则内容一屏装得下时 `last.index == total-1` 恒真，一进页就拉历史 | ✅ |
| 当前实现的问题 | 用「距最高 index 16 个单元」近似，不是像素；单元高度差异大时提前/滞后都很离谱；短列表无门控 | ✅ |
| 「一进页把全部历史拉完」 | 主因不是阈值，而是 P0-3 的跳顶循环；阈值本身在正常滚动下只会在真正接近顶部时触发一次 | ✅ 日志 |
| 失败重试 | 当前 1.5s 无限循环；`onLoadOlder` 同步早退时 `loadingOlder` 不会置真 → 循环永不退出。建议最多 2–3 次退避，或改成「下一次滚动再试」（网页就是无内建重试） | ✅ 源码 |

### F. 右侧进度条

| 子问题 | 结论 | 状态 |
| --- | --- | --- |
| 方向 | 应从「顶部→底部」为正；当前 `progress` 随上滚增大且从顶部画 → 整体反相 | ✅ |
| `insideItem` | reverse 下 `top.offset ≥ 0`，`-top.offset` 恒 ≤0 → 恒 0，是死代码 | ✅ |
| 正确进度式 | `fromBottomUnits = first.index + min(-first.offset, first.size)/first.size`；`maxUnits = (total - 1 - (last.index - first.index)).coerceAtLeast(1)`；`progressFromTop = 1 - fromBottomUnits/maxUnits` → 在底部精确为 1、顶部精确为 0 | 推荐 / △ |
| thumb 高度 | `viewport / estimatedTotal`；`estimatedTotal` 应含 `mainAxisItemSpacing` 与 contentPadding。固定 72dp 估算与网页 `estimateSize=72px` 同源，但长 Markdown 行误差大；用可见项平均高度更准（会有轻微呼吸，可只在滚动停止时更新） | △ |
| 可滚动判定 | 用 `canScrollBackward || canScrollForward`；不要用 `total <= viewport/72dp` 估算 | ✅ |
| 空列表 | `layoutInfo.totalItemsCount==0` → 不画（连轨道也不画）；当前 `return@Canvas` 已满足 | ✅ |
| 短列表 | 不可滚 → 整条隐藏；当前估算在「行很矮但很多」/「行很高但很少」两种极端都可能误判 | ✅ |
| 端点 | 底部 thumb 贴轨道底、顶部贴轨道顶；轨道应扣掉 contentPadding（top 16dp / bottom 20dp）才能与内容端点对齐 | ✅ |

### G. 底部渐隐 / 回到底部按钮

| 子问题 | 结论 | 状态 |
| --- | --- | --- |
| 显隐依据 | 网页是「不跟随且单元数>0」；当前是 `!atBottom`。`atBottom` 修好后语义接近，但插入新行后有一帧 `(1,0)` 会让按钮闪一下 → 建议直接用 `!following` | ✅ 网页口径 / △ 闪烁 |
| 点击行为 | `following = true` + 瞬时 `scrollToItem(0,0)`；顺序正确（先置意图，滚动失败也不丢跟随） | ✅ |
| 与 following 的关系 | 点击后按钮应立即消失（按 `!following`）；按 `!atBottom` 则要等 pin 后的下一次布局 | ✅ |
| 渐隐 | 只在非贴底时出现，方向（透明→背景）正确；高度 40dp 覆盖底部 20dp padding + 20dp 内容 | ✅ |
| 无障碍 | `Surface(onClick)` 是否有可读标签取决于 Icon 的 `contentDescription` 是否被合并；建议在 Surface 上显式加 `contentDescription = "回到底部"` | △ TalkBack 验证 |

### H. IME / 视口高度 / 分屏 / 旋转 / 字体缩放

| 场景 | 结论 | 状态 |
| --- | --- | --- |
| IME 弹起（跟随中） | 布局自动完成：dock 让出 IME，时间线变矮，`(0,0)` 锚在新底边 → 最新消息贴着输入框上沿；额外 pin 应门控为 `!atBottom` | ✅ 布局 / △ 真机 |
| IME 弹起（非跟随） | 内容随底边整体上移，正在读的顶部被裁。若要保阅读位置：视口高度减少 Δ 时 `scrollBy(-Δ)`（reverse 下向后滚 = 内容下移） | △ 推荐 |
| IME 动画期间 | Compose 的 IME inset 是动画值，视口高度会连续变化；当前效果每次都 pin → 连续取消/重发滚动，易 jank。门控 `!atBottom` 后自然消失 | ✅ 源码 |
| 分屏/旋转 | `configChanges` 含 orientation/screenSize/screenLayout，不重建 Activity；跟随时保持底部，非跟随时底锚定会改变可见范围（可选用上面的补偿） | ✅ 清单 / △ 真机 |
| 字体缩放 | 行高变化，key 锚定保持锚点 index+offset；`48dp` 阈值不受 fontScale 影响（dp 只随 density） | ✅ |
| `density` 捕获 | `atBottom` 的 `remember` 无 key，闭包里的 `density` 是首次组合的值；改字体/显示大小时阈值不会更新。改成 `remember(listState, density)` 或在 derivedState 内 `with(LocalDensity.current)` | ✅ |
| API 26–29 的 IME inset | `WindowInsets.ime` 走 compat 路径，`union` 行为需在低版本机验证（避免多推一条键盘高度） | △ |

---

## 4. 推荐实现（伪代码级，不写入源码）

> 原则：**只保留一套坐标真理**（`firstVisibleItemIndex/ScrollOffset`），其它判据（atBottom、进度、预取）都从它派生；不再用 item 几何去猜「距底」。

### 4.1 贴底

```kotlin
private const val BOTTOM_EPSILON_DP = 48   // 与网页 48px 对齐

// 删除 BOTTOM_SCROLL_OFFSET（Int.MAX_VALUE/2）——reverse 下它不是「更靠底」，而是「越过列表起点」
private suspend fun LazyListState.pinToBottom() {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return  // 已贴底：no-op，保住 key 锚点
    scrollToItem(index = 0, scrollOffset = 0)   // reverse：item 0 的底边 = 视觉底
}
```

### 4.2 `atBottom`

```kotlin
val bottomEpsilonPx = with(LocalDensity.current) { BOTTOM_EPSILON_DP.dp.roundToPx() }
val atBottom by remember(listState, bottomEpsilonPx) {
    derivedStateOf {
        listState.layoutInfo.totalItemsCount > 0 &&
            listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= bottomEpsilonPx
    }
}
```

### 4.3 following 状态机

```kotlin
var following by remember { mutableStateOf(true) }

// 退出：drag start 立即退出（不等到位移）
LaunchedEffect(dragging) {
    if (dragging) { following = false; return@LaunchedEffect }
    // 恢复：等惯性真正停止 + 稳定期，再以底部判据决定
    snapshotFlow { listState.isScrollInProgress }.first { !it }
    delay(SETTLE_DELAY_MS)            // 150~200ms
    if (!listState.isScrollInProgress) following = atBottom
}

// 进入（首次有内容 / 换会话）
LaunchedEffect(sessionKey) { following = true; listState.pinToBottom() }

// 兜底：非触摸滚动（滚轮/无障碍/键盘）也要能退出
LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }
        .collect { scrolling ->
            if (scrolling && !dragging && !programmaticScroll &&
                listState.firstVisibleItemIndex != 0) following = false
        }
}
```

### 4.4 内容变化时的跟随（承重逻辑，不是兜底）

```kotlin
LaunchedEffect(listState) {
    snapshotFlow {
        // 只观察「结构/尺寸可能变化」的信号；viewport 高度单独观察
        Triple(unitCount, lastUnitKey, streamingSignature)
    }.collect {
        if (dragging || !following) return@collect
        // 关键：atBottom 只有在「已是 (0,0)」时为真；
        // 新行插入 index 0 会把状态顶到 (1,0)，atBottom=false → 这里必须 pin
        if (!atBottom) listState.pinToBottom()
    }
}
```

说明：`streamingSignature` 建议只统计「最后一个 turn 内的流式行」，不要每次 `sumOf` 全量 1300 行（P2-4）。

### 4.5 非跟随时保持锚点（防大批量插入/折叠）

```kotlin
data class Anchor(val key: Any, val scrollOffset: Int)
var anchor by remember { mutableStateOf<Anchor?>(null) }
var lastRows by remember { mutableStateOf(rows) }

// 在「数据没变的帧」记录上一帧布局的锚点（layoutInfo 还是变更前的那次测量）
SideEffect {
    if (!following && rows === lastRows) {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()
        if (first != null) {
            anchor = Anchor(first.key, listState.firstVisibleItemScrollOffset)
        }
    }
    lastRows = rows
}

LaunchedEffect(unitKeys) {
    if (following) return@LaunchedEffect
    val a = anchor ?: return@LaunchedEffect
    val newIndex = unitKeys.indexOf(a.key)          // 建议 remember(unitKeys) { key→index Map }
    if (newIndex >= 0 && !listState.isScrollInProgress) {
        listState.requestScrollToItem(newIndex, a.scrollOffset)  // 非动画，只影响下一次 measure
    }
}
```

### 4.6 进度条

```kotlin
val first = visible.first()                       // 视觉最底（index 最小）
val last  = visible.last()                        // 视觉最顶（index 最大）
val span  = (last.index - first.index).coerceAtLeast(1)

// 距底多少「项」：index + 该项被滚过的比例（reverse 下 first.offset ≤ 0）
val withinFirst = ((-first.offset).coerceIn(0, first.size).toFloat() /
    first.size.coerceAtLeast(1))
val fromBottomUnits = first.index + withinFirst
val maxUnits = (total - 1 - span).coerceAtLeast(1)

val progressFromTop = (1f - (fromBottomUnits / maxUnits)).coerceIn(0f, 1f)
val thumbTop = (trackHeight - thumbHeight) * progressFromTop   // 顶部=0，底部=1
```

- 在底部：`first.index=0, offset=0` → `fromBottomUnits=0` → `progressFromTop=1` → thumb 贴底。✅
- 在顶部：`first.index = total-1-span`、`-first.offset ≥ first.size` → 比值 ≥1 → clamp → thumb 贴顶。✅
- 可滚性：`canScrollBackward || canScrollForward`，否则整条隐藏。
- thumb 高度：`viewportPx / (avgVisibleItemPx * total + spacing * (total-1) + contentPadding)`，或保留 72dp 估算但接受误差；不要再拿它当「是否可滚」的判据。

### 4.7 预取阈值

```kotlin
val nearTop by remember(listState) {
    derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
        if (!listState.canScrollBackward && !listState.canScrollForward) return@derivedStateOf false
        val contentTop = info.viewportEndOffset - info.afterContentPadding   // = maxOffset
        val distanceFromTop = contentTop - (last.offset + last.size)
        distanceFromTop <= maxOf(with(density) { 64.dp.roundToPx() }, 2 * info.viewportSize.height)
    }
}
```

- 重试：最多 2–3 次指数退避；`onLoadOlder` 同步早退时不要进入循环。
- prepend 本身不需要滚动补偿（reverse 下高 index 端追加不动现有 index）。

### 4.8 IME / 视口高度

```kotlin
// 跟随时：无需动作（(0,0) 自动贴住新底边）；只在漂移时补一发
// 非跟随时：保住「顶部正在读的内容」
LaunchedEffect(listState) {
    var previous = listState.layoutInfo.viewportSize.height
    snapshotFlow { listState.layoutInfo.viewportSize.height }
        .distinctUntilChanged()
        .collect { h ->
            val delta = previous - h
            previous = h
            if (delta == 0) return@collect
            if (following) {
                if (!atBottom) listState.pinToBottom()
            } else {
                // reverse：视口底边上移 delta，向后滚 delta 让内容跟着下移，屏幕位置不变
                listState.scrollBy(-delta.toFloat())
            }
        }
}
```

### 4.9 按钮 / 渐隐 / 发送

```kotlin
val showJumpToBottom = !following && visibleUnits.isNotEmpty()
// 渐隐同源：visible = showJumpToBottom
// 点击：following = true; scope.launch { listState.pinToBottom() }
// 发送：onSend 时 following = true（并在消息上屏后 pinToBottom），否则用户看不到自己的消息
```

### 4.10 其它小修

- `TimelineUnit.Group.key`：改为 `rows.first().rowId()`（追加稳定；prepend 只影响正在读顶部的那一个组，代价更小），或引入不随两端变化的组 ID。
- `normalizeTimelineRows`：匿名行保持原相对位置，不要 `sorted + anonymous`。
- 删除 `BOTTOM_SCROLL_OFFSET`；把 `48.dp`/回退阈值/`72dp` 估算抽成常量并注释坐标系。
- `atBottom` 的 `density` 传入 `remember` key。

---

## 5. 验证清单

> 设备当前 adb 为 offline，以下步骤留给下一次真机。全部只读日志 + 交互验证，不改源码。

### 5.1 贴底方向（P0-1）

1. 安装包含 `scrollOffset = 0` 的构建，打开一个长对话。
2. `adb logcat -s ZemoteTrace | grep "UI/timeline"`。
3. 期望 `pin(0)` 行满足：
   - `first 0@0+...`（index 0，offset 0）
   - `off=0`
   - `canBack=false`、`canFwd=true`
4. 反例：`first <lastIndex>@...` 且 `canFwd=false` → 仍在跳顶，检查是否误用了 `Int.MAX/2`。

### 5.2 atBottom 与按钮（P0-2 / P1-3）

1. 在底部静置：按钮与底部渐隐必须**不可见**；日志 `drag end → following=true atBottom=true`。
2. 上滑 20dp（<48dp）松手：仍应恢复 following；上滑 100dp 松手：`atBottom=false`，按钮出现。
3. 滚到最顶部：按钮必须出现，且 `drag end → following=false atBottom=false`（旧公式在这里会给 true）。
4. 点按钮：一帧内跳到底，`pin(0)` 日志为 index 0，按钮立即消失。

### 5.3 跟随与追加（C/D）

1. 底部静置，等流式输出：不应出现 `follow drift → pin` 刷屏；`pin(0)` 只应在「新行插入」时出现（旧项长高不需要）。
2. 用 `adb shell input swipe` 上滑到历史中段，停住；持续等新 delta 到达：
   - 不应出现任何 `follow drift → pin`；
   - `firstVisibleItemIndex` 与 `visibleItemsInfo.first().key` 必须保持不变（建议临时加一条 debug 日志验证 key）。
3. 在上一步期间制造一次批量插入（重连/刷新 snapshot）：确认视口不跳；若跳，按 §4.5 加锚点补偿。

### 5.4 prepend / 预取（E）

1. 滚到接近顶部：`loadOlder trigger` 每次只应出现一页；合并后 `firstVisibleItemIndex` 不变、可见项 key 不变。
2. 内容不足一屏的短对话：不应触发 `loadOlder`（可滚性门控）。
3. 断网制造 `rowsRange` 失败：重试不应无限刷；`loadingOlder` 最终复位。

### 5.5 进度条（F）

1. 底部：thumb 贴轨道底；顶部：贴轨道顶。
2. 从底部缓慢向上滑：thumb 单调向上，不反向、不跳变。
3. 短对话（不可滚）：整条（含轨道）隐藏。
4. 深色/浅色主题下 thumb 与轨道对比度正常。

### 5.6 IME / 视口（H）

1. 跟随时弹键盘：最新消息底边紧贴输入框上沿，无跳顶；收起键盘同理。
2. 非跟随时弹键盘：正在读的顶部行不应被裁掉（若启用了 §4.8 补偿）。
3. 旋转/分屏：跟随时保持底部；非跟随时记录旋转前后可见项 key，应尽量保持。
4. 系统字体放大到最大：无崩溃、无 `coerceIn(min>max)`；按钮/进度条仍可用。
5. API 26–29 设备上验证 IME 只让出一次高度（不叠 nav bar）。

### 5.7 纯函数单测建议

把以下逻辑抽成 `internal` 纯函数并用现有 JVM 单测覆盖（`android/app/src/test/kotlin/app/zemote/ui/chat/timeline/`）：

- `atBottomOf(index, offset, epsilon)`：`(0,0)=true`、`(0,48dp)=true`、`(0,48dp+1)=false`、`(1,0)=false`、空列表=false。
- `progressFromTop(firstIndex, firstOffset, firstSize, lastIndex, total)`：底部=1、顶部=0、中段单调。
- `normalizeTimelineRows`：匿名行保持相对位置（当前实现会移到末尾）。
- `uniqueTimelineKeys`：重复匿名键的 `#n` 稳定性。

---

## 6. 源码证据索引（Compose foundation 1.11.4）

| 结论 | 文件:行 |
| --- | --- |
| reverse 下 before=bottomPadding、after=topPadding | `LazyList.kt:232-239` |
| `viewportStartOffset=-before`、`viewportEndOffset=maxOffset+after` | `LazyListMeasure.kt:451-452` |
| `offset` 是主轴偏移、相对滚动起点 | `LazyListItemInfo.kt:32-36` |
| reverse 实际放置 `H-offset-size` | `LazyListMeasuredItem.kt:231-236` |
| reverse 排列 `mainAxisLayoutSize-absoluteOffset-size` | `LazyListMeasure.kt:588-593` |
| 大 offset 时跳过除最后一项外的所有项 | `LazyListMeasure.kt:227-231` |
| index 0 处负 offset 被夹回 0 | `LazyListMeasure.kt:146,149-152` |
| `visibleItemsInfo` 按 index 升序 | `LazyLayoutMeasuredItem.kt:82-91` |
| `canScrollBackward` 定义 | `LazyListMeasureResult.kt:69-70` |
| `firstVisibleItemScrollOffset` 语义（forward 为正） | `LazyListState.kt:221-235` |
| `requestScrollToItem` 语义（只影响下一次 measure、取消进行中的滚动） | `LazyListState.kt:397-419` |
| key 锚定 + `requestPositionAndForgetLastKnownKey` 清 key | `LazyListScrollPosition.kt:81-109` |
| `findIndexByKey` 找不到 key 时回退旧 index | `LazyLayoutItemProvider.kt:94-109` |
| nearest-range key 窗口（≈±100/130） | `LazyLayoutKeyIndexMap.kt:52-95` |
| reverse 手势方向表 | `ScrollableArea.kt:58-70` |

> 获取方式：`https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.11.4/foundation-android-1.11.4-sources.jar`，解压后只读查阅；Gradle 缓存里只有 `foundation.aar`，没有 sources jar。

---

## 7. 遗留 / 未验证项

1. `scrollOffset = 0` 的贴底版本未在真机装包验证（设备 offline）；源码与旧日志已充分反证「大 offset 错误」。
2. 非跟随时批量插入 >100 项的触发频率未实测；机制已由源码确定，建议按 §5.3 的 key 日志验证。
3. API 26–29 的 IME inset 行为未验证。
4. TalkBack 对「回到底部」按钮的可读标签未验证。
5. `TimelineUnit.Group` 的 key 策略（first vs last）是权衡，不是绝对错误；按「追加频率远高于 prepend」推荐 first。
