//! 设备身份（Device identity），对应 `Reference_code/lib/protocol/device_info.dart`。
//!
//! Dart 原文注释：设备身份在 relay 认证（relay auth）与 mobile-view-state 更新时发送。
//! 网页端把自己谎报为浏览器；Zemote 如实上报自己，以便桌面端显示真实的已连接客户端。
//!
//! ## 本模块的取数边界
//! Dart 用 `flutter/foundation` 的 `kIsWeb` 与 `defaultTargetPlatform` 取当前运行时平台。
//! 按 PORTING.md，本 crate **纯逻辑、无 I/O、不依赖平台**：本模块只定义
//! [`TargetPlatform`] 枚举与 `&str` 映射（序列化），**取数留给宿主**。
//!
//! 调用方（Dart）：
//! - `lib/protocol/relay_client.dart:165` —— `auth_init` 帧的 `meta.platform`
//! - `lib/protocol/zemote_client.dart:398` —— `mobile-view-state-update` 的 `deviceInfo.platform`

use serde::{Deserialize, Serialize};

/// Zemote 上报给对端的应用名。对应 Dart 常量 `zemoteAppName`（`device_info.dart:7`）。
///
/// 线上值固定为 `"zemote"`，不要改。
pub const ZEMOTE_APP_NAME: &str = "zemote";

/// Dart/Flutter `TargetPlatform` 的忠实镜像（`device_info.dart:13` 的 switch 分支）。
///
/// 这不是本 crate 自己发明的概念：它逐条对应 Flutter `TargetPlatform` 的 6 个取值，
/// 顺序与 `zemotePlatformName` 的 switch 分支一致，便于对照。
///
/// 注意：**web 不在这里**。Dart 先判 `kIsWeb`，命中就直接返回 `"web"`，
/// 不查 `defaultTargetPlatform`；因此 web 由 [`zemote_platform_name`] 的 `is_web` 入参表达。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TargetPlatform {
    /// 对应 `TargetPlatform.android` → `"android"`。
    Android,
    /// 对应 `TargetPlatform.iOS` → `"ios"`。
    Ios,
    /// 对应 `TargetPlatform.windows` → `"windows"`。
    Windows,
    /// 对应 `TargetPlatform.macOS` → `"macos"`。
    Macos,
    /// 对应 `TargetPlatform.linux` → `"linux"`。
    Linux,
    /// 对应 `TargetPlatform.fuchsia` → `"fuchsia"`。
    Fuchsia,
}

/// 对应 Dart `zemotePlatformName()`（`device_info.dart:11`）。
///
/// 返回真实运行时平台名（android / web / windows / ...）；无法识别时回落到 `web`，
/// 以保证握手在奇异目标上依然合法——Dart 侧用 `kIsWeb` 实现这个回落。
///
/// 与 Dart 一样，返回值只可能是下面 7 个字符串之一，且**大小写全小写**：
/// `"web"`、`"android"`、`"ios"`、`"windows"`、`"macos"`、`"linux"`、`"fuchsia"`。
/// 特别是 iOS 在线上是 `"ios"`（不是 `"iOS"`），macOS 是 `"macos"`（不是 `"macOS"`）。
///
/// # 入参即“取数”
/// - `is_web`：宿主传入 Flutter 的 `kIsWeb`。为 `true` 时无条件返回 `"web"`，与 Dart 的
///   短路顺序一致（web 上 `defaultTargetPlatform` 是按 UA 猜的，不参与判断）。
/// - `target`：宿主传入 Flutter 的 `defaultTargetPlatform`（非 web 平台）。
///
/// 本函数是纯函数：同样的入参永远得到同样的输出，不读环境、不做 I/O。
///
/// // PORT-NOTE: 宿主（Flutter/Kotlin 侧）负责取数：`is_web` 来自 `kIsWeb`，
/// // `target` 来自 `defaultTargetPlatform`。本 crate 不查询当前平台，也不改用
/// // `std::env::consts::OS` / `cfg!(target_os = ...)`——那是编译期固化，无法表达
/// // “web 构建跑在 android 上” 这类 Dart 语义。
pub fn zemote_platform_name(is_web: bool, target: TargetPlatform) -> &'static str {
    if is_web {
        return "web";
    }
    match target {
        TargetPlatform::Android => "android",
        TargetPlatform::Ios => "ios",
        TargetPlatform::Windows => "windows",
        TargetPlatform::Macos => "macos",
        TargetPlatform::Linux => "linux",
        TargetPlatform::Fuchsia => "fuchsia",
    }
}

