# 壳端三场景注入点审计：状态栏主题 / 滚动条 / 会话加载

> **审计基线**：`zcode.z.ai/remote/v4` 当前线上构建 —— 主包 `assets/index-B-ilXaCQ.js`（4,821,509 B）、
> 协议 schema 模块 `assets/src-wmk2orCZ.js`（285,095 B）、样式 `assets/index-DR0mqKFV.css`（392,369 B）、
> 入口 `index.html`（20,641 B，含主题 inline bootstrap）。
> 快照：`Docs/27-remote-v4-网页快照-20260920/latest/`（2,518 个 assets）；入口与线上逐字节一致。
> **审计日期**：2026-09-20 ｜ **方法**：网页侧逐锚点定位（HTML/CSS/主包）+ 壳侧实现对照（当前工作树）｜ **偏移口径**：UTF-8 解码后**字符偏移**。
> 本篇回答的是：**壳想做的三件事，各依赖哪些页面锚点、锚点的语义是什么、什么条件下会失效。**

---

## 0. 一句话

三个场景的锚点**全部存在且语义清晰**：状态栏靠**两个类名 + 一个 `<html>` 属性**（后者由页面 `index.html` 的 inline bootstrap 在 bundle 执行前就盖上）；
滚动条靠页面的**14px 轨道规格**（`::-webkit-scrollbar`）与三个隐藏例外；
会话加载靠**关系型判据的组合**（chat 页标记 + 时间线行数 + 页面 RPC 信标），单独看任何一个都会误判。

---

## 1. 三场景锚点总表

| 场景 | 锚点 | 在哪 | 当前构建是否存在 |
| --- | --- | --- | --- |
| 状态栏 | `.zcode-boot-loading` | `index.html`（inline `<style>`）+ 主包 | ✅ |
| 状态栏 | `.bg-background-win-alt` | CSS `@97,854` + 主包 1 处 | ✅ |
| 状态栏 | `.bg-header` | CSS `@106,606` + 主包 2 处 | ✅ |
| 状态栏 | `html[data-zcode-browser-theme-surface]` | `index.html` inline script 设置；主包 0 命中、`catalogTree` chunk 有读点 | ✅（**产生者在 HTML 里**） |
| 滚动条 | `*{scrollbar-width:auto; scrollbar-color:var(--color-border) transparent}` | CSS `@374,504` | ✅ |
| 滚动条 | `::-webkit-scrollbar{width:14px;height:14px}` + track/thumb/corner | CSS `@374,504` | ✅ |
| 会话加载 | `[data-mobile-page="chat"]` | 主包 `@2,902,755` | ✅ |
| 会话加载 | `[data-v4-timeline-scroll]` + 行数三兄弟 | 主包 `@2,225,176` | ✅ |
| 会话加载 | `[data-zcode-chat-loading-animate]`、`[data-testid="chat-error-banner"]` | 主包 | ✅ |
| 会话加载 | 页面 RPC 信标（`subscribeConversationV4` 等） | 主包 `@503,312` 等 | ✅ |

---

## 2. 场景一：状态栏主题判定

### 2.1 壳侧实现（`android-shell/app/src/main/assets/inject.js:3160-3230`）

```js
var NARROW_QUERY = '(max-width: 767px)';       // 与页面 Tailwind max-md: 同源
var DARK_QUERY   = '(prefers-color-scheme: dark)';

function pageStateName() {
  if (!document.documentElement) return '';
  if (document.getElementsByClassName('zcode-boot-loading').length > 0)      return 'boot';
  if (document.getElementsByClassName('bg-background-win-alt').length === 0) return 'boot';   // ★ 类消失 ⇒ 当 boot
  return mediaMatches(NARROW_QUERY) ? 'main-header' : 'main-surface';        // ★ 窄=页面标题栏在最上，宽=shell 区在最上
}

function pageThemeName() {
  var stamped = document.documentElement.getAttribute('data-zcode-browser-theme-surface');
  if (stamped === 'dark' || stamped === 'light') return stamped;             // ① 页面盖的章（权威）
  var inline = String(document.documentElement.style.colorScheme || '');     // ② 内联 style（boot 期就有）
  … // ③ 回退 prefers-color-scheme
}
```

