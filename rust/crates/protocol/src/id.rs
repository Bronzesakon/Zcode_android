//! 对应 `Reference_code/lib/protocol/id.dart`——UUID / 请求 id 生成。
//!
//! Dart 侧只有两个函数：`generateUuid`（`Random.secure()` 生成 RFC 4122 v4 UUID）
//! 与 `generateRequestId(prefix)`（`'$prefix-<uuid>'`）。线上/落盘格式一个字节都不能变：
//! 小写十六进制、8-4-4-4-12、版本位 `4`、变体位 `8/9/a/b`。
//!
//! 熵源（Dart 的 `dart:math` `Random.secure()`）按 PORTING.md 的边界抽象成
//! [`SecureRandom`] trait；默认实现 [`OsSecureRandom`] 直接取操作系统 CSPRNG
//! （`getrandom`：Android 走 `getrandom(2)`，Windows 走 `BCryptGenRandom`），
//! 与 Dart 的 `Random.secure()` 同级；宿主仍可注入自己的实现。

/// 熵源。对应 Dart `dart:math` 的 `Random.secure()`，本模块只用到 `nextInt(256)`。
///
/// 宿主（Android/Kotlin）实现本 trait 并注入，可换成真正的 CSPRNG。
pub trait SecureRandom {
    /// 对应 Dart `Random.nextInt(256)`：返回 `[0, 256)` 的随机字节。
    fn next_byte(&mut self) -> u8;
}

/// 默认熵源：操作系统 CSPRNG（`getrandom`），与 Dart 的 `Random.secure()` 同级。
///
/// 一次向 OS 要 32 字节缓冲，按字节消费；缓冲耗尽再要一批。
pub struct OsSecureRandom {
    buf: [u8; 32],
    pos: usize,
}

impl OsSecureRandom {
    /// 新建一份熵源。对应 Dart `Random.secure()`。
    pub fn new() -> Self {
        Self {
            buf: [0u8; 32],
            pos: 32,
        }
    }

    fn refill(&mut self) {
        // 对应 Dart `Random.secure()` 在取不到安全熵源时抛 `UnsupportedError`：
        // 这里同样不做降级——悄悄换成弱熵源比失败更危险。
        getrandom::fill(&mut self.buf)
            .expect("OS entropy source unavailable (getrandom failed)");
        self.pos = 0;
    }
}

impl Default for OsSecureRandom {
    fn default() -> Self {
        Self::new()
    }
}

impl SecureRandom for OsSecureRandom {
    fn next_byte(&mut self) -> u8 {
        if self.pos == self.buf.len() {
            self.refill();
        }
        let b = self.buf[self.pos];
        self.pos += 1;
        b
    }
}

/// 对应 `id.dart` 的 `generateUuid()`。
///
/// 生成 RFC 4122 v4 UUID：16 个随机字节，`b[6] = (b[6] & 0x0F) | 0x40`（版本 4），
/// `b[8] = (b[8] & 0x3F) | 0x80`（变体 `10xx`），小写十六进制按 8-4-4-4-12 拼装。
pub fn generate_uuid() -> String {
    generate_uuid_with(&mut OsSecureRandom::new())
}

/// 对应 `id.dart` 的 `generateUuid()`，但由调用方提供熵源（宿主注入 CSPRNG 用）。
pub fn generate_uuid_with(rnd: &mut dyn SecureRandom) -> String {
    let mut b = [0u8; 16];
    for byte in b.iter_mut() {
        *byte = rnd.next_byte();
    }
    b[6] = (b[6] & 0x0F) | 0x40;
    b[8] = (b[8] & 0x3F) | 0x80;
    let hex = |i: usize| format!("{:02x}", b[i]);
    format!(
        "{}{}{}{}-{}{}-{}{}-{}{}-{}{}{}{}{}{}",
        hex(0),
        hex(1),
        hex(2),
        hex(3),
        hex(4),
        hex(5),
        hex(6),
        hex(7),
        hex(8),
        hex(9),
        hex(10),
        hex(11),
        hex(12),
        hex(13),
        hex(14),
        hex(15),
    )
}

/// 对应 `id.dart` 的 `generateRequestId(prefix)`：`'$prefix-${generateUuid()}'`。
pub fn generate_request_id(prefix: &str) -> String {
    generate_request_id_with(prefix, &mut OsSecureRandom::new())
}

/// 对应 `id.dart` 的 `generateRequestId(prefix)`，但由调用方提供熵源。
pub fn generate_request_id_with(prefix: &str, rnd: &mut dyn SecureRandom) -> String {
    format!("{}-{}", prefix, generate_uuid_with(rnd))
}

// PORT-NOTE（已由主线收口，2026-10-16）：默认熵源已改为 `getrandom`（OS CSPRNG），
// 不再是 hash 兜底；`getrandom` 已加入 workspace 与 crate 依赖。

