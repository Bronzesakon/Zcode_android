# 01 · 网页前端逆向 / 样式提取 / 设计令牌 / Web→Compose：开源 Skills 与工具调研

> 调研日期：2026-11-03（GitHub API 实时抓取，star / 提交时间为当日快照）
> 范围：Claude / Codex / 通用 Agent 的 UI 逆向与设计系统抽取 Skill、JS 反混淆与打包还原、设计令牌流水线、浏览器 DOM/CSS 自动提取、Compose 侧转换、移动端远程 Agent。
> 结论只保留高价值项；全量 60 组仓库查询 + 4 组代码查询见 §1.2。
> 本文件为只读调研交付物，未改动仓库任何代码。

---

## 0. 结论（先看这里）

| # | 结论 | 依据 |
|---|---|---|
| 1 | **样式提取的第一选择是 `ChromeDevTools/chrome-devtools-mcp`**，它有一个专门的 `get_css_styles` 工具，能直接返回“匹配到的 CSS 规则 + 内联样式 + 继承样式 + 自定义属性 + `@layer/@media/@container/@scope` 级联包裹”，并标注胜出/被覆盖的声明 | 官方 tool-reference（§A1） |
| 2 | **JS 侧用 `webcrack` + `wakaru` 双件套**：webcrack 管 obfuscator.io / webpack / browserify / unminify；wakaru 补 esbuild/Bun、Metro、现代前端拆包，且自带 `SKILL.md` 可直接给 Agent 用 | §A3、§A4 |
| 3 | **不存在成熟的 “CSS → Jetpack Compose” 直接转换器**。能落地的是“令牌化 → Style Dictionary `compose/object`”这条链；FigmaToCompose 已停更 4 年且无许可证 | §A5、§C |
| 4 | **许可证陷阱必须前置检查**：多个热门 Skill 仓库 README 写 MIT，但仓库内没有 LICENSE 文件（API 返回 `NONE`），法律上属“保留所有权利”，不能复制其代码/文本 | §1.4 |
| 5 | **本任务最小可行组合**：`chrome-devtools-mcp`（采集）→ `webcrack`/`wakaru`（还原）→ 自建规范化脚本 → `style-dictionary` 的 `compose/object`（产出 `ZemoteTokens.kt`） | §6 |

一句话：**不要找“一键 Web→Compose”，把问题拆成“证据采集 / 代码还原 / 令牌归一 / Compose 生成”四层，每层都有现成件，只有第三层需要自己写薄薄一层。**

---

## 1. 调研方法与查询记录

### 1.1 执行环境

| 项 | 值 |
|---|---|
| 仓库 | `E:/Zcode_android` |
| `gh` 版本 | 2.100.0 (2026-09-03) |
| 账号 | `Bronzesakon`（scopes: gist, read:org, repo, workflow） |
| 查询方式 | `gh search repos` / `gh search code` / `gh api repos/{owner}/{repo}` |
| 验证内容 | 仓库是否存在、star、license（`spdxId`）、`isArchived`、默认分支最近一次提交的日期与 message、LICENSE 文件是否真实存在 |

### 1.2 查询矩阵（60 组仓库查询 + 4 组代码查询）

`gh search repos "<q>" --match name,description,readme --limit 25`，`命中` 列为非空结果数。

| # | 查询 | 命中 | # | 查询 | 命中 |
|---|---|---|---|---|---|
| q01 | web reverse engineering | 25 | q31 | figma to compose（二次） | 25 |
| q02 | design tokens extraction | 25 | q32 | design to code compose | 1 |
| q03 | css to tailwind | 25 | q33 | css snapshot extract | 1 |
| q04 | webcrack | 25 | q34 | dom snapshot style | 2 |
| q05 | deobfuscator javascript | 23 | q35 | happy coder claude | 8 |
| q06 | source map recovery | 25 | q36 | omnara | 25 |
| q07 | sourcemap extract | 25 | q37 | vibetunnel | 25 |
| q08 | computed style extract | 25 | q38 | claude code mobile client | 5 |
| q09 | playwright style extractor | 0 | q39 | cssused | 17 |
| q10 | css variables extractor | 25 | q40 | extract css from webpage | 0 |
| q11 | html to jetpack compose | 0 | q41 | design tokens from website | 12 |
| q12 | web to compose | 8 | q42 | relay figma compose | 0 |
| q13 | figma to compose | 0 | q43 | screenshot to code | 25 |
| q14 | design system extractor | 0 | q44 | agent skill android compose | 2 |
| q15 | claude code skill design | 25 | q45 | singlefile save page | 6 |
| q16 | website clone skill | 25 | q46 | ui reverse engineer figma | 0 |
| q17 | design tokens style dictionary | 25 | q47 | style dictionary | 25 |
| q18 | style dictionary design tokens | 25 | q48 | chrome devtools mcp | 25 |
| q19 | css to flutter | 22 | q49 | playwright mcp | 25 |
| q20 | tailwind to flutter | 3 | q50 | browser mcp agent | 25 |
| q21 | html to compose kotlin | 0 | q51 | compose design tokens | 25 |
| q22 | webpack unpack | 17 | q52 | html css to android | 0 |
| q23 | unminify javascript | 25 | q53 | unocss extractor | 17 |
| q24 | design token pipeline | 25 | q54 | design system skill | 25 |
| q25 | dom to react component | 1 | q55 | tailwind compose | 20 |
| q26 | figma tokens | 25 | q56 | design token android | 20 |
| q27 | css audit | 25 | q57 | compose html renderer | 6 |
| q28 | codex cli mobile | 10 | q58 | figma variables code | 17 |
| q29 | claude code remote mobile | 1 | q59 | css to react native | 20 |
| q30 | agent ui reverse engineering | 0 | q60 | web scraper design system | 2 |