**三态语义（壳的注释原文要点）**：

- `boot` = 预渲染引导壳（`.zcode-boot-loading`）**或**所有状态页（KICKED/接管）——它们都把页面背景铺到顶部；
- `main-header` = **窄屏**（≤767px）：页面自己的标题栏（`bg-header`）紧贴状态栏；
- `main-surface` = **宽屏**：外壳区（`bg-background-win-alt`）在最上。

### 2.2 页面侧的三个锚点

| 锚点 | 定义 | 证据 |
| --- | --- | --- |
| `.zcode-boot-loading` | `display:flex; align-items:center; justify-content:center; width/height:100%; background: var(--zcode-bootstrap-bg, var(--color-background, #0f172a)); color: var(--zcode-bootstrap-fg, …)` | `index.html` inline `<style>`（off 8,735 区） |
| `.bg-background-win-alt` | `background-color: var(--color-background-win-alt)` | CSS `@97,854` |
| `.bg-header` | `background-color: var(--color-header)` | CSS `@106,606` |

> **窄屏宽屏都是同一套类名**：页面的手机外壳 chat 页头就是 `bg-header`（见 18 篇 §4.4 的 `className`），
> 宽屏时最顶上是 `bg-background-win-alt` 的容器。壳用 `matchMedia` 判宽窄，**不按设备形态** —— 这条设计在页面上成立。

### 2.3 主题信号的两级来源（**本场景最关键的一条**）

**产生者是 `index.html` 的 inline bootstrap**（在 bundle 之前执行），逐字：

```js
const STORAGE_KEY = 'zcode-theme';
const DEFAULT_THEME = 'zai-dark';
const BROWSER_THEME_COLORS = { dark: '#161616', light: '#f8f8f8' };
const BOOTSTRAP_SNAPSHOT_KEY = 'zcode:web-remote-control:bootstrap-shell';

const normalizeResolvedTheme = theme =>
  theme === 'dark' ? 'dark' : theme === 'light' ? 'light' :
  theme === 'zai-dark' ? 'dark' : theme === 'zai-light' ? 'light' : theme;

const syncBrowserThemeSurface = theme => {
  document.documentElement.setAttribute('data-zcode-browser-theme-surface', theme);   // ★ ① 属性
  document.documentElement.style.colorScheme = theme;                                 // ★ ② 内联 style
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', BROWSER_THEME_COLORS[theme]);
  document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', theme);
};

try {
  let bootstrapTheme = null;
  const snapshotRaw = window.sessionStorage.getItem(BOOTSTRAP_SNAPSHOT_KEY);           // ★ sessionStorage 快照
  …  // 决定首屏主题后调 syncBrowserThemeSurface(...)
} catch {}
```

⇒ **壳的三级回退顺序与页面的写入顺序完全对应**：属性（①）→ `style.colorScheme`（②）→ `prefers-color-scheme`（页面没写时的系统值）。
**只要页面跑过 bootstrap，①② 就同时存在**，壳的回退链只在"页面还没 bootstrap 完"的极短窗口里用到 ③。

**权威色值**（`BROWSER_THEME_COLORS`）：`dark = #161616`、`light = #f8f8f8`。
壳/鸿蒙的固定色表与这两个值同源 —— **状态栏底色不该由壳"猜"，它就是页面这两个色之一**。

### 2.4 本场景的失效模式（壳实现已经处理）

