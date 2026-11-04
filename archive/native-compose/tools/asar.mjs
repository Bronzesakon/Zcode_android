#!/usr/bin/env node
// 只读读取 Electron app.asar，不执行其中任何代码。
//
// asar 布局（实测本机 340MB 包的头 16 字节：04000000 0c446f00 08446f00 04446f00）：
//   0..3   u32le = 4（pickle 自身长度字段）
//   4..7   u32le = 8 + jsonLen      → base = 8 + 该值
//   8..11  u32le = 4 + jsonLen
//   12..15 u32le = jsonLen
//   16..   jsonLen 字节 utf8 JSON 目录树，其后 4 字节对齐到 base
// 每个文件的 offset 相对 base。
//
// 用法（--asar 可省，默认本机 ZCode 桌面端）：
//   node tools/asar.mjs ls   [--asar P] [--depth N] [--filter SUBSTR]
//   node tools/asar.mjs cat  <innerPath> [--max N]
//   node tools/asar.mjs dump <innerPath> <outFile>
//   node tools/asar.mjs stat <substr>
//   node tools/asar.mjs grep <regex> [--ext .js,.mjs] [--max-hits N] [--context N] [--path-filter SUBSTR]
//
// grep 逐个文件流式扫描，只打印命中行的窗口，不整读大文件；>60MB 的文件跳过。

import fs from 'node:fs';
import path from 'node:path';

const DEFAULT_ASAR = 'C:/Users/wdn32/AppData/Local/Programs/ZCode/resources/app.asar';
const VALUE_FLAGS = new Set(['--asar', '--depth', '--filter', '--ext', '--max-hits', '--context', '--max', '--path-filter']);

const flags = {};
const pos = [];
for (let i = 3; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a.startsWith('--')) {
    if (VALUE_FLAGS.has(a)) flags[a] = process.argv[++i];
    else flags[a] = true;
  } else pos.push(a);
}
const asar = flags['--asar'] || DEFAULT_ASAR;

function openAsar(p) {
  const fd = fs.openSync(p, 'r');
  const head = Buffer.alloc(16);
  fs.readSync(fd, head, 0, 16, 0);
  const base = 8 + head.readUInt32LE(4);
  const jsonLen = head.readUInt32LE(12);
  const hb = Buffer.alloc(jsonLen);
  fs.readSync(fd, hb, 0, jsonLen, 16);
  return { fd, tree: JSON.parse(hb.toString('utf8')), base };
}

function walk(node, prefix, out) {
  for (const [name, v] of Object.entries(node.files || {})) {
    const full = prefix + name;
    if (v.files) walk(v, full + '/', out);
    else out.push({ path: full, size: v.size || 0, offset: Number(v.offset || 0) });
  }
}
function listAll(tree) { const out = []; walk(tree, '', out); return out; }
function readEntry(h, entry, max) {
  const len = max && max < entry.size ? max : entry.size;
  const buf = Buffer.alloc(len);
  fs.readSync(h.fd, buf, 0, len, h.base + entry.offset);
  return buf;
}
function find(h, inner) {
  const all = listAll(h.tree);
  return all.find(x => x.path === inner) || all.find(x => x.path.endsWith('/' + inner)) || all.find(x => x.path.includes(inner));
}

const cmd = process.argv[2];
const h = openAsar(asar);

if (cmd === 'ls') {
  const depth = +(flags['--depth'] || 2);
  const filter = flags['--filter'] || '';
  const all = listAll(h.tree);
  if (filter) {
    for (const e of all) if (e.path.toLowerCase().includes(filter.toLowerCase())) console.log(`${e.size}\t${e.path}`);
  } else {
    const seen = new Set();
    for (const e of all) {
      const parts = e.path.split('/');
      for (let d = 1; d <= Math.min(depth, parts.length); d++) {
        const key = parts.slice(0, d).join('/') + (d < parts.length ? '/' : '');
        if (!seen.has(key)) { seen.add(key); console.log(key); }
      }
    }
  }
  console.log(`# total files: ${all.length}`);
} else if (cmd === 'cat') {
  const e = find(h, pos[0]);
  if (!e) { console.error('not found: ' + pos[0]); process.exit(1); }
  process.stdout.write(readEntry(h, e, +(flags['--max'] || 0)));
} else if (cmd === 'dump') {
  const e = find(h, pos[0]);
  if (!e) { console.error('not found: ' + pos[0]); process.exit(1); }
  fs.mkdirSync(path.dirname(pos[1]), { recursive: true });
  fs.writeFileSync(pos[1], readEntry(h, e));
  console.log(`${e.size} bytes -> ${pos[1]}`);
} else if (cmd === 'stat') {
  for (const e of listAll(h.tree).filter(x => x.path.includes(pos[0]))) console.log(`${e.size}\t${e.path}`);
} else if (cmd === 'grep') {
  const re = new RegExp(pos[0], 'g');
  const exts = (flags['--ext'] || '.js,.mjs,.cjs,.ts,.json').split(',').filter(Boolean);
  const maxHits = +(flags['--max-hits'] || 200);
  const ctx = +(flags['--context'] || 60);
  const pf = (flags['--path-filter'] || '').toLowerCase();
  const all = listAll(h.tree).filter(e => exts.some(x => e.path.endsWith(x)) && (!pf || e.path.toLowerCase().includes(pf)));
  let hits = 0;
  for (const e of all) {
    if (hits >= maxHits) break;
    if (e.size > 60 * 1024 * 1024) continue;
    let buf;
    try { buf = readEntry(h, e); } catch { continue; }
    if (buf.subarray(0, 4096).includes(0)) continue;
    const s = buf.toString('utf8');
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(s)) !== null) {
      const a = Math.max(0, m.index - ctx), b = Math.min(s.length, m.index + m[0].length + ctx);
      const line = s.slice(0, m.index).split('\n').length;
      console.log(`--- ${e.path}:${line} @${m.index}`);
      console.log(s.slice(a, b).replace(/\n/g, '\\n'));
      if (++hits >= maxHits) break;
    }
  }
  console.log(`# hits: ${hits} / scanned files: ${all.length}`);
} else {
  console.error('usage: ls | cat | dump | stat | grep');
  process.exit(2);
}
fs.closeSync(h.fd);