代码查询（`gh search code`）：

| # | 查询 | 命中 | 价值 |
|---|---|---|---|
| c01 | `filename:SKILL.md "design tokens"` | 20 | 找到 `design-md-extractor`、`design-agent-skills`、`podo/design-agent-skills` 等 |
| c02 | `filename:SKILL.md "reverse engineer"` | 20 | 找到 `podo/design-agent-skills` 的 `extract-design-md`、`julianoczkowski/designer-skills` |
| c03 | `filename:SKILL.md webcrack` | 20 | 确认 webcrack 已被多个逆向 Skill 标准化调用 |
| c04 | `"computed style" "getComputedStyle" --language TypeScript` | 20 | 说明“computed style 导出”多数是各项目内部工具，无通用开源产品 |

> 空结果的 10 组（q09/q11/q13/q14/q21/q30/q40/q42/q46/q52）本身是结论：**“HTML/CSS → Compose”与“Playwright 样式导出”没有成熟独立项目**，只有零散小仓库或项目内脚本。

### 1.3 验证口径

每个入围仓库至少核验：

```bash
gh api repos/<owner>/<repo> --jq '[.full_name,.stargazers_count,(.license.spdx_id//"NONE"),.archived,.pushed_at,.default_branch,.description]|@tsv'
gh api "repos/<owner>/<repo>/commits?per_page=1" --jq '.[0]|[(.commit.committer.date),(.commit.message|split("\n")[0])]|@tsv'
gh api "repos/<owner>/<repo>/contents/LICENSE"     # 404 = 无许可证文件
```

维护状态判定：**活跃** = 90 天内有提交；**低频** = 90–365 天；**停更** = 超过 365 天。

### 1.4 重要发现：许可证陷阱（直接影响能否采用）

| 仓库 | star | GitHub API license | LICENSE 文件 | README 声称 | 结论 |
|---|---:|---|---|---|---|
| `senlindesign/taste-skill` | 449 | `NONE` | **404 不存在** | 徽章写 “License: MIT” | **不可复制**；README 与仓库事实不符 |
| `Lucent-Snow/style-extractor` | 462 | `NONE` | **404 不存在** | 未声明 | **不可复制**，只能读方法论 |
| `gatsby-sec/web-reverse-skills` | 20 | `NONE` | **404 不存在** | 文末写 “License MIT” | **不可复制** |
| `caio-overmind-ventures/designkit` | 31 | `NONE` | 无 | 未声明 | 仅参考 |
| `47thtechcorner/RayCodes_Rea-AI-Agent-Setup` | 12 | `NONE` | 无 | 未声明 | 仅参考 |
| `per-simmons/clone-app-pat-pro-public` | 264 | `NONE` | 无 | 未声明 | 仅参考 |
| `aldefy/compose-skill` | 600 | `NOASSERTION` | 存在，正文是 **MIT** | MIT | 可用（API 未识别，人工确认为 MIT） |
| `librae8226/nexus4cc` | 134 | `NOASSERTION` | **404 不存在** | 未声明 | 不可复制，仅参考 |

> 规则：**没有 LICENSE 文件 = 默认保留所有权利**。可以“读它怎么写流程”，不能把 `SKILL.md` / 脚本复制进本仓库或产品。

---

## 2. 分级总表

### 立即可用（A 级）

