#!/usr/bin/env python3
# ---------------------------------------------------------------------------
#  从 Reference_web/3.17.1 的 CSS 快照解析 zai-dark / zai-light 两套语义令牌，
#  生成 Android Compose 侧的 ZaiColors.kt（ZemoteColors 的两个实例）。
#
#  解析口径（与 design/tokens/colors.json _meta.conversion 一致）：
#    1. 基座 = `:root,:host`（含 @layer theme 与 @supports 分支）；
#       皮肤覆盖 = `.theme-zai-dark` / `.theme-zai-light`（含 `.dark` 的暗色叠加）。
#    2. `var(--x)` 按同一套生效值递归解析（自定义属性替换语义）。
#    3. oklch/oklab -> 线性 sRGB（Bjorn Ottosson 参考矩阵）-> IEC 61966-2-1 伽马
#       -> 每通道 clamp [0,1] -> 8bit round-half-up。
#    4. color-mix() 按 CSS Color 4 预乘 alpha 插值（oklab 或 srgb），权重归一化。
#
#  用法：
#    python -I design/tokens/generate_zai_theme.py \
#        --css Reference_web/3.17.1/assets/index-BgVjt5KE.css \
#        --out android/app/src/main/kotlin/app/zemote/ui/theme/ZaiColors.kt
#
#  说明：本脚本只读 Reference_web/ 与 design/tokens/colors.json，只写目标 .kt。
#  令牌名与顺序取自 colors.json semantic 的 181 条。
# ---------------------------------------------------------------------------
from __future__ import annotations

import argparse
import io
import json
import math
import os
import re
import sys
from dataclasses import dataclass

# ---------------------------------------------------------------------------
#  CSS 轻量解析
# ---------------------------------------------------------------------------


def strip_comments(src: str) -> str:
    return re.sub(r"/\*.*?\*/", "", src, flags=re.S)


@dataclass
class Rule:
    prelude: str
    contexts: tuple[str, ...]
    decls: list[tuple[str, str]]
    offset: int

    @property
    def selectors(self) -> list[str]:
        return [s.strip() for s in self.prelude.split(",")]


