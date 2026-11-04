# ZCode 远程（Android 薄壳）

手机/平板上的 **ZCode 桌面端远程控制客户端**。主形态是 **WebView 薄壳**：
直接加载官方 `zcode.z.ai/remote/v4`，界面、滚动、历史加载与动画全部继承网页；
原生层只负责网页做不到的部分——扫码接入、文件/图片选择、前台服务、后台保活、任务通知与实况窗。

> ⚠️ 本项目为**协议复刻/套壳**，与官方客户端无代码关联。请遵守 ZCode 服务条款与当地法律法规，
> 仅用于连接你自己的设备。

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `android/` | **主线**：WebView 薄壳（`com.zcode.remote`），Kotlin + 原生 View |
| `docs/android/` | Android 平台事实、壳机制、分区审计（厂商文档 `vendor/`、`coloros/` 本地-only） |
| `docs/web-reverse/` | 官方网页 3.17.1 的逆向规格（布局/组件/协议） |
| `docs/TODO.md` | 薄壳要求、Rust 核心最小替换评估与当前待办 |
| `docs/MIGRATION-FEASIBILITY.md` | 从「原生 Compose」切到「薄壳主线」的迁移评估与执行记录 |
| `archive/native-compose/` | 已弃用的原生 Compose + Rust 实现（tag `native-compose-final`），仅作参考 |
| `Reference_web/` | 网页快照（本地-only，不入 Git） |
| `Reference_code/` | 旧 Flutter 实现，协议逆向的权威参照（只读） |

## 构建

需要：

- **JDK 17**（本机 `E:\AndroidSdk\jdk-17`；Gradle 8.9 不支持更高版本 JDK）
- Android SDK（`compileSdk 35` / `targetSdk 35` / `minSdk 26`）
- 本机 `android/local.properties` 写 `sdk.dir=E\:\\AndroidSdk`
- 发布签名：`android/key.properties` 指向 `android/scratch/zcode-remote-release.p12`
  （本地-only；CI 从 GitHub Secrets 还原）

```bash
cd android
./gradlew :app:assembleDebug
```

单测：

```bash
cd android
./gradlew :app:testDebugUnitTest
node --test          # 注入层/协议 JS 测试
```

## 运行形态

- 桌面端 ZCode 生成远程控制二维码 → 手机扫码 → WebView 加载 `/remote/v4`；
- 首次扫码后 URL 长期持久化，冷启动直进控制页；
- 网页在前台持有唯一 relay 连接；原生只做只读观测（WebSocket hook + 帧旁路）；
- 退后台：前台服务 + CPU/Wi-Fi 锁 + 每 10s 原生心跳泵；页面健康时不重连、不重载；
- 回前台：立即停止原生干预；只有页面进入终态（KICKED）或进程死亡后的冷启动才重载，
  并用 logo 罩遮住失败屏。

## CI / 发布

`.github/workflows/android.yml`：

- `main` 分支 → 滚动预发布 tag `android-pre`，资产固定为 `zcode-remote.apk` + `.md5`；
- `v*` tag → 正式 Release，说明取自 `android/CHANGELOG.md`；
- `js` job：Node 注入/协议测试 + Kotlin 结构/资源检查；
- `build` job：Kotlin 单测 + `assembleRelease`，四个 `ANDROID_*` Secrets 提供壳的 keystore。

版本唯一来源：`android/gradle.properties` 的 `zcodeBaseVersion`；
`versionCode` 取构建时间（UTC+8，`yyMMddHH`），保证覆盖安装。

## 已知边界

- 网页基线 **3.17.1**：锚点审计已完成（77 条锚点存活 73、3.15.3 的 37 条基线 0 死），
  两条 **P0** 待修——版本闸门（壳从不写 URL 的 `app_version`）与 KICKED 语义变化（页面会自愈第一次）；
  清单见 `docs/TODO.md` §3。
- 首屏历史窗口补丁（`RemoteHistoryPatch`，写死 3.15.3）已删除；不再做页面脚本改写。
- 承载（Native Carrier，旧称 Tier2）在 3.17.1 上不启动（只支持 legacy HMAC）；
  语言已统一到 Kotlin，最小功能集与验收见 `docs/TODO.md` §2；Rust 协议核心留在归档当规格与测试基准。
- 渲染进程被杀的处理（`onRenderProcessGone`）尚未实现。

## 安全

连接 URL 含会话凭证（`sid` / `mid` / 旧版 `hash`），仅存于应用私有存储；
严禁写入日志、文档或提交记录。本仓库不包含任何真实连接凭证。

## 许可

自用侧载项目，未附开源许可证；`LICENSE` 仅覆盖仓库中历史代码的原始授权。
