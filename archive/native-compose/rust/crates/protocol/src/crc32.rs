//! 对应 `Reference_code/lib/protocol/crc32.dart`。
//!
//! IEEE CRC-32（多项式/表语义与网页端 `Bf` 相同）。`rpc_transport.dart` 用它给
//! 分片后的物理帧算 `checksum.value`（`{'algorithm': 'crc32', 'value': ...}`），
//! 因此**输出必须是 8 位小写十六进制字符串**，一个字节都不能漂。

/// 对应 Dart `class Crc32`。
pub struct Crc32;

impl Crc32 {
    /// 对应 Dart `Crc32._table`（由 `Crc32._buildTable()` 生成）。
    ///
    /// Dart 侧是惰性 `static final`；Rust 侧改为编译期 `const` 求值，
    /// 表内容逐字节等价（同一个多项式 `0xEDB88320`、同样的 8 轮右移）。
    const TABLE: [u32; 256] = Self::build_table();

    /// 对应 Dart `Crc32._buildTable`。
    const fn build_table() -> [u32; 256] {
        let mut table = [0u32; 256];
        let mut i = 0usize;
        while i < 256 {
            // Dart: var c = i;（i < 256，故 c 始终非负，`>>` 为逻辑右移）
            let mut c = i as u32;
            let mut k = 0;
            while k < 8 {
                c = if (c & 1) != 0 {
                    0xEDB88320 ^ (c >> 1)
                } else {
                    c >> 1
                };
                k += 1;
            }
            // Dart: table[i] = c & 0xFFFFFFFF;（u32 天然等价的掩码）
            table[i] = c;
            i += 1;
        }
        table
    }

    /// 对应 Dart `Crc32.compute`。
    ///
    /// 返回**无符号** 32 位值，等价于 Dart 末尾的 `& 0xFFFFFFFF`
    /// （Dart 的 `int` 是 64 位有符号，故必须显式掩码；Rust 用 `u32` 后不需要）。
    pub fn compute(bytes: &[u8]) -> u32 {
        let mut crc = 0xFFFF_FFFFu32;
        for &b in bytes {
            crc = Self::TABLE[((crc ^ b as u32) & 0xFF) as usize] ^ (crc >> 8);
        }
        (crc ^ 0xFFFF_FFFF) & 0xFFFF_FFFF
    }

