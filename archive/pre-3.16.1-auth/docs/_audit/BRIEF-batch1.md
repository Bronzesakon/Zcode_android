# 派单契约 · 第一批：按 3.15.3 源码更新 remote-v4 文档

> 本文件是**所有第一批子代理共用的约束**。子代理派单提示里只写"你负责哪几篇 + 该篇的具体问题"，
> 通用纪律一律以本文件为准。**不要修改本文件。**

---

## 0. 一句话目标

`E:\Zcode_harmony\Docs\` 里的 remote-v4 系列文档，其基线是 **2026-09-20 构建**（主包
`index-B-ilXaCQ.js`）。线上与桌面端都已升到 **3.15.3**。你的任务是：**用 3.15.3 的源码
重新推导你负责的那几篇**，就地覆写成"基于当前构建的一体报告"。

**这不是"加一段修订说明"，是覆写。** 旧构建的偏移、压缩符号名、文件哈希一律作废；
无法在新构建上复核的旧结论，必须显式标注「未复核（旧基线）」或删除，不许留在正文里
假装仍然有效。

---

## 1. 权威基线（唯一真相源）

### 1.1 网页快照（主用）

目录：`E:\Zcode_android\Reference_web\3.15.3\`（**只读，禁止修改**）

| 项 | 值 |
|---|---|
| 抓取完成 | 2026-10-15 19:52 (+08:00) |
| 入口 HTML SHA-256 | `4ca1a8b07de46f2ba698dcfee4c49dc9f76566440f2437146d832ad386d322f2` |
| 主包 | `assets/index-BGa3KiRe.js` · 6,592,643 B · SHA-256 `ce2e2fc490d6364d21630e9f510b99f48748958e392724c0c9faf8fb5f9b88fd` |
| schema/校验模块 | `assets/validation-CqXZOLXO.js` · 307,369 B · SHA-256 `96e64e8f9fc09ca168e0454b534d1be488a5b368ccf868d1dfcef07beadd6c9b` |
| CSS | `assets/index-BD2BL4Cc.css` · 455,351 B · SHA-256 `40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f` |
| 归档规模 | 2,584 文件 · 45,994,698 B |
| 清单 | `3.15.3/_manifest.json`（逐项 URL/引用方/HTTP/大小/SHA-256/Last-Modified）、`_manifest.md` |
| 快照说明 | `Reference_web/README.md` |

**已实测的定位（省你时间，但请自己复核）**：
- `zcode_type` 帧判别联合、`workspace-bridge-open`、`mobile-view-state-update`、`deviceInfo`
  出现在 **`assets/validation-CqXZOLXO.js` 与 `assets/index-BGa3KiRe.js`** 两个文件里。
- 20260920 基线里这些 schema 在 `src-wmk2orCZ.js`（285,095 B）；新构建对应物是 `validation-*.js`，
  但**文件切分变了**，不要假设一一对应。

### 1.2 桌面端（只做交叉校验，不写进你负责的文档）

`C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`（**只读**）
`out/metadata/build-meta.json`：`appVersion 3.15.3`、`buildCommitId d7ca8bee`、`buildTime 2026-10-13T08:36:30Z`。

**桌面端与网页端是同一个 3.15.3 构建**（网页资源 Last-Modified 同为 2026-10-13）。
桌面端的 schema 在 `out/main/chunk-OW6EUS2G.js`、`out/host/chunk-OUCOFHX4.js`，
入站分发器在 `out/main/index.js`（搜 `routePayload`）。

工具（本仓已有，只读）：
```bash
cd E:/Zcode_android
node tools/asar.mjs grep "<regex>" --path-filter "out/main/" --max-hits 8 --context 200
node tools/asar.mjs ls --depth 2
node tools/asar.mjs cat out/metadata/build-meta.json
```

**交叉校验只用于增强你自己的结论可信度**（例如"网页端与桌面端同一份 Zod，故必填约束对两端都成立"）。
第二批子代理会专门做桌面端；你**不要**把桌面端内容写进 `Zcode_harmony/Docs/` 的文档里，
最多在证据行里加一句"桌面端同构，见 app.asar out/main/index.js"。

---

## 2. 读法纪律（硬性）

主包 6.6MB **单行压缩**，schema 模块 307KB 也是压缩产物。**绝不整读。**

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets

# ① 关键字 → 字节偏移（必须用 Buffer.indexOf；Node 字符串 indexOf 是 UTF-16 索引，中文前缀会错位）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');console.log(b.indexOf('workspaceIdentity'))"

# ② 取窗口（定位后只看局部）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');const i=b.indexOf('workspaceIdentity');process.stdout.write(b.subarray(i-500,i+1500).toString())"

# ③ 一个文件里的全部命中偏移
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');const s=b.toString();let i=0;const k='workspaceIdentity';while((i=s.indexOf(k,i))>=0){console.log(i);i+=k.length}"

# ④ GNU grep 的 -b 也给字节偏移，适合批量
grep -b -o 'zcode_type' validation-CqXZOLXO.js | head -30
```

