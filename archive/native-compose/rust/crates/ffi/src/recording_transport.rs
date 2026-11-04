//! 记录型 transport —— FFI 层的核心设计（契约 `docs/FFI.md` §1.1）。
//!
//! 协议 crate 的 [`RelayTransport`] 是个**回调 trait**（`connect` / `send_text` / `close`），
//! 按常理该由宿主（Kotlin）实现。我们不这么做：Kotlin 侧不实现任何 Rust trait，只处理事件、
//! 喂回输入。于是 Rust 侧提供一个**不碰网络**的实现，把这三个动作按调用顺序**记下来**，
//! 由 [`crate::session::Session::step`] 取空后按 §7.1 序列化成事件交给宿主。
//!
//! ## 顺序保证
//!
//! `RelayClient` 在 `step` 内部**同步**调用这三个方法，本实现只是把它们 `push` 进一个
//! `Vec`。因此：
//!
//! 1. 队列内的先后就是核心调用的先后（例如 `do_connect` 里先 `close(旧代次)` 再
//!    `connect(新代次)`，以及 `connect` 之后紧随的 `send_text(auth_init)`）；
//! 2. `take_actions` 是 `mem::take`（整体取空），不会打乱、不会丢；
//! 3. 同一代次内的动作也严格按序 —— 宿主**必须按数组顺序执行**，不能并发。
//!
//! ## 为什么是 `Arc<Mutex<Vec<_>>>` 而不是 `Vec<_>`
//!
//! `ZemoteClient` 以 `Box<dyn RelayTransport>` 独占持有 transport，而 `Session` 需要在这之后
//! 把记录读出来。共享一个队列是最短路径（无需在门面上开新 API）。所有访问都在
//! 「同一 handle 自己的锁内」（§11），锁不会真正竞争；中毒（某次调用 panic）用
//! `PoisonError::into_inner()` 恢复，不让会话永久不可用。

use std::sync::{Arc, Mutex, PoisonError};

use zemote_protocol::relay_client::RelayTransport;

/// 一次需要宿主执行的传输动作。逐条对应 `RelayTransport` 的三个方法。
///
/// 字段与 [`RelayTransport`] 的入参逐字相同（`generation` / `uri` / `text` / `code` / `reason`），
/// 序列化见契约 §7.1。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// 对应 [`RelayTransport::connect`]。
    Connect {
        /// 连接代次（socket 句柄）。
        generation: u64,
        /// 目标地址（`wss://...`）。
        uri: String,
    },
    /// 对应 [`RelayTransport::send_text`]。`text` 已是紧凑 JSON，宿主直接发。
    SendText {
        /// 该帧所属连接的代次。
        generation: u64,
        /// 完整 JSON 文本。
        text: String,
    },
    /// 对应 [`RelayTransport::close`]。
    Close {
        /// 要关闭的连接代次。
        generation: u64,
        /// 关闭码；`None` 表示 Dart 的无参 `sink.close()`。
        code: Option<i32>,
        /// 关闭原因；`None` 表示未给出。
        reason: Option<String>,
    },
}

/// 记录型 [`RelayTransport`]：只记录，不做任何 I/O。
#[derive(Debug, Clone, Default)]
pub struct RecordingTransport {
    /// 动作队列。`Arc` 让 `Session` 能在 `ZemoteClient` 独占持有 transport 后仍读到记录。
    actions: Arc<Mutex<Vec<Action>>>,
}

impl RecordingTransport {
    /// 新建一个空队列。
    pub fn new() -> Self {
        Self::default()
    }

    /// 取空队列（整体移出），顺序即核心调用顺序。
    ///
    /// 锁中毒时用 `PoisonError::into_inner()` 恢复：中毒只说明上一次调用 panic 过，队列里的
    /// 动作本身仍然有效，丢弃它们只会让宿主少执行几个动作（更难排查）。
    pub fn take_actions(&self) -> Vec<Action> {
        let mut guard = self.actions.lock().unwrap_or_else(PoisonError::into_inner);
        std::mem::take(&mut *guard)
    }

    /// 队列是否为空（诊断/测试用；不改变队列）。
    pub fn is_empty(&self) -> bool {
        self.actions
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .is_empty()
    }

    fn push(&self, action: Action) {
        self.actions
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(action);
    }
}

impl RelayTransport for RecordingTransport {
    fn connect(&mut self, generation: u64, uri: &str) {
        self.push(Action::Connect {
            generation,
            uri: uri.to_string(),
        });
    }

    fn send_text(&mut self, generation: u64, text: &str) {
        self.push(Action::SendText {
            generation,
            text: text.to_string(),
        });
    }

    fn close(&mut self, generation: u64, code: Option<i32>, reason: Option<&str>) {
        self.push(Action::Close {
            generation,
            code,
            reason: reason.map(str::to_string),
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 三个方法各记一条，且 `&mut self` 的方法直接落到内部队列上。
    #[test]
    fn records_every_method_in_call_order() {
        let transport = RecordingTransport::new();
        let mut writer = transport.clone();
        writer.connect(1, "wss://zcode.z.ai/ws");
        writer.send_text(1, "{\"type\":\"auth_init\"}");
        writer.close(1, Some(3000), Some("debug-drop"));

        assert_eq!(
            transport.take_actions(),
            vec![
                Action::Connect {
                    generation: 1,
                    uri: "wss://zcode.z.ai/ws".to_string(),
                },
                Action::SendText {
                    generation: 1,
                    text: "{\"type\":\"auth_init\"}".to_string(),
                },
                Action::Close {
                    generation: 1,
                    code: Some(3000),
                    reason: Some("debug-drop".to_string()),
                },
            ]
        );
    }

    /// `take_actions` 是「取空」而不是「拷贝」：第二次调用必须为空。
    #[test]
    fn take_actions_drains_the_queue() {
        let transport = RecordingTransport::new();
        transport.push(Action::Connect {
            generation: 7,
            uri: "wss://a/ws".to_string(),
        });
        assert_eq!(transport.take_actions().len(), 1);
        assert!(transport.take_actions().is_empty());
        assert!(transport.is_empty());
    }

    /// 无参 `close()` 的两个 `None` 必须原样保留（不是空串、不是 1000）。
    #[test]
    fn close_without_code_or_reason_keeps_both_none() {
        let transport = RecordingTransport::new();
        let mut writer = transport.clone();
        writer.close(3, None, None);
        assert_eq!(
            transport.take_actions(),
            vec![Action::Close {
                generation: 3,
                code: None,
                reason: None,
            }]
        );
    }

    /// 多个代次交错时顺序不得被打乱（重连路径：close(1) → connect(2) → send_text(2)）。
    #[test]
    fn interleaved_generations_keep_core_call_order() {
        let transport = RecordingTransport::new();
        let mut writer = transport.clone();
        writer.close(1, None, None);
        writer.connect(2, "wss://zcode.z.ai/ws");
        writer.send_text(2, "{\"type\":\"auth_init\"}");
        let actions = transport.take_actions();
        assert_eq!(actions[0], Action::Close { generation: 1, code: None, reason: None });
        assert_eq!(actions[1], Action::Connect { generation: 2, uri: "wss://zcode.z.ai/ws".to_string() });
        assert_eq!(actions[2], Action::SendText { generation: 2, text: "{\"type\":\"auth_init\"}".to_string() });
    }
}