| 情形 | 现象 | 壳的处理 |
| --- | --- | --- |
| 页面在状态页（KICKED / 接管） | `bg-background-win-alt` 不存在 | 判为 `boot`（第二道判定） |
| 页面还在引导壳 | `.zcode-boot-loading` 在 | 判为 `boot` |
| 页面把主题切成深/浅 | 属性值变化 | MutationObserver 观察（`inject.js` §6 段） |
| 属性在 chunk 加载前不可见 | —— | 属性由 **inline bootstrap** 写，**不依赖 chunk**（`catalogTree` 里的只是消费点） |

---

## 3. 场景二：滚动条

### 3.1 页面规格（CSS `@374,504`，逐字）

```css
*{scrollbar-width:auto; scrollbar-color:var(--color-border) transparent}
::-webkit-scrollbar{width:14px;height:14px}
::-webkit-scrollbar-track{background:0 0}
::-webkit-scrollbar-thumb{background:var(--color-border);background-clip:padding-box;
                          border:3px solid #0000;border-radius:9999px;min-width:32px;min-height:32px}
::-webkit-scrollbar-corner{background:0 0}
```

- **轨道宽 14px**（同时给了 `scrollbar-width:auto` 的 Firefox 路径与 `scrollbar-color`）；
- 滑块是"内嵌 padding-box + 3px 透明边 + 全圆角"的胶囊，最小 32px；
- 颜色取 CSS 变量 `--color-border`（**主题化**，随深/浅色变）。

### 3.2 页面的隐藏例外（三处，别当成"规格"）

```css
.terminal-xterm-shell .xterm-viewport{scrollbar-width:none}
.terminal-xterm-shell .xterm-viewport::-webkit-scrollbar{width:0;height:0;display:none}
[data-zcode-pptx-render-surface] *{scrollbar-width:none}
[data-zcode-pptx-render-surface] *::-webkit-scrollbar{…}
```

⇒ 终端面板与 PPT 预览面**主动隐藏滚动条**；此外还有 `::-webkit-scrollbar{display:none!important}` 一类全局隐藏规则（同 CSS）。
壳的自绘方案若不做"跳过页面自己隐藏滚动条的容器"这一判断，会在这些面上画出多余的条
（壳实现里确有该跳过逻辑，见 `inject.js` §7 段注释）。

### 3.3 与壳的 14px 归零方案的关系

- 页面轨道是 14px ⇒ 壳"归零 + 自绘"的账面对得上（把 14px 还回内容区）；
- 但**该方案是 Android WebView 专有的补偿**：Android WebView 关掉了 overlay 滚动条，
  于是页面那 14px 变成实占宽度、内容被推左；
- ⚠️ **ArkWeb 不关 overlay**（`inject.js:3395-3397` 原文），同一页面在鸿蒙壳里天然居中、滚动时出条后淡出
  ⇒ **迁移到鸿蒙时这一整段大概率应当不注入**（或只保留 gutter 保险）。这条属跨平台判断，不是页面规格。

---

## 4. 场景三：会话加载

### 4.1 页面侧链路（速览，细节见 18 篇）

```
进对话 → 建订阅 subscribeConversationV4({...scope, sessionId, base?, visibility?})    ← 主包 @503,312
       → ACK {subscriptionId}
       → 帧（onDynamicConversationFrame）→ ops 归约 → rows.window 增长
       → 行数/跟随态落到 DOM（data-row-count / data-following / data-total-row-count）
```

### 4.2 壳的判据锚点（`inject.js` §5b/§5c）

