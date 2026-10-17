# rich/ — offline math & mermaid rendering assets

Used by `app.zemote.ui.chat.rich.RichBlock`. Everything here is loaded from
`file:///android_asset/rich/`; the WebView has `blockNetworkLoads = true`.

## Files

| Path | Origin | Size |
| --- | --- | --- |
| `katex/katex.js`, `katex/dep.js` | `Reference_web/3.15.3/assets/katex-DefY6ydN.js` + `chunk-Bj-mKKzh.js`, converted from ESM to classic scripts (`window.katex`) | ~257 KB |
| `katex/katex.css` | KaTeX rules extracted from `Reference_web/3.15.3/assets/index-BD2BL4Cc.css`, font URLs rewritten to local woff2 | ~21 KB |
| `katex/KaTeX_*.woff2` | same reference assets (19 files, woff2 only; woff/ttf omitted) | ~250 KB |
| `mermaid.js` | **minimal UMD renderer written for this project** (see below) | ~22 KB |
| `index.html` | page template with `__TYPE__`, `__DISPLAY__`, `__THEME__`, `__SOURCE_B64__` placeholders | ~6 KB |

## Why mermaid.js is not the upstream build

The reference web bundle ships no standalone mermaid build: only
`mermaid-GHXKKRXX-*.js` (a React wrapper), `mermaid-*-*.js` (syntax-highlighting
grammars) and lazy diagram chunks whose core lives inside the 6.6 MB
`index-BGa3KiRe.js`. Extracting that is not viable, so `mermaid.js` is a small
dependency-free renderer exposing `mermaid.render(id, text) -> Promise<{svg}>`.

Supported diagram types:

- `flowchart` / `graph` with `TD`, `TB`, `BT`, `LR`, `RL`; node shapes
  `[]`, `()`, `(())`, `([])`, `[[]]`, `[()]`, `{}`, `{{}}`, `>]`; edges
  `-->`, `---`, `-.->`, `==>`, `--x`, `--o`, `-->|label|`, `-- label -->`.
- `sequenceDiagram` with `participant`/`actor`, `->>`, `-->>`, `->`, `-->`,
  `-x`, `Note over/left of/right of`, `loop`/`alt`/`opt`/`par`/`critical`/`break`
  frames, `else`, `end`.

Anything else rejects the promise; `RichBlock` then shows the monospace source.