**纪律**：
- 每个结论必须给出**可复现坐标**：文件名 + 字节偏移 + 你截取的原文片段（压缩代码片段可以短，
  但必须是原文，不许转述成"大概是"）。
- 压缩符号名（`S`/`je`/`yk`/`e.object`）**必须连上下文一起给**，因为同名符号在不同 chunk 里含义不同。
- 分不清就写「未能证实」，**不要猜**。猜测必须显式标 `⚠️ 推测`。

---

## 3. 输出纪律（硬性）

1. **就地覆写**你负责的文档（`E:\Zcode_harmony\Docs\<原文件名>`）。保持原编号与原文件名不变。
2. 文首必须有「**审计基线**」块，格式：
   ```
   > **审计基线**：3.15.3（2026-10-15 快照）
   > 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
   > schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
   > 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
   ```
3. **中文书写**。表格化。所有数字带单位与来源。
4. 用**覆写**而不是追加：不留「修订说明」「旧→新对照」这类叠写（除非该篇原结构就是这样，
   那就保持原结构但内容换成新构建）。
5. **写完必须回一个 ≤15 行的摘要**给父对话（父对话看不到你的过程）：
   改了哪几篇 / 最关键的 3–5 条新结论（带坐标）/ 哪些旧结论被推翻 / 哪些仍未证实。
   **完整报告写进文档文件，不要把长正文回给父对话。**
6. 禁止把密钥、口令、token、设备凭据写入任何文件（本批不需要这类数据）。

---

## 4. 禁区

- **只读**：`Reference_web/`、`app.asar`、`Reference_code/` 一律不改不删。
- `Zcode_harmony/Docs/` 被 gitignore（`.gitignore` 内容为 `*`）——**它不入 Git**，这是既有约定，别去改。
- `Reference_web/` 在本仓也被 gitignore（本项目 `.gitignore` 第 53 行）。保持现状。
- 不要 `git add` / `git commit` / 不要动本项目的 `rust/`、`android/` 代码。
- 不要执行抓下来的 JS（只做静态阅读）。不要联网重新抓快照（快照已冻结；如需联网检索官方文档，
  可以，但要标注来源与日期）。

---

## 5. 术语与既有约定

- 「壳」= Android/鸿蒙的宿主应用（本项目是原生 Kotlin，不再是 WebView 壳）；「网页」= `zcode.z.ai/remote/v4`
  的移动端远控客户端；「桌面端」= 被远控的 ZCode 桌面应用。
- `zcode_type` = relay 业务帧的类型判别字段（文档 16 列了全集）。
- 既有文档互相引用用相对链接（`[14-…](14-remote-v4-bootstrap载荷-完整字段表.md)`），保持这个风格。
- 文档 27（20260920 快照）与 `_archive-20260920-旧构建网页审计/` 是**历史证据**，不要改、不要删。