/// 设备身份载荷：relay `auth_init` 的 `meta` 与 mobile-view-state 的 `deviceInfo` 共用同一形状。
///
/// // PORT-NOTE: 该 struct 的形状**不是**从 `device_info.dart` 抄来的——那个文件里只有
/// // 常量与函数。形状来自两处调用点内联的 map：
/// //   - `lib/protocol/relay_client.dart:163-168`（`auth_init.meta`）
/// //   - `lib/protocol/zemote_client.dart:396-401`（`mobile-view-state-update.deviceInfo`）
/// // 两处内容逐字节相同，故在此收成一个类型。
/// // 待主线收口：若 `relay_client.rs` / `zemote_client.rs` 也各自定义了等价结构，
/// // 由主线决定提升到公共模块或在本模块统一，避免三份重复。
///
/// 线上字段名（**已冻结，不可改**）：`platform`、`version`、`name`。
/// 三者都是单个小写单词，与 Rust 字段名逐字相同，因此不需要 `#[serde(rename)]`。
///
/// 字段取值来源：
/// - `platform` ← [`zemote_platform_name`]
/// - `version`  ← `params.appVersion`，Dart 侧为 null 时回落字符串 `"web"`
///   （`connection_params.dart:11` 的 `appVersion`）
/// - `name`     ← [`ZEMOTE_APP_NAME`]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceInfo {
    /// 真实运行时平台名，取自 [`zemote_platform_name`]。线上字段 `platform`。
    pub platform: String,
    /// 应用版本号。线上字段 `version`。
    ///
    /// // PORT-NOTE: Dart 在调用点写 `params.appVersion ?? 'web'`，回落值 `"web"` 属于
    /// // 调用点的语义，不在本类型里兜底——本类型只承载已定好的字符串，保持与 Dart
    /// // “map 里放什么就是什么” 一致。宿主要自行应用该回落。
    pub version: String,
    /// 应用名，固定为 [`ZEMOTE_APP_NAME`]。线上字段 `name`。
    pub name: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 期望值来源：`Reference_code/lib/protocol/device_info.dart:7` 的字面量。
    /// 该文件在 `Reference_code/test/` 下**没有**专属测试（已全仓 grep 确认），
    /// 因此期望值直接取自 Dart 源码的 switch 分支与常量字面量，未做任何推测。
    #[test]
    fn app_name_is_zemote() {
        assert_eq!(ZEMOTE_APP_NAME, "zemote");
    }

    /// 对应 Dart `zemotePlatformName()` 的 6 个 switch 分支（`device_info.dart:13-26`）。
    /// 期望字符串逐条照抄 Dart 的 `return` 字面量。
    #[test]
    fn platform_name_matches_dart_switch_arms() {
        assert_eq!(zemote_platform_name(false, TargetPlatform::Android), "android");
        assert_eq!(zemote_platform_name(false, TargetPlatform::Ios), "ios");
        assert_eq!(zemote_platform_name(false, TargetPlatform::Windows), "windows");
        assert_eq!(zemote_platform_name(false, TargetPlatform::Macos), "macos");
        assert_eq!(zemote_platform_name(false, TargetPlatform::Linux), "linux");
        assert_eq!(zemote_platform_name(false, TargetPlatform::Fuchsia), "fuchsia");
    }

    /// 对应 Dart `if (kIsWeb) return 'web';`（`device_info.dart:12`）——短路优先。
    /// 与 Dart 相同：`kIsWeb` 为真时**不查** `defaultTargetPlatform`。
    #[test]
    fn web_short_circuits_every_target() {
        for target in [
            TargetPlatform::Android,
            TargetPlatform::Ios,
            TargetPlatform::Windows,
            TargetPlatform::Macos,
            TargetPlatform::Linux,
            TargetPlatform::Fuchsia,
        ] {
            assert_eq!(zemote_platform_name(true, target), "web");
        }
    }

    /// 大小写是线上契约的一部分：Dart 返回全小写，`"iOS"` / `"macOS"` 是错的。
    #[test]
    fn platform_names_are_all_lowercase() {
        for target in [
            TargetPlatform::Android,
            TargetPlatform::Ios,
            TargetPlatform::Windows,
            TargetPlatform::Macos,
            TargetPlatform::Linux,
            TargetPlatform::Fuchsia,
        ] {
            let name = zemote_platform_name(false, target);
            assert_eq!(name, name.to_ascii_lowercase());
        }
    }

    /// 序列化形状与调用点逐字节对齐：
    /// `relay_client.dart:163-168` 的 `meta`、`zemote_client.dart:396-401` 的 `deviceInfo`
    /// 都是 `{platform, version, name}`，键序即此顺序。
    /// 断言整串 JSON（含键序、无多余键），任何字段改名/增删都会在这里失败。
    #[test]
    fn device_info_serializes_to_frozen_wire_shape() {
        let info = DeviceInfo {
            platform: zemote_platform_name(false, TargetPlatform::Android).to_string(),
            version: "0.5.3-beta.5".to_string(),
            name: ZEMOTE_APP_NAME.to_string(),
        };
        assert_eq!(
            serde_json::to_string(&info).unwrap(),
            r#"{"platform":"android","version":"0.5.3-beta.5","name":"zemote"}"#
        );
    }

    /// web 目标下 `version` 回落成 `"web"`（调用点 `params.appVersion ?? 'web'`）。
    #[test]
    fn device_info_web_fallback_version_roundtrips() {
        let info = DeviceInfo {
            platform: zemote_platform_name(true, TargetPlatform::Android).to_string(),
            version: "web".to_string(),
            name: ZEMOTE_APP_NAME.to_string(),
        };
        let json = serde_json::to_string(&info).unwrap();
        assert_eq!(
            json,
            r#"{"platform":"web","version":"web","name":"zemote"}"#
        );
        let back: DeviceInfo = serde_json::from_str(&json).unwrap();
        assert_eq!(back, info);
    }
}