// PORT-NOTE: `id_account_test.dart` 的 `Account model` 与 `AccountStore` 两组用例不属于本模块。
// `Account.fromUrl` / `toJson` / `fromJson` 的 id 字段只是调用 `generateUuid()`，
// 模型本身应落在 account 模块（`Account` + `ZemoteConnectionParams.parse`）；
// `AccountStore`（SharedPreferences 持久化、`addUrl`/`rename`/`remove`/`touch`、
// `loaded` 状态、`ChangeNotifier`）是带 I/O 的宿主能力，按 PORTING.md 边界应定义 trait，
// 由 Kotlin 侧实现存储。

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    /// 固定字节的测试熵源：把给定字节按序吐出，用尽后循环。
    struct FixedRandom {
        bytes: Vec<u8>,
        pos: usize,
    }

    impl FixedRandom {
        fn new(bytes: &[u8]) -> Self {
            Self {
                bytes: bytes.to_vec(),
                pos: 0,
            }
        }
    }

    impl SecureRandom for FixedRandom {
        fn next_byte(&mut self) -> u8 {
            let b = self.bytes[self.pos % self.bytes.len()];
            self.pos += 1;
            b
        }
    }

    // 以下 7 条逐条对应 `Reference_code/test/id_account_test.dart` 的 `generateUuid` 组。

    /// 对应 `produces a non-empty string`。
    #[test]
    fn produces_a_non_empty_string() {
        let uuid = generate_uuid();
        assert!(!uuid.is_empty());
    }

    /// 对应 `contains hyphens at expected positions`。
    #[test]
    fn contains_hyphens_at_expected_positions() {
        let uuid = generate_uuid();
        let b = uuid.as_bytes();
        assert_eq!(b[8], b'-');
        assert_eq!(b[13], b'-');
        assert_eq!(b[18], b'-');
        assert_eq!(b[23], b'-');
    }

    /// 对应 `is 36 characters (8-4-4-4-12)`。
    #[test]
    fn is_36_characters() {
        let uuid = generate_uuid();
        assert_eq!(uuid.len(), 36);
    }

    /// 对应 `version nibble is 4`。
    #[test]
    fn version_nibble_is_4() {
        let uuid = generate_uuid();
        assert_eq!(uuid.as_bytes()[14], b'4');
    }

    /// 对应 `variant is one of 8/9/a/b`。
    #[test]
    fn variant_is_one_of_8_9_a_b() {
        let uuid = generate_uuid();
        let c = uuid.as_bytes()[19] as char;
        assert!("89ab".contains(c), "variant nibble was {c:?}");
    }

    /// 对应 `produces unique values`。
    #[test]
    fn produces_unique_values() {
        let mut ids: HashSet<String> = HashSet::new();
        for _ in 0..100 {
            ids.insert(generate_uuid());
        }
        assert_eq!(ids.len(), 100);
    }

    /// 对应 `generateRequestId uses prefix`。
    #[test]
    fn generate_request_id_uses_prefix() {
        let id = generate_request_id("test");
        assert!(id.starts_with("test-"));
        assert!(id.len() > "test-".len() + 20);
    }

    // 以下 3 条是本模块补的确定性用例（不在 Dart 测试里）：把 Dart 那三行位运算
    // 逐行套到固定字节上，期望值由 Python 按同一算法独立算出，用于锁死掩码与格式。

    /// 全 0 字节 → 期望值由 Python 独立算出。
    #[test]
    fn fixed_all_zero_bytes() {
        let mut rnd = FixedRandom::new(&[0u8; 16]);
        assert_eq!(
            generate_uuid_with(&mut rnd),
            "00000000-0000-4000-8000-000000000000"
        );
    }

    /// 全 0xFF 字节 → 期望值由 Python 独立算出。
    #[test]
    fn fixed_all_ff_bytes() {
        let mut rnd = FixedRandom::new(&[0xFFu8; 16]);
        assert_eq!(
            generate_uuid_with(&mut rnd),
            "ffffffff-ffff-4fff-bfff-ffffffffffff"
        );
    }

    /// 字节 0x00..0x0F → 期望值由 Python 独立算出（验证掩码不是简单清零）。
    #[test]
    fn fixed_sequential_bytes() {
        let bytes: Vec<u8> = (0u8..16).collect();
        let mut rnd = FixedRandom::new(&bytes);
        assert_eq!(
            generate_uuid_with(&mut rnd),
            "00010203-0405-4607-8809-0a0b0c0d0e0f"
        );
    }

    /// 前缀注入路径与 `generate_uuid_with` 一致（补的用例，Dart 无对应）。
    #[test]
    fn generate_request_id_with_uses_prefix_and_injected_entropy() {
        let mut rnd = FixedRandom::new(&[0u8; 16]);
        assert_eq!(
            generate_request_id_with("upload", &mut rnd),
            "upload-00000000-0000-4000-8000-000000000000"
        );
    }
}