| 锚点 | 含义 | 陷阱 |
| --- | --- | --- |
| `[data-mobile-page="chat"]` | 在对话视图 | home 页为 `null`；**它只说明"在对话页"，不说明"有内容"** |
| `[data-v4-timeline-scroll]` 存在 | 时间线容器已挂载 | 与上一条常同时为真，不能单独当"内容已到" |
| `data-row-count > 0` | 窗口内有行 | 需要与下面两条合看 |
| `data-total-row-count` | 全会话行数 | `0` 才是"会话真的空"；`窗口=0 但 total>0` 是"窗口没铺开" |
| `data-loading-older="true"` | 正在加载更早 | 与"卡住"必须区分 |
| `data-following` | 是否跟随 | `false` 可能是用户滚上去了，不是停更 |
| `[data-zcode-chat-loading-animate]` | 加载动画 | 这就是"左下角转圈"的候选锚点（见 23 篇） |
| `[data-testid="chat-error-banner"]` | 错误横幅 | 失败态的直接证据 |
| 页面 RPC 信标 | `subscribeConversationV4` / `conversationRowsRangeV4` 的调用痕迹（壳用 hook 到的 RPC 观测） | 传输坏到发不出 RPC 时它是唯一能到的信标 |

### 4.3 三种"看起来一样、其实不同"的状态（壳必须区分）

| 状态 | 页面表现 | 判据组合 |
| --- | --- | --- |
| **真没内容**（会话为空） | 0 行 | `total=0` 且无 `loading-older`、无错误横幅 |
| **内容没到**（订阅没回/被压队） | 0 行 + 可能转圈 | `total>0` 或（`total=0` 且 chat 标记在、有 loading animate） |
| **内容到了但视图没回到对话** | 落 home | `data-mobile-page` 不存在（= home）⇒ 壳的**档 3** 才动手（21/24 篇） |

---

## 5. 锚点脆弱性与巡检建议

1. **锚点全是页面实现细节**，不是契约。`ANCHOR-REPORT` 已把 42 条锚点纳入巡检（含本篇的全部锚点）；
2. **优先替换成更硬的判据**：`data-testid`（页面侧 testid 常量表有 300+ 个值，含
   `chat-send-button`、`v4-composer-input`、`v4-timeline`、`task-list`、`task-item`）比类名稳
   （`ANCHOR-REPORT` Q6 的裁定）；
3. **两端同源实现有漂移风险**：安卓 `inject.js` 与鸿蒙 `Index.ets` 各有一份三态判定实现
   （判据/断点/状态名都重复）。**建议抽成一份声明式表 + 一条跨端一致性断言**（`ANCHOR-REPORT` Q8）；
4. **主题属性不要绕路**：直接读 `data-zcode-browser-theme-surface`（页面 inline bootstrap 保证它在 bundle 前就有）。

---

## 6. 复现

```bash
cd Docs/27-remote-v4-网页快照-20260920/latest
# 主题 bootstrap（属性/内联 style/色值）
python -c "d=open('index.html',encoding='utf-8',errors='replace').read();i=d.find('syncBrowserThemeSurface');print(d[i-900:i+1400])"
# 类名的 CSS 定义
python -c "import re;d=open('assets/index-DR0mqKFV.css',encoding='utf-8',errors='replace').read();[print(d[m.start():d.find('}',m.start())+1]) for m in list(re.finditer(r'\.bg-(?:background-win-alt|header)\{',d))[:3]]"
# 滚动条规格与隐藏例外
python -c "d=open('assets/index-DR0mqKFV.css',encoding='utf-8',errors='replace').read();i=d.find('::-webkit-scrollbar{width:14px');print(d[i-260:i+700])"
# 壳侧实现（当前工作树）
sed -n '3160,3235p' ../../../android-shell/app/src/main/assets/inject.js
```

---

## 7. 出处

- 快照：`Docs/27-remote-v4-网页快照-20260920/latest/`（入口与线上逐字节一致）。
- 壳侧：`android-shell/app/src/main/assets/inject.js`（当前工作树实读）。
- 相关篇目：[18 对话渲染（DOM 契约）](18-remote-v4-功能域-对话渲染与流式更新.md) ·
  [20 导航与外观（主题/i18n）](20-remote-v4-功能域-导航列表与全局外观.md) ·
  [23 卡死现场分析](23-remote-v4-进对话无内容与左下角转圈-现场分析.md) ·
  [27 快照与锚点巡检](../27-remote-v4-网页快照-20260920/README.md)。
