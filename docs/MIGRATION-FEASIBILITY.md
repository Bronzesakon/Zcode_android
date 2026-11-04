# 迁移可行性评估（修订版）：薄壳接管 `Zcode_android`，原生应用归档

> 评估日期：2026-11-04 ｜ **只读评估，未改动任何代码/文档**
> 目标变更（2026-11-04 用户拍板）：
> 1. **纯原生 Compose 应用归档为「待改进版本」**，实际弃用，只作参考与后续迁移来源；
> 2. **薄壳（WebView）成为主线**，独享 `Zcode_android` 仓库；
> 3. **放弃深度合并**——不做「一个 App 内 Web/原生切换」，不合并两套 Application/资源/服务；
> 4. 文档、CI、鸿蒙净化的迁移方案不变。
>
> 结论分级：**【实测】**＝本轮命令/文件核实；**【源码】**＝代码结构直接可见；**【推断】**＝证据强但需构建验证。

---

## 0. 结论摘要

**比原方案简单得多：从“合并两套 App”变成“壳接管仓库 + 原生整体归档”。**

原方案里最麻烦的 10 项冲突（Application、双 launcher、双前台服务、通知 ID、FileProvider authority、
资源重名、R8、双 owner、多设备路由、模式切换）**全部消失**，因为最终只有一个 App、
一个 applicationId、一套 Manifest、一套资源、一套服务。

剩下真正要处理的是四件事：

| # | 事项 | 性质 | 影响 |
| --- | --- | --- | --- |
| B1 | **壳的 Tier2 原生接管只支持 3.15.3 的 HMAC 认证** | 源码事实 | 3.16.1+ 链接没有 `hash`，`maybePostRelayCreds()` 直接 return ⇒ **后台原生接管在 3.17.1 上不会启动** |
| B2 | **原生仓库有 67 个未提交改动** | 工作树事实 | 归档前必须先 commit/建分支，否则 Compose 工作全部丢失 |
| B3 | **壳最后验证基线是 3.15.3** | 源码 + 文档 | 协议级注入稳定；`RemoteHistoryPatch` 写死 3.15.3 资源路径，3.17.1 上静默失效；DOM 锚点需重跑审计 |
| B4 | **签名/Secrets 换人** | 发布事实 | 仓库 Secrets 当前是 `app.zemote` 的 keystore；主线换成 `com.zcode.remote`，必须换成壳的 keystore，否则老用户无法覆盖升级 |

**没有架构级阻断。** 这是一次“仓库换主线”的迁移，不是重写。

### 执行进度（2026-11-04）

- **已完成**：鸿蒙侧彻底删除——113 个跟踪文件删除（`android-shell/` 110 + Android CI/发布脚本 + 2 个 Android/迁移文档），
  根 APK 删除，`README.md` / `Docs/README.md` 重写为纯鸿蒙，`.gitignore` / `.zcodeignore` 清理，
  `entry/.../Index.ets` 中的 Android 注释已改；本地 `.adb-bridge`（38 MB）与 `.workbuddy-ai`（1.1 MB）移出仓库；
  鸿蒙记忆已按纯鸿蒙重写（22 个 Android 壳记忆移出记忆目录）。