    /// 对应 Dart `Crc32.hexOf`。
    ///
    /// Dart 的 `toRadixString(16).padLeft(8, '0')` 产出**小写**、左侧补零、恰好 8 字符
    /// 的十六进制（`compute` 结果 ≤ `0xFFFFFFFF`，故不会超过 8 位）。
    /// `Reference_code/test/widget_test.dart` 断言的 `'cbf43926'` 即小写形式。
    pub fn hex_of(bytes: &[u8]) -> String {
        format!("{:08x}", Self::compute(bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::Crc32;

    // 说明：下列期望值（除标注"既有测试"的以外）由本机 Python `zlib.crc32(x) & 0xFFFFFFFF`
    // 独立算出；已另行用 Python 复现 Dart 的表算法并逐例比对 zlib，结果完全一致
    // （Dart 的 `_buildTable`/`compute` 与 IEEE CRC-32 / zlib 同族）。

    /// 既有测试：`Reference_code/test/widget_test.dart` 的 `'crc32 known vector'`。
    #[test]
    fn crc32_known_vector() {
        assert_eq!(Crc32::hex_of(b"123456789"), "cbf43926");
        assert_eq!(Crc32::compute(b"123456789"), 0xCBF4_3926);
    }

    /// 期望值由 Python `zlib.crc32(b'')` 独立算出：`0x00000000`。
    #[test]
    fn empty_input_is_zero() {
        assert_eq!(Crc32::compute(&[]), 0x0000_0000);
        assert_eq!(Crc32::hex_of(&[]), "00000000");
    }

    /// 期望值由 Python `zlib.crc32(bytes([0,0,0,0]))` 独立算出：`0x2144DF1C`。
    /// 注：全零输入非零——CRC-32 的初值/终值异或决定了这一点。
    #[test]
    fn all_zero_bytes() {
        assert_eq!(Crc32::compute(&[0x00, 0x00, 0x00, 0x00]), 0x2144_DF1C);
        assert_eq!(Crc32::hex_of(&[0x00, 0x00, 0x00, 0x00]), "2144df1c");
    }

    /// 期望值由 Python `zlib.crc32(bytes([0xFF,0xFF,0xFF,0xFF]))` 独立算出：`0xFFFFFFFF`。
    /// 该值覆盖"终值异或后仍为全 1"的边界（`hex_of` 必须输出 8 个 f）。
    #[test]
    fn all_ff_bytes() {
        assert_eq!(Crc32::compute(&[0xFF, 0xFF, 0xFF, 0xFF]), 0xFFFF_FFFF);
        assert_eq!(Crc32::hex_of(&[0xFF, 0xFF, 0xFF, 0xFF]), "ffffffff");
    }

    /// 期望值由 Python `zlib.crc32` 独立算出：
    /// `bytes([0x00])` → `0xD202EF8D`；`bytes([0xFF])` → `0xFF000000`。
    /// 单字节 0x00 / 0xFF 分别压住查表下标 0 与 255 的边界。
    #[test]
    fn single_byte_bounds() {
        assert_eq!(Crc32::compute(&[0x00]), 0xD202_EF8D);
        assert_eq!(Crc32::compute(&[0xFF]), 0xFF00_0000);
        assert_eq!(Crc32::hex_of(&[0x00]), "d202ef8d");
        assert_eq!(Crc32::hex_of(&[0xFF]), "ff000000");
    }

    /// 期望值由 Python `zlib.crc32(bytes([0,255,0,255,0x10,0x7F,0x80,0x01]))` 独立算出：
    /// `0x7E4082FA`。混入高位字节，验证 `b as u32` 不产生符号扩展。
    #[test]
    fn mixed_00_ff_bytes() {
        let input = [0x00u8, 0xFF, 0x00, 0xFF, 0x10, 0x7F, 0x80, 0x01];
        assert_eq!(Crc32::compute(&input), 0x7E40_82FA);
        assert_eq!(Crc32::hex_of(&input), "7e4082fa");
    }

    /// 期望值由 Python `zlib.crc32(bytes(range(256)))` 独立算出：`0x29058C73`。
    /// 较长输入（256 字节，覆盖 0x00–0xFF 全部字节值）。
    #[test]
    fn long_input_0_to_255() {
        let input: Vec<u8> = (0u32..256).map(|v| v as u8).collect();
        assert_eq!(Crc32::compute(&input), 0x2905_8C73);
        assert_eq!(Crc32::hex_of(&input), "29058c73");
    }

    /// 期望值由 Python 独立算出：62 字节 ASCII
    /// `b'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'` → `0x21F8FA32`。
    #[test]
    fn long_ascii_input() {
        let input = b"abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        assert_eq!(Crc32::compute(input), 0x21F8_FA32);
        assert_eq!(Crc32::hex_of(input), "21f8fa32");
    }

    /// 期望值由 Python `zlib.crc32` 独立算出：`[1,2,3]` → `0x55BC801D`，`[7,8,9]` → `0x4B0BFD3B`。
    /// 这两个载荷来自既有测试 `Reference_code/test/rpc_transport_test.dart`
    /// （它用 `'00000000'` 作为 `[1,2,3]` 的错误校验和来测拒绝路径，
    /// 并用 `Crc32.hexOf([7,8,9])` 构造合法帧——故此处固定真实值，供 `rpc_transport` 移植对照）。
    #[test]
    fn rpc_transport_payload_vectors() {
        assert_eq!(Crc32::hex_of(&[1, 2, 3]), "55bc801d");
        assert_eq!(Crc32::hex_of(&[7, 8, 9]), "4b0bfd3b");
    }

    /// `hex_of` 恒为 8 位小写十六进制（对齐 Dart `padLeft(8, '0')`），
    /// 否则 `rpc_transport` 里的 `checksum.value` 字符串比对会失败。
    #[test]
    fn hex_of_is_always_8_lowercase_chars() {
        let cases: [&[u8]; 5] = [
            &[],
            &[0x00],
            &[0xFF],
            b"123456789",
            &[0x00, 0x00, 0x00, 0x00],
        ];
        for case in cases {
            let hex = Crc32::hex_of(case);
            assert_eq!(hex.len(), 8, "输入 {case:?} 的 hex 长度不是 8：{hex}");
            assert!(
                hex.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)),
                "输入 {case:?} 的 hex 含非小写十六进制字符：{hex}"
            );
        }
    }
}