def _find_matching_brace(src: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    n = len(src)
    quote = ""
    paren = 0
    while i < n:
        ch = src[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = ""
        elif ch == "\\":
            # 选择器里的转义类名（如 content-\[\'\'\]）不是字符串定界符
            i += 2
            continue
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            paren += 1
        elif ch == ")":
            paren = max(0, paren - 1)
        elif paren == 0 and ch == "{":
            depth += 1
        elif paren == 0 and ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced braces")


def _next_semicolon_or_brace(src: str, start: int, end: int) -> int:
    i = start
    depth = 0
    quote = ""
    while i < end:
        ch = src[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = ""
        elif ch == "\\":
            i += 2
            continue
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif depth == 0 and ch in ";{":
            return i
        i += 1
    return -1


def _split_decls(body: str) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    depth = 0
    quote = ""
    start = 0
    i = 0
    n = len(body)
    while i <= n:
        ch = body[i] if i < n else ";"
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = ""
        elif ch == "\\":
            i += 2
            continue
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif ch == ";" and depth == 0:
            part = body[start:i].strip()
            if part:
                if ":" in part:
                    prop, val = part.split(":", 1)
                    out.append((prop.strip(), val.strip()))
            start = i + 1
        i += 1
    return out


CONDITIONAL_AT_RULES = (
    "@media",
    "@supports",
    "@layer",
    "@container",
    "@scope",
    "@starting-style",
)


def parse_rules(src: str) -> list[Rule]:
    rules: list[Rule] = []
    n = len(src)

    def walk(start: int, end: int, contexts: tuple[str, ...]) -> None:
        i = start
        while i < end:
            j = _next_semicolon_or_brace(src, i, end)
            if j < 0:
                break
            if src[j] == ";":
                i = j + 1
                continue
            prelude = src[i:j].strip()
            close = _find_matching_brace(src, j)
            if prelude.startswith("@"):
                keyword = prelude.split("{")[0].split()[0].lower()
                if keyword in CONDITIONAL_AT_RULES:
                    walk(j + 1, close, contexts + (prelude,))
                # @font-face / @keyframes / @property 等不参与变量级联
            else:
                rules.append(
                    Rule(
                        prelude=prelude,
                        contexts=contexts,
                        decls=_split_decls(src[j + 1 : close]),
                        offset=i,
                    )
                )
            i = close + 1

    walk(0, n, ())
    return rules


# ---------------------------------------------------------------------------
#  颜色计算
# ---------------------------------------------------------------------------

RGBA = tuple[float, float, float, float]  # r,g,b in [0,1] gamma sRGB; a in [0,1]

NAMED = {
    "transparent": (0.0, 0.0, 0.0, 0.0),
    "black": (0.0, 0.0, 0.0, 1.0),
    "white": (1.0, 1.0, 1.0, 1.0),
}


def _clamp01(x: float) -> float:
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def _srgb_to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(c: float) -> float:
    return c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def _oklab_to_srgb(L: float, a: float, b: float) -> tuple[float, float, float]:
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_**3, m_**3, s_**3
    r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return (
        _clamp01(_linear_to_srgb(r)),
        _clamp01(_linear_to_srgb(g)),
        _clamp01(_linear_to_srgb(bl)),
    )


def _srgb_to_oklab(r: float, g: float, b: float) -> tuple[float, float, float]:
    rl, gl, bl = _srgb_to_linear(r), _srgb_to_linear(g), _srgb_to_linear(b)
    l = 0.4122214708 * rl + 0.5363325363 * gl + 0.0514459929 * bl
    m = 0.2119034982 * rl + 0.6806995451 * gl + 0.1073969566 * bl
    s = 0.0883024619 * rl + 0.2817188376 * gl + 0.6299787005 * bl
    l_, m_, s_ = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    return (
        0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
        1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
        0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
    )


def _parse_alpha(tok: str | None) -> float:
    if tok is None:
        return 1.0
    tok = tok.strip()
    if tok.endswith("%"):
        return _clamp01(float(tok[:-1]) / 100.0)
    return _clamp01(float(tok))


def _parse_percent_or_number(tok: str, scale: float) -> float:
    tok = tok.strip()
    if tok.endswith("%"):
        return float(tok[:-1]) / 100.0 * scale
    return float(tok)


def _split_args(s: str) -> list[str]:
    out, depth, start, quote = [], 0, 0, ""
    for i, ch in enumerate(s):
        if quote:
            if ch == quote:
                quote = ""
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif ch == "," and depth == 0:
            out.append(s[start:i].strip())
            start = i + 1
    tail = s[start:].strip()
    if tail:
        out.append(tail)
    return out


def _components(body: str) -> list[str]:
    """CSS 函数参数：既可能是逗号分隔，也可能是空格分隔。"""
    parts = _split_args(body)
    if len(parts) == 1:
        parts = parts[0].split()
    return parts


def parse_color(
    value: str, vars_map: dict[str, str], _seen: frozenset[str] = frozenset()
) -> RGBA:
    v = value.strip()
    if v.endswith("!important"):
        v = v[: -len("!important")].strip()
    low = v.lower()
    if low in NAMED:
        return NAMED[low]
    if low.startswith("#"):
        h = low[1:]
        if len(h) in (3, 4):
            h = "".join(c * 2 for c in h)
        if len(h) == 6:
            return (
                int(h[0:2], 16) / 255,
                int(h[2:4], 16) / 255,
                int(h[4:6], 16) / 255,
                1.0,
            )
        if len(h) == 8:
            return (
                int(h[0:2], 16) / 255,
                int(h[2:4], 16) / 255,
                int(h[4:6], 16) / 255,
                int(h[6:8], 16) / 255,
            )
        raise ValueError(f"bad hex: {value}")
    m = re.match(r"^var\(\s*(--[\w-]+)\s*(?:,\s*(.*))?\)$", v, re.S)
    if m:
        name, fallback = m.group(1), m.group(2)
        if name in _seen:
            raise ValueError(f"cyclic var {name}")
        if name in vars_map:
            return parse_color(vars_map[name], vars_map, _seen | {name})
        if fallback is not None:
            return parse_color(fallback, vars_map, _seen | {name})
        raise KeyError(name)
    m = re.match(r"^(rgba?|hsla?)\((.*)\)$", low, re.S)
    if m:
        fn, body = m.group(1), m.group(2)
        parts = _components(body)
        if fn.startswith("rgb"):
            nums = []
            for p in parts[:3]:
                nums.append(_parse_percent_or_number(p, 1.0) if p.endswith("%") else float(p) / 255.0)
            alpha = _parse_alpha(parts[3].split("/")[-1] if len(parts) > 3 else None)
            if "/" in body:
                alpha = _parse_alpha(body.split("/")[-1])
            return (_clamp01(nums[0]), _clamp01(nums[1]), _clamp01(nums[2]), alpha)
        h = float(re.sub(r"(deg|rad|grad|turn)$", "", parts[0]))
        if parts[0].endswith("rad"):
            h = math.degrees(float(parts[0][:-3]))
        elif parts[0].endswith("grad"):
            h = float(parts[0][:-4]) * 0.9
        elif parts[0].endswith("turn"):
            h = float(parts[0][:-4]) * 360
        sat = _parse_percent_or_number(parts[1], 1.0)
        light = _parse_percent_or_number(parts[2], 1.0)
        alpha = _parse_alpha(parts[3].split("/")[-1] if len(parts) > 3 else None)
        if "/" in body:
            alpha = _parse_alpha(body.split("/")[-1])
        return (*_hsl_to_rgb(h % 360, sat, light), alpha)
    m = re.match(r"^oklch\((.*)\)$", low, re.S)
    if m:
        body = m.group(1)
        alpha = 1.0
        if "/" in body:
            body, alp = body.split("/", 1)
            alpha = _parse_alpha(alp)
        parts = _components(body)
        L = _parse_percent_or_number(parts[0], 1.0)
        C = _parse_percent_or_number(parts[1], 0.4)
        H = float(re.sub(r"(deg|rad|grad|turn)$", "", parts[2]))
        if parts[2].endswith("rad"):
            H = math.degrees(float(parts[2][:-3]))
        elif parts[2].endswith("grad"):
            H = float(parts[2][:-4]) * 0.9
        elif parts[2].endswith("turn"):
            H = float(parts[2][:-4]) * 360
        rad = math.radians(H)
        return (*_oklab_to_srgb(L, C * math.cos(rad), C * math.sin(rad)), alpha)
    m = re.match(r"^oklab\((.*)\)$", low, re.S)
    if m:
        body = m.group(1)
        alpha = 1.0
        if "/" in body:
            body, alp = body.split("/", 1)
            alpha = _parse_alpha(alp)
        parts = _components(body)
        L = _parse_percent_or_number(parts[0], 1.0)
        a = _parse_percent_or_number(parts[1], 0.4)
        b = _parse_percent_or_number(parts[2], 0.4)
        return (*_oklab_to_srgb(L, a, b), alpha)
    m = re.match(r"^color-mix\((.*)\)$", v, re.S)
    if m:
        return _parse_color_mix(m.group(1), vars_map, _seen)
    raise ValueError(f"unsupported color: {value}")


def _hsl_to_rgb(h: float, s: float, l: float) -> tuple[float, float, float]:
    c = (1 - abs(2 * l - 1)) * s
    x = c * (1 - abs((h / 60) % 2 - 1))
    m = l - c / 2
    if h < 60:
        r, g, b = c, x, 0
    elif h < 120:
        r, g, b = x, c, 0
    elif h < 180:
        r, g, b = 0, c, x
    elif h < 240:
        r, g, b = 0, x, c
    elif h < 300:
        r, g, b = x, 0, c
    else:
        r, g, b = c, 0, x
    return (r + m, g + m, b + m)


def _parse_color_mix(
    body: str, vars_map: dict[str, str], _seen: frozenset[str]
) -> RGBA:
    parts = _split_args(body)
    space = parts[0].strip().lower()
    if not space.startswith("in "):
        raise ValueError(f"bad color-mix space: {space}")
    space = space[3:].strip()
    if space.startswith("oklab"):
        space = "oklab"
    elif space.startswith("srgb"):
        space = "srgb"
    else:
        raise ValueError(f"unsupported color-mix space: {space}")
    if len(parts) != 3:
        raise ValueError(f"bad color-mix args: {body}")

    def parse_stop(p: str) -> tuple[RGBA, float | None]:
        toks = p.strip().split()
        pct = None
        color_tokens = []
        for t in toks:
            if t.endswith("%") and re.match(r"^[0-9.]+%$", t):
                pct = float(t[:-1]) / 100.0
            else:
                color_tokens.append(t)
        return parse_color(" ".join(color_tokens), vars_map, _seen), pct

    c1, p1 = parse_stop(parts[1])
    c2, p2 = parse_stop(parts[2])
    if p1 is None and p2 is None:
        p1, p2 = 0.5, 0.5
    elif p1 is None:
        p1 = 1.0 - p2
    elif p2 is None:
        p2 = 1.0 - p1
    total = p1 + p2
    if total <= 0:
        raise ValueError("color-mix zero weights")
    p1, p2 = p1 / total, p2 / total

    if space == "oklab":
        a1 = _srgb_to_oklab(c1[0], c1[1], c1[2])
        a2 = _srgb_to_oklab(c2[0], c2[1], c2[2])
        al = c1[3] * p1 + c2[3] * p2
        if al <= 0:
            return (0.0, 0.0, 0.0, 0.0)
        L = (a1[0] * c1[3] * p1 + a2[0] * c2[3] * p2) / al
        A = (a1[1] * c1[3] * p1 + a2[1] * c2[3] * p2) / al
        B = (a1[2] * c1[3] * p1 + a2[2] * c2[3] * p2) / al
        return (*_oklab_to_srgb(L, A, B), al)
    al = c1[3] * p1 + c2[3] * p2
    if al <= 0:
        return (0.0, 0.0, 0.0, 0.0)
    rgb = tuple(
        (c1[i] * c1[3] * p1 + c2[i] * c2[3] * p2) / al for i in range(3)
    )
    return (*rgb, al)


def to_argb(c: RGBA) -> int:
    r, g, b, a = c
    ri = int(math.floor(r * 255 + 0.5))
    gi = int(math.floor(g * 255 + 0.5))
    bi = int(math.floor(b * 255 + 0.5))
    ai = int(math.floor(a * 255 + 0.5))
    return (ai << 24) | (ri << 16) | (gi << 8) | bi


def hexstr(argb: int) -> str:
    return f"#{argb:08X}"


# ---------------------------------------------------------------------------
#  级联
# ---------------------------------------------------------------------------

BASE_ROOT_SELECTORS = {":root", ":host"}


def is_base_rule(rule: Rule) -> bool:
    sels = set(rule.selectors)
    if not (sels & BASE_ROOT_SELECTORS):
        return False
    # 只取全局基座；排除 @property / 组件作用域里的 :root（不存在）
    return True


def base_rank(rule: Rule) -> tuple[int, int]:
    # CSS 级联：@layer 内的规则优先级低于无层规则；@supports 只是条件包装，
    # 不改变层优先级，同一层内按源码顺序决胜。
    layered = any(c.lower().startswith("@layer") for c in rule.contexts)
    return (0 if layered else 1, rule.offset)


def theme_rules(rules: list[Rule], theme_selector: str, include_dark: bool) -> list[Rule]:
    wanted = {theme_selector}
    if include_dark:
        wanted.add(".dark")
    out = []
    for r in rules:
        sels = set(r.selectors)
        if sels & wanted:
            # 避免把 `.theme-zai-light .foo` 这类后代选择器算进来（精确匹配已排除）
            out.append(r)
    out.sort(key=lambda r: (_layer_rank(r), r.offset))
    return out


def _layer_rank(rule: Rule) -> int:
    return 1 if any(c.lower().startswith("@layer") for c in rule.contexts) else 0


def build_var_map(rules: list[Rule], theme_selector: str, include_dark: bool) -> dict[str, str]:
    base: list[Rule] = [r for r in rules if is_base_rule(r)]
    base.sort(key=base_rank)
    out: dict[str, str] = {}
    for r in base:
        for p, v in r.decls:
            if p.startswith("--"):
                out[p] = v
    for r in theme_rules(rules, theme_selector, include_dark):
        for p, v in r.decls:
            if p.startswith("--"):
                out[p] = v
    return out


# ---------------------------------------------------------------------------
#  Kotlin 输出
# ---------------------------------------------------------------------------

KOTLIN_HEADER = """\
// ---------------------------------------------------------------------------
//  ZaiColors.kt —— 3.17.1 网页 zai 皮肤（zai-dark / zai-light）语义令牌
//
//  由 design/tokens/generate_zai_theme.py 生成，【不要手改】。
//  上游：Reference_web/3.17.1/assets/index-BgVjt5KE.css（sha256 {sha}）
//  口径：`:root,:host` 基座 + `.theme-zai-dark` / `.theme-zai-light` 覆盖，
//        var() 递归、oklch/color-mix 按 colors.json _meta.conversion 转 sRGB。
//  兜底：{fallback_note}
//
//  主构造器仍走 181 个 ARGB Int（Color 是 value class，181 个 Color 参数会超
//  JVM 255 槽上限，见 Color.kt 文件头）。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

internal object ZaiColors {{
"""


def emit_kotlin(
    tokens: list[str],
    dark: dict[str, int],
    light: dict[str, int],
    sha: str,
    fallback_dark: list[str],
    fallback_light: list[str],
) -> str:
    def block(name: str, values: dict[str, int], doc: str) -> str:
        lines = [f"    /** {doc} */", f"    val {name}: ZemoteColors = ZemoteColors("]
        for tok in tokens:
            prop = kebab_to_camel(tok)
            argb = values[tok]
            # 与 Color.kt 一致：> Int.MAX_VALUE 的 ARGB 字面量在 Kotlin 里是 Long，
            # 必须显式 .toInt()，否则 Int 构造参数编译失败。
            literal = f"0x{argb:08X}" + (".toInt()" if argb > 0x7FFFFFFF else "")
            lines.append(f"        {prop}Argb = {literal},")
        lines.append("    )")
        return "\n".join(lines)

    fb = []
    if fallback_dark:
        fb.append(f"zai-dark 解析失败 {len(fallback_dark)} 条（沿用 ZemoteColors.Dark）：" + ", ".join(fallback_dark))
    if fallback_light:
        fb.append(f"zai-light 解析失败 {len(fallback_light)} 条（沿用 ZemoteColors.Light）：" + ", ".join(fallback_light))
    fallback_note = "；".join(fb) if fb else "无（181/181 全部解析成功）"
    body = KOTLIN_HEADER.format(sha=sha, fallback_note=fallback_note)
    body += block(
        "ZaiDark",
        dark,
        "zai-dark：html.dark + .theme-zai-dark（网页默认主题）",
    )
    body += "\n\n"
    body += block(
        "ZaiLight",
        light,
        "zai-light：html（无 .dark）+ .theme-zai-light",
    )
    body += "\n}\n"
    return body


def kebab_to_camel(name: str) -> str:
    if name.startswith("--color-"):
        name = name[len("--color-"):]
    parts = name.lstrip("-").split("-")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


# ---------------------------------------------------------------------------
#  main
# ---------------------------------------------------------------------------


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", ".."))
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--css",
        default=os.path.join(repo, "Reference_web", "3.17.1", "assets", "index-BgVjt5KE.css"),
    )
    ap.add_argument(
        "--colors-json", default=os.path.join(repo, "design", "tokens", "colors.json")
    )
    ap.add_argument(
        "--out",
        default=os.path.join(
            repo,
            "android/app/src/main/kotlin/app/zemote/ui/theme/ZaiColors.kt",
        ),
    )
    ap.add_argument("--check", action="store_true", help="只报告，不写文件")
    args = ap.parse_args()

    import hashlib

    raw = io.open(args.css, encoding="utf-8").read()
    sha = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    src = strip_comments(raw)
    rules = parse_rules(src)
    print(f"rules parsed: {len(rules)}")

    meta = json.load(io.open(args.colors_json, encoding="utf-8"))
    tokens = list(meta["semantic"].keys())
    if len(tokens) != 181:
        print(f"WARN semantic tokens = {len(tokens)}", file=sys.stderr)

    # 默认 Light/Dark 值从现有 Color.kt 取，作为解析失败兜底
    defaults: dict[str, dict[str, int]] = {"light": {}, "dark": {}}
    color_kt = os.path.join(os.path.dirname(args.out), "Color.kt")
    ck = io.open(color_kt, encoding="utf-8").read()
    for theme, key in (("Light", "light"), ("Dark", "dark")):
        m = re.search(
            r"val " + theme + r": ZemoteColors = ZemoteColors\((.*?)\n        \)\n",
            ck,
            re.S,
        )
        if not m:
            raise SystemExit(f"cannot locate ZemoteColors.{theme} in {color_kt}")
        for prop, val in re.findall(r"(\w+)Argb = 0x([0-9A-Fa-f]{8})", m.group(1)):
            tok = camel_to_kebab(prop)
            defaults[key][tok] = int(val, 16)

    results: dict[str, dict[str, int]] = {"dark": {}, "light": {}}
    fallbacks: dict[str, list[str]] = {"dark": [], "light": []}
    for key, selector, include_dark in (
        ("dark", ".theme-zai-dark", True),
        ("light", ".theme-zai-light", False),
    ):
        vmap = build_var_map(rules, selector, include_dark)
        resolved = 0
        for tok in tokens:
            try:
                argb = to_argb(parse_color(f"var({tok})", vmap))
                results[key][tok] = argb
                resolved += 1
            except Exception as exc:  # noqa: BLE001 - 逐条兜底并记录
                fallback = defaults[key].get(tok) or defaults["dark" if key == "light" else "light"].get(tok)
                if fallback is None:
                    print(f"FATAL {key} {tok}: {exc} (no default)", file=sys.stderr)
                    return 2
                results[key][tok] = fallback
                fallbacks[key].append(tok)
                print(f"fallback {key} {tok}: {exc}", file=sys.stderr)
        print(f"{key}: resolved {resolved}/{len(tokens)}")

    # 已知值抽查
    samples = {
        "--color-background": "#161616",
        "--color-foreground": "#D4D4D4",
        "--color-card": "#2B2B2B",
        "--color-border": "#FFFFFF1A",
        "--color-primary": "#FFFFFF",
    }
    for tok, expect in samples.items():
        got = hexstr(results["dark"][tok])
        print(f"check dark {tok}: {got} (expect {expect})")

    out = emit_kotlin(tokens, results["dark"], results["light"], sha, fallbacks["dark"], fallbacks["light"])
    if args.check:
        print(out[:400])
        return 0
    io.open(args.out, "w", encoding="utf-8", newline="\n").write(out)
    print(f"wrote {args.out}")
    return 0


def camel_to_kebab(prop: str) -> str:
    base = prop[:-4] if prop.endswith("Argb") else prop
    s = re.sub(r"([a-zA-Z])(\d+)", r"\1-\2", base)  # glow01 -> glow-01
    s = re.sub(r"([A-Z])", r"-\1", s).lower()
    return "--color-" + s


if __name__ == "__main__":
    raise SystemExit(main())