| # | 仓库 | star | License | 最近提交 | 维护 | 用途 | 集成成本 |
|---|---|---:|---|---|---|---|---|
| A1 | [ChromeDevTools/chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp) | 54,718 | Apache-2.0 | 2026-11-03 | 活跃 | 浏览器取证：CSS 规则/级联、网络、脚本、性能 | 0.5 人日 |
| A2 | [microsoft/playwright-mcp](https://github.com/microsoft/playwright-mcp) | 39,122 | Apache-2.0 | 2026-11-01 | 活跃 | 登录态/多页/表单场景的浏览器自动化 | 0.5 人日 |
| A3 | [j4k0xb/webcrack](https://github.com/j4k0xb/webcrack) | 3,015 | MIT | 2026-07-26 | 低频 | obfuscator.io / webpack / browserify / unminify | 2 小时 |
| A4 | [pionxzh/wakaru](https://github.com/pionxzh/wakaru) | 1,076 | Apache-2.0 | 2026-11-02 | 活跃 | esbuild/Bun、Metro、现代前端拆包；自带 SKILL.md | 2 小时 |
| A5 | [style-dictionary/style-dictionary](https://github.com/style-dictionary/style-dictionary) | 4,875 | Apache-2.0 | 2026-10-28 | 活跃 | 令牌 → Compose `object`（`compose/object`） | 0.5–1 人日 |
| A6 | [jpoindexter/design-md-extractor](https://github.com/jpoindexter/design-md-extractor) | 68 | MIT | 2026-06-07 | 低频 | 单站全量抽取 → DESIGN.md + tokens + Tailwind + 提示词 | 1 人日 |
| A7 | [arvindrk/extract-design-system](https://github.com/arvindrk/extract-design-system) | 269 | MIT | 2026-06-19 | 低频 | CLI/Skill 双形态的“起步令牌”抽取 | 2 小时 |

### 需改造（B 级）

| # | 仓库 | star | License | 最近提交 | 维护 | 为什么不是 A |
|---|---|---:|---|---|---|---|
| B1 | [Lucent-Snow/style-extractor](https://github.com/Lucent-Snow/style-extractor) | 462 | **NONE** | 2026-03-13 | 低频 | 流程与本任务最贴合，但**无许可证**；Windows 路径写死；需 chrome-devtools-mcp |
| B2 | [senlindesign/taste-skill](https://github.com/senlindesign/taste-skill) | 449 | **NONE** | 2026-07-07 | 低频 | 有“设计品味/DNA”方法论，但无 LICENSE；依赖 Playwright MCP |
| B3 | [gatsby-sec/web-reverse-skills](https://github.com/gatsby-sec/web-reverse-skills) | 20 | **NONE** | 2026-04-13 | 低频 | 中文五件套分类好，但无 LICENSE、内容偏浅 |
| B4 | [INSANE0777/Awwwards-mcp](https://github.com/INSANE0777/Awwwards-mcp) | 405 | MIT | 2026-11-03 | 活跃 | 能力很强（设计 DNA/动效/打包取证），但抓 Awwwards 有 ToS/反爬风险，产物是 Vite/Astro/Next |
| B5 | [batteryshark/rekit](https://github.com/batteryshark/rekit) | 20 | Apache-2.0 | 2026-07-27 | 低频 | 统一调度器 + js-deobfuscate，能力对但偏重（镜像/容器） |
| B6 | [aldefy/compose-skill](https://github.com/aldefy/compose-skill) | 600 | MIT（LICENSE 文件为准） | 2026-07-08 | 低频 | 是 Compose **编码正确性** skill，不做网页提取；适合作为落地端护栏 |
| B7 | [Fausto-404/js-reverse-automation--skill](https://github.com/Fausto-404/js-reverse-automation--skill) | 606 | Apache-2.0 | 2026-10-10 | 活跃 | chrome-devtools-mcp + JSRPC/autoDecoder，偏国内签名逆向 |
| B8 | [dominikmartn/hue](https://github.com/dominikmartn/hue) | 863 | MIT | 2026-06-11 | 低频 | “品牌 → 设计系统 skill”，偏生成而非证据提取 |
| B9 | [podo/design-agent-skills](https://github.com/podo/design-agent-skills) | 30 | MIT | 2026-10-27 | 活跃 | 164 个 skill 的目录，含 `extract-design-md`；按需装，不是单一工具 |
| B10 | [chentianxiong123/webclone-skill](https://github.com/chentianxiong123/webclone-skill) | 1 | MIT | 2026-10-06 | 活跃 | DOM→Vue 流水线 + 12 个 JS 抽取脚本，脚本清单有参考价值 |
| B11 | [bernaferrari/FigmaToCode](https://github.com/bernaferrari/FigmaToCode) | 5,262 | GPL-3.0 | 2026-08-05 | 低频 | 支持 HTML/Tailwind/Flutter/SwiftUI，**不支持 Compose**；GPL 传染 |

### 仅参考（C 级，只读方法论 / 不引入依赖）

| 仓库 | star | License | 最近提交 | 定位 |
|---|---:|---|---|---|
| [jehna/humanify](https://github.com/jehna/humanify) | 3,293 | MIT | 2026-07-29 | LLM 重命名变量；成本高，仅在 webcrack 后仍不可读时用 |
| [kuizuo/js-deobfuscator](https://github.com/kuizuo/js-deobfuscator) | 1,194 | MIT | 2026-08-11 | Babel AST 自动化反混淆，能力与 webcrack 重叠 |
| [echo094/decode-js](https://github.com/echo094/decode-js) | 1,069 | GPL-3.0 | 2026-10-25 | AST 分析强，但 GPL-3.0 + 中文文档，集成需隔离 |
| [rarecoil/unwebpack-sourcemap](https://github.com/rarecoil/unwebpack-sourcemap) | 544 | MIT | 2022-04-16 | 从 source map 还原 SPA；停更 |
| [akx/source-from-sourcemaps](https://github.com/akx/source-from-sourcemaps) | 7 | MIT | 2024-11-04 | 小工具，source map 还原 |
| [CaelumF/FigmaToCompose](https://github.com/CaelumF/FigmaToCompose) | 451 | **NONE** | 2022-08-28 | Figma→Compose 插件；停更、无许可证 |
| [manish026/figma-to-compose-mcp](https://github.com/manish026/figma-to-compose-mcp) | 3 | Apache-2.0 | 2025-06-25 | Figma→Compose 的 MCP 尝试；玩具级 |
| [greekr4/rever-browser](https://github.com/greekr4/rever-browser) | 49 | MIT | 2026-11-03 | 面向逆向的 Agent 浏览器 |
| [StoneSteel27/AutomatiQ](https://github.com/StoneSteel27/AutomatiQ) | 178 | MIT | 2026-10-27 | “简化 Web 逆向”的 Agent 工具 |
| [julianoczkowski/designer-skills](https://github.com/julianoczkowski/designer-skills) | 598 | Apache-2.0 | 2026-07-06 | 设计流程 skill 集，不含提取器 |
| [addyosmani/web-quality-skills](https://github.com/addyosmani/web-quality-skills) | 3,029 | MIT | 2026-08-24 | Lighthouse/CWV 审计 skill |
| [bergside/awesome-design-skills](https://github.com/bergside/awesome-design-skills) | 3,463 | MIT | 2026-06-28 | 67 个 DESIGN.md/SKILL.md 索引 |
| [seungdori/web-stylebook-mcp](https://github.com/seungdori/web-stylebook-mcp) | 76 | MIT | 2026-11-03 | 离线给“设计方向 + WCAG 令牌”，偏生成 |
| [styled-components/css-to-react-native](https://github.com/styled-components/css-to-react-native) | 1,177 | MIT | 2026-10-29 | CSS→RN 属性映射，可参考“CSS 属性 → 原生样式”的语义表 |
| [ronjunevaldoz/tailwind-compose](https://github.com/ronjunevaldoz/tailwind-compose) | 1 | Apache-2.0 | 2026-07-11 | Tailwind 风格 Compose 工具函数；极小 |
| [argyleink/open-props](https://github.com/argyleink/open-props) | 5,563 | MIT | 2026-10-11 | CSS 变量体系参考（不是提取器） |
| [tokens-studio/figma-plugin](https://github.com/tokens-studio/figma-plugin) | 1,629 | MIT | 2026-10-23 | 有 Figma 源文件时才需要 |
| [tokens-studio/sd-transforms](https://github.com/tokens-studio/sd-transforms) | 251 | MIT | 2025-12-10 | Tokens Studio JSON → Style Dictionary |
| [NulightJens/jensai-skills](https://github.com/NulightJens/jensai-skills) | 16 | MIT | 2026-08-09 | 含 design extraction / brand kit |
| [trilwu/secskills](https://github.com/trilwu/secskills) | 183 | MIT | 2026-08-06 | 含 reversing-obfuscated-javascript |
| [vladmdgolam/agent-skills](https://github.com/vladmdgolam/agent-skills) | 9 | MIT | 2026-10-14 | 含 reverse-engineer-js |
| [SkyNotSilent/true-web-clone](https://github.com/SkyNotSilent/true-web-clone) | 11 | MIT | 2026-07-09 | source-first 克隆，含 WebGL/3D |
| [luigiluft/ditto](https://github.com/luigiluft/ditto) | 4 | MIT | 2026-07-03 | recon→DNA→rebuild→verify 的 skill 流程 |
| [browser-use/browser-harness](https://github.com/browser-use/browser-harness) | 18,837 | MIT | 2026-11-02 | 让 Agent 用真实 Chrome（含登录态） |
| [pasky/chrome-cdp-skill](https://github.com/pasky/chrome-cdp-skill) | 3,286 | MIT | 2026-06-28 | 连现有 Chrome 标签页的轻量 CDP skill |

### 移动端远程 Agent（与本项目“远程移动端”相关的横向参考）

| 仓库 | star | License | 最近提交 | 结论 |
|---|---:|---|---|---|
| [amantus-ai/vibetunnel](https://github.com/amantus-ai/vibetunnel) | 4,706 | MIT | 2026-08-05 | 浏览器变终端，Web 方案；与本项目原生中继不同路线 |
| [omnara-ai/omnara](https://github.com/omnara-ai/omnara) | 3,348 | Apache-2.0 | 2026-11-03 | 自托管 Agent 平台，含移动端；架构可参考 |
| [librae8226/nexus4cc](https://github.com/librae8226/nexus4cc) | 134 | NONE（无 LICENSE） | 2026-10-03 | “Claude Code Everywhere”，Android 端细节可参考 |
| [mjzcng/codex-cli-mobile-pairing](https://github.com/mjzcng/codex-cli-mobile-pairing) | 5 | MIT | 2026-07-15 | 作者明确说：优先用官方 codex remote-control，本仓库只是兼容层 |
| [brunojaime/codex-cli-mobile-bridge](https://github.com/brunojaime/codex-cli-mobile-bridge) | 0 | MIT | 2026-11-02 | Flutter 聊天端控本地 Codex CLI，早期项目 |

> 这一类的共同点：**都在解决“传输 + 会话镜像”，没有解决“Web UI → 原生 UI 的样式还原”**。本项目已有 `RelayWebSocket` / `ZemoteEngine`，此处仅作架构对照，不建议引入。

---

## 3. 立即可用（A 级）详解

### A1. ChromeDevTools/chrome-devtools-mcp —— 本任务的“样式取证相机”

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/ChromeDevTools/chrome-devtools-mcp |
| star | 54,718（2026-11-03） |
| License | Apache-2.0 |
| 最近提交 | 2026-11-03（活跃） |
| 安装 | `npx -y chrome-devtools-mcp@1.12.0` |

**关键能力（官方 tool-reference 原文要点）**

`get_css_styles`：

> Retrieve matched CSS rules, inline styles (element.style), inherited styles, custom properties, and cascade wrappers (`@layer`, `@media`, `@container`, `@scope`) for an element identified by its UID. Rules are ordered from highest to lowest cascade precedence and include source line numbers (e.g. index:196). Active (winning) declarations have no prefix tag, while (losing) overridden declarations are prefixed with `[overloaded]`. Treat the output as authoritative and complete. Results are paginated (10 rules per page by default).

这条直接解决了“网页样式到底哪条生效”的核心问题，比 `getComputedStyle` 更完整：它给的是**规则级证据 + 级联顺序 + 源文件行号**。

其他可用工具（已核实的工具名）：

- `take_snapshot`（拿 uid，`get_css_styles` 的前置）
- `evaluate_script`（批量跑 `getComputedStyle`、导出 `document.styleSheets`）
- `list_network_requests` / `get_network_request`（找 JS/CSS 资源、source map）
- `performance_start_trace` / `performance_stop_trace` / `performance_analyze_insight`
- `take_screenshot`、`emulate`、`resize_page`、`lighthouse_audit`

**配置（MCP 客户端通用）**

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@1.12.0", "--isolated"]
    }
  }
}
```

- `--isolated`：临时用户目录，干净环境；需要登录态时去掉，改用持久 `--user-data-dir`。
- 也可连“已经在跑的 Chrome”，适合复用已登录会话（见 `docs/advanced-usage.md`）。

**能力边界**

- 只能 Chrome/Chromium，不能 Firefox/Safari。
- `get_css_styles` 必须先从 `take_snapshot` 拿 uid，**逐元素**调用且分页（默认 10 条/页），不适合“一次抓 500 个节点”。
- 批量场景仍要 `evaluate_script` 自己遍历 DOM；此时拿不到“被覆盖声明”的级联信息，需接受精度下降。
- 工具名随版本变动（`get_css_styles` 是较新能力），**升级前先跑一次冒烟**。

**对本任务是否可直接用**：**是**。这是最接近“样式证据链”的现成件，建议作为采集层底座。

---

### A2. microsoft/playwright-mcp —— 登录态与多页场景的补充

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/microsoft/playwright-mcp |
| star | 39,122 |
| License | Apache-2.0 |
| 最近提交 | 2026-11-01（活跃） |
| 安装 | `npx -y @playwright/mcp@0.0.84` |

**能力**：`browser_navigate`、`browser_snapshot`（无障碍树）、`browser_evaluate`、`browser_click/fill_form/hover`、`browser_network_requests`、`browser_console_messages`、`browser_cookie_*`、`browser_localstorage_*`、`browser_pdf_save`、`browser_highlight` 等。

**边界**：**没有专用 CSS 规则工具**，样式提取要靠 `browser_evaluate` 写 JS；快照是无障碍树，不是 CSSOM。

**定位**：与 A1 不是替代关系。A1 拿“样式证据”，A2 拿“稳定登录态 + 跨页流程 + 表单交互”。两者可同时挂。

---

### A3. j4k0xb/webcrack —— 打包/混淆还原的主力

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/j4k0xb/webcrack |
| star | 3,015 |
| License | MIT |
| 最近提交 | 2026-07-26（低频，但功能稳定，npm 2.16.0） |
| 要求 | **Node.js 22 或 24**（依赖 `isolated-vm`，奇数版 Node 不保证 ABI） |

```bash
npm install -g webcrack@2.16.0
webcrack input.js
webcrack bundle.js -o output-dir
```

**能做**：obfuscator.io 反混淆、unminify、transpile、webpack/browserify 拆包、自动识别（无需配置）、按作用域安全重命名。

**不能做**：Vite/Rollup 的 chunk 图关系、CSS 提取、运行时补环境。

**结论**：对 `Reference_web/3.17.1/assets` 这类 Vite 产物，先用 webcrack 做 unminify 与字符串还原；如果发现是 esbuild/Metro 风格，转 A4。

---

### A4. pionxzh/wakaru —— 现代前端拆包 + 自带 Skill

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/pionxzh/wakaru |
| star | 1,076 |
| License | Apache-2.0 |
| 最近提交 | 2026-11-02（活跃，`@wakaru/cli` 1.16.0） |
| 自带 | `skills/wakaru/SKILL.md`（可直接给 Claude/Codex 用） |

```bash
npm install -g @wakaru/cli@1.16.0
wakaru input.js -o output.js
wakaru bundle.js --unpack -o out/
wakaru dist/ --unpack -o out/
```

**覆盖**：webpack、**esbuild/Bun**、Browserify、**Metro**；输出可读模块并恢复现代语法。

**边界**：不处理 CSS；不做运行时环境补全；对强 VM/控制流平坦化仍需人工。

**为什么值得并列**：webcrack 强在 obfuscator.io 与 webpack，wakaru 强在 esbuild/Metro 与现代语法还原，二者互补；且 wakaru 附带的 `SKILL.md` 是可复用的 Agent 操作手册（Apache-2.0，可改写）。

---

### A5. style-dictionary —— 令牌到 Compose 的唯一成熟桥

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/style-dictionary/style-dictionary |
| star | 4,875 |
| License | Apache-2.0 |
| 最近提交 | 2026-10-28（活跃，v5.7.1） |
| 关键 | 内置 `compose/object` 格式 + `compose` transform group |

已核实源码：

- `lib/common/formats.js` 导入 `./templates/compose/object.kt.template.js`
- `composeObjectOpts` 支持 `className`、`packageName`
- `lib/common/transformGroups.js` 的 `compose` 组包含 `color/composeColor`、`size/compose/em`、`size/compose/remToSp`、`size/compose/remToDp`

**最小配置**

```js
// style-dictionary.config.mjs
export default {
  source: ["tokens/**/*.json"],
  platforms: {
    compose: {
      transformGroup: "compose",
      buildPath: "build/compose/",
      files: [
        {
          destination: "ZemoteTokens.kt",
          format: "compose/object",
          options: {
            className: "ZemoteTokens",
            packageName: "app.zemote.ui.theme"
          }
        }
      ]
    }
  }
};
```

```bash
npm i -D style-dictionary@5.7.1
npx style-dictionary build
```

**边界**：它只做“结构化令牌 → 代码”，不负责从网页抽令牌；语义命名（`color.surface.card`）仍需人工/LLM 归一。生成的 Compose 对象是常量集合，不包含 `MaterialTheme` 语义映射，落地时要在 `Theme.kt` 里桥接。

**风险**：`remToSp/remToDp` 依赖根字号假设，Web 的 `rem` 与 Android `sp` 不是一一对应，必须人工校准。

---

### A6. jpoindexter/design-md-extractor —— 最完整的单站抽取管线

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/jpoindexter/design-md-extractor |
| star | 68 |
| License | MIT |
| 最近提交 | 2026-06-07（低频） |
| 形态 | CLI + 本地 GUI + MCP server（全本地，不调用 AI，无需 API key） |

```bash
git clone https://github.com/jpoindexter/design-md-extractor
cd design-md-extractor
npm install
npx playwright install chromium
npm run build

node dist/cli.js extract https://example.com --out ./out/example
# GUI
npm run dev            # http://127.0.0.1:4317
# MCP
node /abs/path/dist/mcp.js
```

**产物**：`DESIGN.md`、`evidence.json`（schema 校验）、`tokens.css`、`tailwind-theme.js`、`design-tokens.json`（W3C DTCG）、`ai-prompt.txt`、`preview.html`、三档截图。

**采集维度**：颜色/渐变/字体/间距/圆角/阴影/表面层级/组件去重与语义命名/布局/图像策略/动效时长与缓动/**hover/focus/active/disabled 的静态解析 + 真实指针触发观察**（能抓到 JS 驱动的 hover）。

**能力边界**

- 面向“公开可访问站点”；有 Cloudflare/登录墙时需 `--cookies` 或 `--profile`。
- 组件识别是启发式，复杂设计系统会漏；`DESIGN.md` 质量取决于站点规范程度。
- 输出仍是 Web 语言（CSS/Tailwind），**没有 Compose 导出**——这正是 A5 要接的地方。

**为什么是 A**：它是“证据 → DESIGN.md → 令牌”整条链里最完整、许可证干净、可离线跑的开源实现，适合直接当基线，再在其 `design-tokens.json` 后接 Style Dictionary。

---

### A7. arvindrk/extract-design-system —— 最轻的起步令牌抽取

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/arvindrk/extract-design-system |
| star | 269 |
| License | MIT |
| 最近提交 | 2026-06-19（低频；npm `extract-design-system@0.1.11`） |
| 形态 | Skill + CLI（Playwright） |

```bash
npx playwright install chromium
npx -y extract-design-system@0.1.11 https://example.com

# 只抽取不生成
npx -y extract-design-system@0.1.11 https://example.com --extract-only
# 用已有 normalized.json 重新生成
npx -y extract-design-system@0.1.11 init
```

**产物**：`.extract-design-system/raw.json`、`normalized.json`、`design-system/tokens.json`、`design-system/tokens.css`。

**边界（作者自述）**：v1 只抽“颜色/字体/间距/圆角/阴影”并生成起步文件，**不生成组件库，不保证像素级还原**。

**结论**：适合“先跑一遍看站点有没有规范令牌”的快筛；深度还原用 A6。

---

## 4. 需改造（B 级）详解

### B1. Lucent-Snow/style-extractor —— 方法论最贴合，但许可证不允许复用

- 462 star，**API license = NONE，LICENSE 文件 404**，最近提交 2026-03-13。
- 结构：`SKILL.md` + `references/`（含两套完整参考案例：9nine、MotherDuck）+ `scripts/`（`interaction-diff.js`、`transition-scanner.js`、`motion-tools.js`、`library-detect.js`、`extract-keyframes.py`）。
- 流程（SKILL.md 原文要点）：打开真实网页 → 截图 + 抓 computed style + 下载 CSS/JS → 有动效时补运行时证据 → 输出 `style-guide.md` / `motion-guide.md` / `evidence-manifest.md`。
- 强制依赖 `chrome-devtools-mcp`；输出目录写死 `%USERPROFILE%\style-extractor\`（Windows 专属）。
- **采用方式**：只读它的“证据清单/输出契约/动效取证思路”，不要复制 `SKILL.md` 或脚本；用 A1 重写同等流程。

### B2. senlindesign/taste-skill —— “设计品味”层，但无许可证

- 449 star，API `NONE`，**无 LICENSE 文件**（README 徽章声称 MIT，与实际不符）。
- 4 步管线：measure → pattern → taste → observer；产物 `{domain}.md` + `{domain}.json`，强调“为什么这样设计”而不只是数值。
- 依赖 Playwright MCP；`references/extract.js` 是核心抽取脚本。
- **采用方式**：借鉴“Taste DNA（原则/触发条件/取舍）”这一层，补在令牌之后；代码不可复制。

### B3. gatsby-sec/web-reverse-skills —— 中文五件套分类

- 20 star，API `NONE`，无 LICENSE 文件（README 写 MIT）。
- 五个 skill：`deobfuscate`、`webpack-unpack`、`env-patch`、`sign-crack`、`anti-debug`。
- 价值在**任务分解方式**，不在实现深度；适合作为内部 checklist 的来源之一。

### B4. INSANE0777/Awwwards-mcp

- 405 star，MIT，2026-11-03 活跃。
- 能力：自然语言搜获奖站点、拉“design DNA”、band-map 任意 URL、滚动/悬停动效普查、框架指纹、读 bundle、按 section 重建并生成 Vite/Astro/Next 工程、`verify_build` 打分。
- **不适合直接用的原因**：抓取 awwwards.com 涉及第三方 ToS 与反爬；产物是 Web 工程，不是 Compose；“重建”偏生成式，证据链不如 A6 稳。
- 可用于“找参考站点/动效参数”的灵感层。

### B5. batteryshark/rekit

- 20 star，Apache-2.0，2026-07-27。
- 统一 `bin/rekit` 调度 + `registry.json` 能力声明 + 每个 skill 的 `SKILL.md` + 可执行 runner；含 `js-deobfuscate`。
- 优点：契约清晰、可审计；缺点：Python/Bash 调度器 + 容器镜像，接入本项目的收益低于直接调 webcrack。
- **结论**：如果要做“逆向工具中台”，参考其 registry/doctor/availability 设计；否则不引入。

### B6. aldefy/compose-skill

- 600 star，LICENSE 文件正文为 MIT（API 报 `NOASSERTION`，已人工确认），2026-07-08。
- 内容：Jetpack Compose / CMP 的 API 事实核查（以 `androidx/androidx` 源码为据）、`remember`、重组稳定性、modifier 顺序、导航、废弃 API。
- **不解决网页提取**，但能显著降低“令牌生成后写错 Compose”的返工；建议作为落地阶段的编码护栏。

### B7. Fausto-404/js-reverse-automation--skill

- 606 star，Apache-2.0，2026-10-10。
- 组合：`chrome-devtools-mcp` + Skill 规范 + JSRPC + Flask + autoDecoder。
- 偏“请求签名/加密参数”逆向，与“样式还原”只共享浏览器取证层；国内站点场景可参考。

### B8. dominikmartn/hue

- 863 star，MIT，2026-06-11。
- 从 URL/品牌名/截图生成一套完整设计语言 skill（颜色、字体、间距、组件、明暗主题、hero 配方）。
- 偏“生成品牌系统”，不是“提取现有站点证据”；可用于“提取后补齐缺失的暗色模式/组件规则”。

### B9. podo/design-agent-skills

- 30 star，MIT，2026-10-27，164 个 skill 的目录（按需安装）。
- `npx skills add podo/design-agent-skills`；含 `extract-design-md`、`taste-skill` 等。
- 价值：一次拿到多个设计 skill 的“菜单”；风险：质量参差，需逐个审。

### B10. chentianxiong123/webclone-skill

- 1 star，MIT，2026-10-06。
- Python Playwright + 12 个 JS 抽取脚本，DOM → Vue 3 生成。
- 价值：**脚本清单**（抽取哪些维度）可对照自查；实现不建议直接用。

### B11. bernaferrari/FigmaToCode

- 5,262 star，**GPL-3.0**，2026-08-05。
- 支持 HTML/Tailwind/Flutter/SwiftUI，**没有 Compose**。
- GPL-3.0 对闭源产品不友好；仅参考其“布局推断”策略。

---

## 5. 针对本项目的落地建议

### 5.1 推荐流水线（四层）

```
[1 采集]  chrome-devtools-mcp  ── get_css_styles / evaluate_script / network
              │  （登录态/多页时叠加 playwright-mcp）
              ▼
[2 还原]  webcrack（webpack/obfuscator） + wakaru（esbuild/Metro）
              │  先找 source map：unwebpack-sourcemap / source-from-sourcemaps
              ▼
[3 归一]  自建薄脚本：CSS 规则 → 语义令牌（W3C DTCG JSON）
              │  参考 design-md-extractor 的 evidence schema 与命名策略
              ▼
[4 生成]  style-dictionary（compose/object）→ ZemoteTokens.kt
              │  + 人工 MaterialTheme 语义映射（surface/onSurface/...）
              ▼
          Compose UI（配合 compose-skill 做编码护栏）
```

### 5.2 命令清单（可直接执行）

```bash
# 0) 环境
node -v            # webcrack 要求 22 或 24

# 1) 采集：启动 Chrome DevTools MCP（MCP 客户端配置见 §A1）
npx -y chrome-devtools-mcp@1.12.0 --isolated

# 2) 还原：先 webcrack，再按产物特征决定是否 wakaru
npm i -g webcrack@2.16.0
webcrack "E:/Zcode_android/Reference_web/3.17.1/assets/<entry>.js" -o ./reverse/webcrack-out

npm i -g @wakaru/cli@1.16.0
wakaru ./reverse/webcrack-out --unpack -o ./reverse/wakaru-out

# 3) 快筛：站点是否有规范令牌
npx -y extract-design-system@0.1.11 https://<target> --extract-only

# 4) 深抽：DESIGN.md + 多格式产物
node design-md-extractor/dist/cli.js extract https://<target> --out ./reverse/design-md

# 5) 生成 Compose 令牌
npm i -D style-dictionary@5.7.1
npx style-dictionary build   # 配置见 §A5
```

### 5.3 与现有 `docs/web-reverse/tools/` 的关系

仓库已有 `dump.js`、`cssq.js`、`index.pretty.js`、`parse_css.py`、`theme_zai.txt` 等手工脚本，说明当前是“自建脚本 + 手工整理”的路径。建议：

| 现有资产 | 处置 |
|---|---|
| `dump.js` / `dump_window.py` | 保留作一次性探查；批量采集迁到 `evaluate_script`（A1） |
| `cssq.js` / `css_evidence*.txt` | 保留为历史证据；新证据用 `get_css_styles` 的“胜出/被覆盖”标注，可信度更高 |
| `index.pretty.js` | 由 webcrack/wakaru 产物替代（可读性更好、保留模块边界） |
| `parse_css.py` / `semantic_classes.py` | 演进为 §5.1 第 3 层的“归一器”，输出 DTCG JSON |
| `theme_zai.txt` / `i18n.json` | 作为令牌命名的语义来源，不参与自动抽取 |

> 不建议直接删除旧脚本；先并行跑一轮，用同一站点对比“手工证据 vs MCP 证据”，确认无遗漏后再收敛。

### 5.4 成本估算

| 阶段 | 工作 | 预估 |
|---|---|---|
| P0 | 接入 chrome-devtools-mcp，跑通 `take_snapshot → get_css_styles` | 0.5 人日 |
| P1 | webcrack + wakaru 还原目标 bundle，产出可读模块 | 0.5–1 人日 |
| P2 | 写归一器：CSS 规则 → DTCG JSON（颜色/字体/间距/圆角/阴影/动效） | 2–3 人日 |
| P3 | Style Dictionary 接 `compose/object`，生成 `ZemoteTokens.kt` 并接主题 | 1 人日 |
| P4 | 语义映射与视觉回归（对照截图逐屏校准） | 3–5 人日 |

**最小验证路径（半天）**：只做 P0 + 手工挑 1 个页面 → `get_css_styles` 导出 → 人工比对现有 `theme_zai.txt`，确认证据质量后再投入 P2。

---

## 6. 能力边界与风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **无许可证仓库**（style-extractor / taste-skill / web-reverse-skills / designkit / RayCodes） | 复制代码或 SKILL.md 文本即侵权 | 只读方法论；代码全部自写；准入前跑 `gh api .../contents/LICENSE` |
| **GPL-3.0**（decode-js、FigmaToCode） | 传染闭源产品 | 不链接、不修改、仅命令行隔离调用或直接放弃 |
| **CSS→Compose 无成熟方案** | 不能“一键转” | 走令牌链；布局/交互手工实现；用截图回归验收 |
| **chrome-devtools-mcp 工具名漂移** | 升级后脚本失效 | 固定 `1.12.0`；升级前跑 `get_css_styles` 冒烟 |
| **`get_css_styles` 逐元素 + 分页** | 大页面耗时 | 先用 `evaluate_script` 批量筛“有样式差异”的节点，再对关键节点取规则级证据 |
| **webcrack 要求 Node 22/24 + isolated-vm** | CI/本地版本不一致会失败 | 固定 Node 22 LTS；CI 与本地同版本 |
| **第三方站点 ToS / 反爬**（Awwwards、clone 类） | 法律与封禁风险 | 只对自有/授权站点跑；Awwwards 仅作灵感检索 |
| **设计令牌语义错配**（Web `rem` vs Android `sp/dp`） | 视觉比例失真 | 固定根字号假设；用 4dp 栅格校准；逐屏截图回归 |
| **LLM 重命名成本**（humanify） | token 费用与不可复现 | 仅在 webcrack 后仍不可读时小范围使用 |

---

## 7. 证据链接（全部已实际访问/核验）

**采集与浏览器**

- https://github.com/ChromeDevTools/chrome-devtools-mcp
- https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/tool-reference.md （`get_css_styles` 定义）
- https://github.com/microsoft/playwright-mcp
- https://github.com/browser-use/browser-harness
- https://github.com/pasky/chrome-cdp-skill

**JS 还原**

- https://github.com/j4k0xb/webcrack
- https://github.com/pionxzh/wakaru
- https://github.com/pionxzh/wakaru/blob/main/skills/wakaru/SKILL.md
- https://github.com/kuizuo/js-deobfuscator
- https://github.com/echo094/decode-js
- https://github.com/jehna/humanify
- https://github.com/rarecoil/unwebpack-sourcemap
- https://github.com/akx/source-from-sourcemaps

**样式/设计系统抽取**

- https://github.com/Lucent-Snow/style-extractor
- https://github.com/senlindesign/taste-skill
- https://github.com/jpoindexter/design-md-extractor
- https://github.com/arvindrk/extract-design-system
- https://github.com/gatsby-sec/web-reverse-skills
- https://github.com/INSANE0777/Awwwards-mcp
- https://github.com/dominikmartn/hue
- https://github.com/podo/design-agent-skills
- https://github.com/chentianxiong123/webclone-skill

**令牌与 Compose**

- https://github.com/style-dictionary/style-dictionary
- https://github.com/style-dictionary/style-dictionary/blob/main/__tests__/formats/androidCompose.test.js （`compose/object`）
- https://github.com/style-dictionary/style-dictionary/blob/main/lib/common/formats.js （`composeObjectOpts.className/packageName`）
- https://github.com/style-dictionary/style-dictionary/blob/main/lib/common/transformGroups.js （`compose` transform group）
- https://github.com/tokens-studio/figma-plugin
- https://github.com/tokens-studio/sd-transforms
- https://github.com/aldefy/compose-skill
- https://github.com/CaelumF/FigmaToCompose
- https://github.com/bernaferrari/FigmaToCode

**移动端远程**

- https://github.com/amantus-ai/vibetunnel
- https://github.com/omnara-ai/omnara
- https://github.com/librae8226/nexus4cc
- https://github.com/mjzcng/codex-cli-mobile-pairing
- https://github.com/brunojaime/codex-cli-mobile-bridge

---

## 8. 未验证 / 未覆盖（明确标注）

| 项 | 状态 | 说明 |
|---|---|---|
| `goto-bus-stop/webpack-unpack` | **404 / 不可验证** | 搜索结果存在，但 `gh api repos/goto-bus-stop/webpack-unpack` 返回 404，可能已删除或改名；不列入推荐 |
| “Happy Coder” 官方移动端 | **未找到可信仓库** | `happy coder claude` 查询只返回噪声；不臆测 |
| `cssused`（页面已用 CSS 提取扩展） | **未验证到主仓** | 查询结果为同名噪声；该方向用 A1 `get_css_styles` + DevTools Coverage 替代 |
| Vite/Rollup chunk 图还原 | **无专用工具** | wakaru 明确列出 webpack/esbuild/Bun/Browserify/Metro，未列 Rollup；Vite 产物建议先找 source map |
| Figma → Compose 商用方案 | **无** | 现有项目均停更或玩具级；不建议作为依赖 |
| 各仓库 star 精确性 | 快照 | 2026-11-03 抓取，后续会变 |

---

## 9. 一页决策清单

- 要“网页样式证据”：**装 A1**，用 `get_css_styles`，不要再用 `dump.js` 猜。
- 要“JS 可读”：**A3 先跑，A4 补刀**；先找 source map 再反混淆。
- 要“令牌文件”：**A7 快筛，A6 深抽**；只信 `evidence.json`，不信截图目测。
- 要“Compose 令牌”：**A5 的 `compose/object`**，`className=ZemoteTokens`，`packageName=app.zemote.ui.theme`。
- 要“设计品味/为什么”：读 **B1/B2** 的方法论，**不复制代码**。
- 不要做的事：找“CSS→Compose 一键转换”、复制无 LICENSE 的 Skill、把 GPL 工具链进产品。