- **已暂存**：`E:\Zcode_android\scratch\android-shell-import\`（269 MB，壳源码 + 本地文档 + ColorOS + keystore）；
  Android 自撰文档与厂商文档在 `E:\Zcode_android\docs\android\`（91 MB）。
- **未执行**：原生 67 个未提交改动的 commit/tag、壳搬入 `android/`、CI 切换。这些属于 P1。

---

## 1. 盘点（本轮实测）

### 1.1 壳（将成为主线）

| 项 | 数值 |
| --- | --- |
| Kotlin 主源码 | 36 个文件 / 16,789 行（含 JS assets） |
| Kotlin 单测 | 17 个文件 / 122 项 |
| JS 单测 | 82 项（`protocol.test.js` 37 + `inject.test.js` 45） |
| 注入层 | `assets/inject.js` 4,021 行 / 186 KB；`zcode-protocol.js` 1,961 行 / 88 KB |
| namespace / applicationId | `com.zcode.remote` |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| AGP / Kotlin / Gradle | 8.7.3 / 2.1.0 / 8.9 |
| 依赖 | appcompat、material、constraintlayout、webkit、zxing、okhttp |
| release 混淆 | 关闭 |
| 签名 | 自己的 keystore（`android-shell/scratch/`，本地-only） |
| Git 跟踪 | 110 个文件；`docs/`、`ColorOS_docs/`、`scratch/` 均未跟踪 |

### 1.2 原生（将归档）

| 项 | 数值 |
| --- | --- |
| Kotlin 主源码 | 109 个文件 |
| Kotlin 单测 | 49 个文件 |
| namespace / applicationId | `app.zemote` |
| compileSdk / targetSdk | 36 / 36 |
| AGP / Kotlin / Gradle | 8.13.2 / 2.3.20 / 9.1.0 |
| 依赖 | Compose、CameraX、ML Kit、OkHttp、serialization、DataStore |
| Rust | `rust/crates/ffi`（工作区含 `target/`，**3.2 GB**） |
| 未提交改动 | **67 个文件**（Compose UI 相关工作） |
| 工作树体积 | `android/` 329 MB、`rust/` 3.2 GB（绝大多数是构建产物） |

### 1.3 文档体量

| 目录 | 大小 | 源仓库是否入库 |
| --- | --- | --- |
| `Zcode_harmony/Docs/` | 96 MB | 否（`.gitignore` 全忽略） |
| `android-shell/docs/` | 124 MB | 否 |
| `android-shell/ColorOS_docs/` | 88 MB | 否 |
| `Zcode_android/docs/` | 3.1 MB | 是 |
| `Zcode_android/archive/` | 53 MB | 是 |

---

## 2. 目标仓库结构（建议）

```
Zcode_android/
├── android/                       # ★ 薄壳主线（原 android-shell/ 原样搬入）
│   ├── app/                       #   com.zcode.remote
│   ├── gradle/  gradlew  ...
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── gradle.properties          #   zcodeBaseVersion
├── .github/
│   ├── workflows/android.yml      # ★ 壳的 CI（去掉 Rust/NDK）
│   └── scripts/release-notes.ps1  # ★ 壳的发布脚本
├── docs/
│   ├── android/                   # ★ 26 篇 Android 自撰文档
│   │   ├── vendor/                #   本地-only（56 篇 Android/M3 厂商文档）
│   │   └── coloros/               #   本地-only（149 篇 ColorOS/OPPO）
│   ├── web-reverse/               #   保留（3.17.1 网页逆向）
│   └── ...
├── archive/
│   ├── native-compose/            # ★ 原原生项目整体归档
│   │   ├── README.md              #   「已弃用，仅参考」
│   │   ├── android/               #   原 Compose 应用（含 67 个未提交改动的最终状态）
│   │   ├── rust/                  #   原 Rust 核心（只迁源码，不带 target/）
│   │   └── docs/                  #   原生专属文档（UI-SPEC/audit/chat/design…）
│   └── pre-3.16.1-auth/           #   已有
├── Reference_web/                 #   网页快照（不入库）
└── Reference_code/                #   Flutter 旧参考
```

为什么用 `android/` 而不是仓库根：

- 保留现有 CI 的 `paths: android/**` 语义，改动最小；
- 根目录继续放 `docs/`、`archive/`、`Reference_web/`，不把 Gradle 工程摊到根；
- 壳的 `settings.gradle.kts`/`app/`/`gradle/` 原样搬进 `android/`，`working-directory: android` 即可。

---

## 3. 迁移步骤评估

### 3.1 第一步：保全原生工作（B2，**先做**）

当前 67 个未提交文件是 Compose UI 的最后一轮工作。归档前：

1. 在 `main` 上提交（或建 `archive/native-compose` 分支提交）；
2. 打一个 tag（如 `native-compose-final`）；
3. 再执行目录移动。

否则 `git mv android archive/native-compose/android` 会把未提交改动一起搬走但难以追溯，
且后续再想从归档里“捡回某个修复”会失去上下文。

### 3.2 第二步：壳接管 `android/`

- 把 `android-shell/{app,gradle,gradlew,gradlew.bat,settings.gradle.kts,build.gradle.kts,gradle.properties,proguard-rules.pro,.gitattributes}` 复制到 `android/`；
- **不做包名/资源/Manifest 合并**：`applicationId` 保持 `com.zcode.remote`；
- 壳的 `README.md` / `CHANGELOG.md` 作为新主线文档（改名/改路径引用）；
- 壳的 `tools/` 放到 `android/tools/`（CI 需要）；
- 壳的 `scratch/`、`key.properties`、`*.apk` 继续 gitignore；
- 原生 `android/` 整体移到 `archive/native-compose/android/`。

**需要改的只有路径引用**：CI 的 `working-directory`、README 里的命令、脚本里的相对路径。
不涉及代码逻辑。

### 3.3 第三步：Rust 归档

- `rust/` 只迁**源码**（`Cargo.toml`、`crates/`、`README`），不要把 `target/`（3.2 GB）搬进归档；
- 归档后不参与 CI；
- 保留原因：它是未来“后台原生接管”的最佳 carrier（见 §5），不是死资产。

### 3.4 第四步：CI 换成壳的

**用户要求：壳的 CI 发布滚动脚本迁过来，作为新仓库的 CI。** 具体：

| 项 | 现状（目标仓库） | 迁移后 |
| --- | --- | --- |
| workflow | `android.yml`（Rust + Compose + 发布） | 壳的 `android-shell.yml`（JS 静态检查 + Kotlin 单测 + APK + 滚动发布） |
| 构建步骤 | cargo + NDK + gradlew | 去掉 Rust/NDK；只跑 gradlew |
| 版本源 | 根 `VERSION` | 壳的 `android/gradle.properties: zcodeBaseVersion` |
| 版本号 | `yyMMddHH`（UTC+8） | 相同（壳的脚本已是这套） |
| 签名 Secrets | `app.zemote` 的 keystore | **换成壳的 keystore**（同名 Secrets） |
| 发布 tag | `android-pre` | 相同 |
| 资产名 | `zemote-arm64-v8a.apk` + `zemote-version.json` | 壳的 `zcode-remote.apk` + `.md5`（无 version.json） |
| 静态检查 | 无 | 壳的 `js` job（Node 82 项 + Kotlin 结构/资源检查） |
| 发布说明 | `release-notes.ps1` | 壳的 `android-shell-release-notes.ps1` |

注意：

- **Secrets 必须换成壳的 keystore**，否则 `com.zcode.remote` 的已有安装无法覆盖升级；
- 根 `VERSION` 文件在迁移后不再被 CI 读取，建议删除或改为指向 `gradle.properties`；
- 壳没有应用内更新器，`zemote-version.json` 可以不再生成；如果以后要加更新器，再补；
- 壳 CI 里“拉取 3.15.3 的 `index-BGa3KiRe.js` 做 history patch 回归”的步骤，随 patch 一起删除或改为 3.17.1 的可选测试（见 §5）。

### 3.5 第五步：文档迁移（方案不变）

#### 已迁移（字节级一致，无需重复）

`archive/pre-3.16.1-auth/docs/remote-v4/` 已有且 MD5 与 `Zcode_harmony/Docs/` 完全相同：

`10, 13, 14, 15, 16, 17, 18, 19, 20, 21, 24, 28, 49`（13 篇）+ `README.md`。

#### 需迁移的 Android 自撰文档（26 篇）

| 组 | 文档 |
| --- | --- |
| 平台事实 | `11-remote-v4-平台事实.md`（实为 Android WebView/FGS/ColorOS/Live Updates） |
| 禁区 | `12-安卓壳-禁区陷阱与真机配方.md` |
| 注入审计 | `22-…壳端注入点审计…`、`23-…进对话无内容…`（2026-09-20 基线，比壳内旧版新） |
| 后台承载 | `25-android-shell-后台原生承载-独立设计.md`（与 `android-shell/docs/15` 同 MD5，只迁一份） |
| 全仓审计 | `26-android-shell-全仓审计-错误路线残留与清理.md`（与 `android-shell/docs/18` 同 MD5，只迁一份） |
| 功能域 | `29`–`34`（通知/诊断/多工作区/运行态/TaskStore/看门狗） |
| 分区审计 | `35`–`47`（relay-core、shell-runtime、tier2、activities、notify、core-state、inject-js a–d、protocol-js、resources、tests） |
| 性能 | `安卓壳-快速交接与性能收窄.md` |

目标：`docs/android/`（保持原编号，便于与旧文档互引）。

#### 留在鸿蒙

`01`–`09`（实况窗、ArkWeb、鸿蒙保活、ArkWeb API、复刻对照）；`27`/`48` 旧网页快照不迁；
`README-harmony索引.md` 留在鸿蒙。

#### 厂商文档（本地-only）

| 目录 | 内容 | 目标 |
| --- | --- | --- |
| `android-shell/docs/01`–`04` | 56 篇 Android 文件选择/M3/Android 16 规范 + 469 图 | `docs/android/vendor/` + `.gitignore`，只提交 manifest |
| `android-shell/docs/miuix/` | compose-miuix-ui 源码克隆 | 不迁（原生已归档，不需要） |
| `android-shell/ColorOS_docs/` | 149 篇 OPPO + 6 篇 Android，88 MB | `docs/android/coloros/` + `.gitignore`，只提交 manifest |
| `android-shell/docs/05-…20260911/` | 3.15.x 网页快照 | 不迁；其中分析篇已被 22/23 取代 |

> 目标仓库 docs 入库；200 MB+ 厂商文档不能直接 commit。建议只入库自撰 md，
> vendor/coloros 用 `.gitignore` + `MANIFEST.md` 记录来源与本地路径。

#### 原生专属文档

`UI-SPEC.md`、`CHAT-PAGE-SPEC.md`、`UI-CONFIRM-3.17.1.md`、`docs/audit/`、`docs/chat/`、
`design/`、`ARCHITECTURE.md`、`ENGINE-API.md`、`FFI.md` 等随原生项目移入
`archive/native-compose/docs/`，避免主线文档继续指向已弃用的实现。

### 3.6 第六步：双工作区 ZCode 记忆同步（用户新增要求）

迁移不是只动文件；两个工作区的项目记忆也必须跟着换主线，否则下一次会话会按旧目标继续
修 Compose 或继续在鸿蒙仓库改 Android 壳。

**两个记忆目录（实测路径）：**

| 工作区 | 记忆目录 |
| --- | --- |
| `Zcode_android` | `C:\Users\wdn32\.zcode\cli\memories\projects\zcode_android-77f9accff5473297\memory\` |
| `Zcode_harmony` | `C:\Users\wdn32\.zcode\cli\memories\projects\zcode_harmony-7c61232d71c51019\memory\` |

**Zcode_android 侧（2026-11-04 已做决策级同步）：**

- `project-overall-goals.md` 已覆写为新目标：WebView 薄壳主线、原生归档、放弃 Compose 复刻；
- 新增 `migration-shell-mainline.md`：记录迁移源/目标/归档/文档/CI/阻断项；
- `rewrite-plan-rust-kotlin.md` 顶部已标注「2026-11-04 已被取代」；
- `MEMORY.md` 索引已更新，旧目标不再作为当前口径。

**Zcode_harmony 侧（2026-11-04 已彻底清理，不留迁移叙事）：**

- **删除**所有 android-shell 相关记忆（实现、CI、多工作区、M3、ColorOS、WebView、Kotlin、通知定位、
  brand assets、main/pre 分支、交接区等共 22 个文件），备份只放在
  `E:\Zcode_android\scratch\zcode-harmony-memory-backup-20261104\`（不在记忆目录内，不参与召回）；
- `MEMORY.md` 重写为纯鸿蒙索引，不再包含任何 Android 壳条目；
- 不新增“迁出/归档”说明——鸿蒙侧**不应知道**这个子项目存在过；
- 剩余鸿蒙/网页记忆里的专名与路径已改为平台通用表述。

**剩余同步（放到 P4 收尾）：**

1. 迁移**实际执行后**，把 `Zcode_android` 侧记忆里的「计划/待执行」改为「已执行 + 日期 + 新路径」；
2. 鸿蒙侧**不得**再出现 android-shell 相关记忆或索引；Android 状态只写 `Zcode_android`；
3. 若迁移中改了版本源/资产名/Secrets，把最终值写进 `migration-shell-mainline.md`，不要留在对话里；
4. 检查 `MEMORY.md` 索引行描述是否仍写「Compose 对齐 / 不换 WebView」等旧口径。

> 记忆同步是**迁移验收项**，不是可选项：验收标准 = 在新会话里分别打开两个工作区，
> 问「当前主线是什么」，两边都应回答「薄壳主线，原生已归档」，而不是旧目标。

---

## 4. 鸿蒙仓库净化（方案不变）

> **2026-11-04 已执行**：`android-shell/`、根 APK、Android CI/文档已从工作树删除；
> 源码暂存 `E:\Zcode_android\scratch\android-shell-import\`，文档在 `docs/android/`。
> 仓库 `README.md` 与 `Docs/README.md` 已重写为纯鸿蒙，**不写“迁出/归档”说明**；
> 记忆也已按纯鸿蒙重写（见 §3.6）。

### 4.1 删除

- `android-shell/` 整个目录（代码、构建产物、docs、ColorOS_docs、scratch、APK）；
- 根目录 `zcode-remote-pre93.apk` / `pre95.apk` / `.md5`；
- `.github/workflows/android-shell.yml`；
- `.github/scripts/android-shell-release-notes.ps1`；
- `Docs/11,12,22,23,25,26,29–47, 安卓壳-快速交接…`（迁走后删除）；
- `Docs/27,48`（旧快照，可删）；
- `.gitignore` 中 android-shell 相关规则。

### 4.2 保留

- `entry/`、`AppScope/`、`build-profile.json5`、`hvigor/`、`oh-package.json5`、`code-linter.json5`；
- `Docs/01–09`（鸿蒙）+ `Docs/10,13–21,24,28,49`（网页/协议，鸿蒙也需要）；
- `项目文档.md`、`安卓薄壳迁移文档.md`（本地历史）。

### 4.3 引用清理

- `README.md` 中 Android 壳章节（构建、APK、CI、真机验证）整段删除/改写为纯鸿蒙；
- `entry/src/main/ets/pages/Index.ets` 注释里的 Android 引用（非代码依赖）；
- `Docs/09` 保留，但注明“Android 壳已迁出”。

**风险：低。** Harmony 代码不依赖 `android-shell/`；删后跑一次 `hvigor` 即可确认。

---

## 5. 3.17.1 兼容性（B1/B3，最重要的功能性风险）

### 5.1 好消息

来自 `28-remote-v4-网页更新影响` 的真实更新实测：

- 壳依赖的 **37 条 DOM/存储/协议锚点** 在一次真实网页升级中 **0 消失 / 0 恒假**；
- 壳侧 **0 行改动**；
- 壳对网页资源文件名/哈希/资源根**零引用**（除 history patch）；
- `WebSocket` hook 与 `zcode_type` 帧属于线协议层，跨版本稳定。

### 5.2 坏消息

| 项 | 问题 | 处理 |
| --- | --- | --- |
| `RemoteHistoryPatch` | 写死 `/remote/v4/3.15.3/assets/index-BGa3KiRe.js`；3.17.1 上不命中，补丁静默失效 | **直接删除**（优化失效但不会崩），或按 3.17.1 重写 |
| DOM 锚点 | 3.17.1 的 `[data-mobile-page="chat"]` 等选择器需重跑审计 | 用 doc 28 的方法重跑 37 锚点；不通过再改 |
| `mobile-task-view-update` | 3.17.1 新帧，壳旧解析器不认识 | 只做通知则影响小；要做“查看状态”再补 |
| **Tier2** | 只支持 legacy HMAC；3.16.1+ 无 `hash`，`maybePostRelayCreds()` 直接 return | **第一阶段不启用后台接管**；第二阶段用 Rust carrier（见下） |

### 5.3 后台接管的正确路径

壳原样迁过来后，在 3.17.1 上只能做：

- 页面持有连接；
- 原生只读 tap（WebSocket hook + 帧旁路）；
- FGS + 10s 心跳泵；
- 冷启动路由恢复。

**做不了**：原生直连 relay 的后台接管（Tier2 认证不匹配）。

第二阶段可选：

1. **Rust 引擎做 carrier**（推荐）：Rust 已支持 `auth_token` / ws2 / OAuth token 交换；
   把参考壳的 `HandoffState` / 判死判据 / 交还不变式移植过来；
2. 给 Tier2 补 OAuth：注入层把页面实际使用的 relay URL（含短 token）交给原生，Tier2 改走 ws2 + token。

**建议**：主线先只做“页面持有 + 只读旁路 + 心跳泵”，把 UI 和“回前台不重载”跑通；
后台接管放到 P3，用 Rust carrier，不再维护第二套 Kotlin 协议实现。

---

## 6. 风险清单

| # | 风险 | 等级 | 说明/缓解 |
| --- | --- | --- | --- |
| R1 | 原生 67 个未提交改动丢失 | **高** | 归档前先 commit + tag `native-compose-final` |
| R2 | 签名 Secrets 没换成壳的 keystore | **高** | `com.zcode.remote` 的已有安装无法覆盖升级；迁移前核对 keystore 别名/密码 |
| R3 | Tier2 在 3.17.1 不工作 | **高（功能）** | 第一阶段不启用；P3 用 Rust carrier |
| R4 | 3.17.1 DOM 锚点回归 | 中 | 重跑 37 锚点审计；history patch 删除/重写 |
| R5 | `rust/target` 3.2 GB 被搬进归档 | 中 | 只迁源码；`archive/native-compose/rust/` 加 `.gitignore` |
| R6 | 厂商文档 200 MB+ 入库 | 中 | vendor/coloros 本地-only + manifest |
| R7 | CI 路径/版本源/资产名残留 | 中 | 按 §3.4 逐项改；删根 `VERSION` 或改为指针 |
| R8 | 原生文档继续被主线引用 | 低 | 移入 `archive/native-compose/docs/`，README 标注“已弃用” |
| R9 | 壳的 AGP/Kotlin 版本较旧 | 低 | 壳原样保留自己的 8.7.3/2.1.0/8.9 工具链即可，不必升到原生那套；CI 用壳的版本 |
| R10 | 鸿蒙仓库删除后构建失败 | 低 | 无代码依赖；删后跑 hvigor |
| R11 | `DiagReceiver` 导出 + DUMP | 低 | 保留 debug；release 可移除或加签名权限 |
| R12 | 壳没有应用内更新器 | 低 | 主线暂时靠滚动预发布链接；需要时再补 |
| R13 | **双工作区记忆仍是旧口径** | **中** | 下次会话可能继续修 Compose 或在鸿蒙改壳；按 §3.6 同步，并把它列为迁移验收项 |

---

## 7. 分阶段路线

### P0：保全与决策（0.5 天）

1. 提交原生 67 个未提交改动，打 tag `native-compose-final`；
2. 确认壳的 keystore/别名/密码，准备替换 GitHub Secrets；
3. 确认 `android/` 作为壳工程根（推荐）还是仓库根；
4. 确认发布资产名（建议保留壳的 `zcode-remote.apk`）；
5. 确认 `VERSION` 与 `gradle.properties` 的单一版本源；
6. **双工作区记忆同步（决策级）**：按 §3.6 已完成；迁移执行后再做最终态收尾。

### P1：仓库换主线（1 天）

- 原生 `android/` + `rust/` + 原生文档 → `archive/native-compose/`；
- 壳 `android-shell/*` → `android/`；
- 删除目标仓库里与原生构建相关的 CI/脚本，换成壳的 workflow + release notes；
- 根 README/CHANGELOG 改为壳的说明；
- 验收：`cd android && ./gradlew :app:assembleDebug` 成功；壳的 122 Kotlin + 82 JS 测试全绿。

### P2：3.17.1 兼容回归（2–3 天）

- 重跑 37 锚点审计；
- 删除/重写 `RemoteHistoryPatch`；
- 真机验收：扫码 → 网页首页 → 对话 → 发送/上传 → 旋转 → 退后台 10 分钟 → 回前台不 reload。

### P3：后台接管（可选，2–4 周）

- 用 Rust 引擎做 carrier（替代 Tier2）；
- 参考壳的 `HandoffState` / 判死 / 交还 / logo 罩；
- 有在跑任务时立即接管，否则延迟；
- 验收：后台通知实时；回前台 ≤1.5s 可交互；KICKED 零闪现。

### P4：文档与鸿蒙净化（1 天，可与 P1 并行）

- 26 篇 Android 文档 → `docs/android/`（2026-11-04 已执行）；
- vendor/coloros 本地-only + manifest（已迁入 `docs/android/vendor`、`docs/android/coloros`）；
- 鸿蒙仓库删 `android-shell`、APK、Android CI/文档，重写 README（2026-11-04 已执行）；
- **双工作区记忆最终同步**（§3.6）：`Zcode_android` 侧记录最终路径/版本/Secrets；
  鸿蒙侧不得出现 android-shell 相关记忆；验收标准是两边的“当前主线”回答一致。

---

## 8. 需要拍板的决策

| # | 决策 | 选项 | 建议 |
| --- | --- | --- | --- |
| D1 | 壳工程落点 | `android/` / 仓库根 | **`android/`**（CI 路径改动最小） |
| D2 | 版本源 | 壳的 `gradle.properties` / 根 `VERSION` | **壳的 `gradle.properties`**（随壳 CI 原样迁移） |
| D3 | 发布资产名 | `zcode-remote.apk` / `zemote-*` | **`zcode-remote.apk`**（壳的发布脚本原样） |
| D4 | 原生 Rust 归档方式 | 源码 / 源码+target | **只迁源码**（target 3.2 GB 不入归档） |
| D5 | 后台接管 | 第一阶段不做 / 直接做 | **第一阶段不做**；P3 用 Rust carrier |
| D6 | 厂商文档 | 入库 / 本地-only | **本地-only + manifest** |
| D7 | 壳的 history patch | 保留 / 删除 / 重写 | **先删除**（3.15.3 指纹已失效） |
| D8 | 原生文档 | 留 `docs/` / 归档 | **归档到 `archive/native-compose/docs/`**，避免主线误导 |

---

## 9. 一句话总结

**这次迁移的本质是“换主线”：壳原样搬进 `android/`，原生（含 Rust、UI 文档、67 个未提交改动）整体封存到 `archive/native-compose/`，CI/文档/鸿蒙净化照旧。**
没有合并冲突，没有双 owner；真正的风险只剩三个——**未提交的原生工作要先保全、签名 Secrets 要换成壳的、Tier2 在 3.17.1 上已失效**。
