/*
 * Minimal offline Mermaid-compatible renderer (UMD, no dependencies).
 *
 * The reference web bundle (Reference_web/3.15.3) does not ship a standalone
 * mermaid build: only the app's React wrapper plus lazy diagram chunks that
 * depend on the 6.6 MB application bundle.  This file is therefore a small
 * self-contained implementation covering the two diagram types requested:
 *
 *   - flowchart / graph (TD, TB, BT, LR, RL)
 *   - sequenceDiagram
 *
 * Unsupported diagram types reject the promise so the native side can fall
 * back to monospace source text.
 *
 * Public API (matches the tiny surface used by index.html):
 *   mermaid.initialize(config) -> void
 *   mermaid.render(id, text)   -> Promise<{ svg }>
 */
(function (global) {
  'use strict';

  var FONT = 'ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
  var FS = 13;
  var GAP_X = 38;
  var GAP_Y = 44;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function textLines(s) {
    return String(s == null ? '' : s)
      .replace(/<br\s*\/?>/gi, '\n')
      .split('\n')
      .map(function (l) { return l.trim(); });
  }

  function measure(line, fs) {
    var w = 0;
    for (var i = 0; i < line.length; i++) {
      var c = line.charCodeAt(i);
      w += c > 0x2e80 ? fs : fs * 0.58;
    }
    return w;
  }

  function maxLineWidth(lines, fs) {
    var w = 0;
    for (var i = 0; i < lines.length; i++) w = Math.max(w, measure(lines[i], fs));
    return w;
  }

  function cleanLabel(raw) {
    var s = String(raw || '').trim();
    if (s.length >= 2 && s.charAt(0) === '"' && s.charAt(s.length - 1) === '"') {
      s = s.slice(1, -1);
    }
    return s;
  }

  /* ------------------------------------------------------------------ */
  /* flowchart                                                          */
  /* ------------------------------------------------------------------ */

  function tokenizeFlowLine(line) {
    var toks = [];
    var i = 0;
    var n = line.length;
    while (i < n) {
      while (i < n && /\s/.test(line.charAt(i))) i++;
      if (i >= n) break;
      var rest = line.slice(i);

      // edge operator: optional '<' + 2+ dashes/equals/dot-dash + optional > x o
      var m = rest.match(/^(<?)(-{2,}|={2,}|\.-+)([>xo])?/);
      if (m && !/^[A-Za-z0-9_]/.test(m[2])) {
        var op = m[0];
        i += op.length;
        var label = '';
        var lm = line.slice(i).match(/^\s*\|([^|]*)\|/);
        if (lm) {
          label = lm[1];
          i += lm[0].length;
        } else if (/^(--|==|\.-)$/.test(op)) {
          // "A -- label --> B" form
          var am = line.slice(i).match(/^\s+([^-|>][^>]*?)\s+(-{2,}>|={2,}>|\.-+>|-{3,})/);
          if (am) {
            label = am[1].trim();
            op = am[2];
            i += am[0].length;
          }
        }
        toks.push({ t: 'e', op: op, label: label });
        continue;
      }

      m = rest.match(/^([A-Za-z_][A-Za-z0-9_]*(?:-(?!-)[A-Za-z0-9_]+)*)/);
      if (!m) { i++; continue; }
      var id = m[1];
      i += m[0].length;

      var shape = null;
      var text = id;
      var sm = line.slice(i).match(/^\s*(\(\([\s\S]*?\)\)|\[\[[\s\S]*?\]\]|\[\([\s\S]*?\)\]|\[[\s\S]*?\]|\([\s\S]*?\)|\{[\s\S]*?\}|>[\s\S]*?\])/);
      if (sm) {
        var raw = sm[1];
        i += sm[0].length;
        if (raw.slice(0, 2) === '((') { shape = 'circle'; text = raw.slice(2, -2); }
        else if (raw.slice(0, 2) === '([') { shape = 'stadium'; text = raw.slice(2, -2); }
        else if (raw.slice(0, 2) === '[[') { shape = 'rect'; text = raw.slice(2, -2); }
        else if (raw.slice(0, 2) === '[(') { shape = 'rect'; text = raw.slice(2, -2); }
        else if (raw.slice(0, 2) === '{{') { shape = 'rect'; text = raw.slice(2, -2); }
        else if (raw.charAt(0) === '{') { shape = 'diamond'; text = raw.slice(1, -1); }
        else if (raw.charAt(0) === '(') { shape = 'round'; text = raw.slice(1, -1); }
        else { shape = 'rect'; text = raw.slice(1, -1); }
        text = cleanLabel(text);
      }
      toks.push({ t: 'n', id: id, shape: shape, label: text });
    }
    return toks;
  }

  function parseFlow(src) {
    var nodes = {};
    var order = [];
    var edges = [];
    var dir = 'TD';
    var started = false;
    var lines = String(src).split(/\r?\n/);

    for (var li = 0; li < lines.length; li++) {
      var line = lines[li].trim();
      if (!line) continue;
      if (!started) {
        var hm = line.match(/^(?:flowchart|graph)\s+(TD|TB|BT|LR|RL)\b/i);
        if (hm) { dir = hm[1].toUpperCase(); started = true; }
        continue;
      }
      line = line.replace(/%%.*$/, '').trim();
      if (!line) continue;
      if (/^(subgraph\b|end\b|direction\b|classDef\b|class\b|style\b|linkStyle\b|click\b)/i.test(line)) continue;

      var toks = tokenizeFlowLine(line);
      for (var ti = 0; ti < toks.length; ti++) {
        var tk = toks[ti];
        if (tk.t === 'n') {
          var nd = nodes[tk.id];
          if (!nd) {
            nd = nodes[tk.id] = { id: tk.id, label: tk.id, shape: 'rect' };
            order.push(tk.id);
          }
          if (tk.shape) {
            nd.shape = tk.shape;
            nd.label = tk.label;
          }
        } else {
          var from = null, to = null;
          for (var a = ti - 1; a >= 0; a--) if (toks[a].t === 'n') { from = toks[a].id; break; }
          for (var b = ti + 1; b < toks.length; b++) if (toks[b].t === 'n') { to = toks[b].id; break; }
          if (from && to) edges.push({ from: from, to: to, op: tk.op, label: tk.label });
        }
      }
    }
    if (!order.length) throw new Error('empty flowchart');
    return { dir: dir, nodes: nodes, order: order, edges: edges };
  }

  function flowNodeSize(nd) {
    var lines = textLines(nd.label);
    var w = Math.max(46, maxLineWidth(lines, FS) + 30);
    var h = Math.max(34, lines.length * 18 + 20);
    if (nd.shape === 'diamond') { w *= 1.4; h *= 1.55; }
    else if (nd.shape === 'circle') { var d = Math.max(w, h); w = d; h = d; }
    else if (nd.shape === 'stadium') { w += 16; }
    nd.lines = lines;
    nd.w = Math.round(w);
    nd.h = Math.round(h);
  }

  function layoutFlow(g) {
    var ids = g.order;
    var nodes = g.nodes;
    var rank = {};
    var i;
    for (i = 0; i < ids.length; i++) rank[ids[i]] = 0;

    var guard = 0;
    var changed = true;
    while (changed && guard++ < ids.length + 2) {
      changed = false;
      for (i = 0; i < g.edges.length; i++) {
        var e = g.edges[i];
        if (rank[e.to] < rank[e.from] + 1 && rank[e.from] + 1 <= ids.length) {
          rank[e.to] = rank[e.from] + 1;
          changed = true;
        }
      }
    }

    var layers = {};
    var maxRank = 0;
    for (i = 0; i < ids.length; i++) {
      var r = rank[ids[i]];
      if (!layers[r]) layers[r] = [];
      layers[r].push(ids[i]);
      if (r > maxRank) maxRank = r;
    }

    // one barycenter pass for prettier ordering
    for (var pass = 0; pass < 2; pass++) {
      for (var rr = 1; rr <= maxRank; rr++) {
        var layer = layers[rr] || [];
        var prev = layers[rr - 1] || [];
        var pos = {};
        for (i = 0; i < prev.length; i++) pos[prev[i]] = i;
        var base = {};
        for (i = 0; i < layer.length; i++) base[layer[i]] = i;
        layer.sort(function (a, b) {
          var sa = 0, ca = 0, sb = 0, cb = 0;
          for (var k = 0; k < g.edges.length; k++) {
            var ed = g.edges[k];
            if (ed.to === a && pos[ed.from] !== undefined) { sa += pos[ed.from]; ca++; }
            if (ed.to === b && pos[ed.from] !== undefined) { sb += pos[ed.from]; cb++; }
          }
          var va = ca ? sa / ca : base[a];
          var vb = cb ? sb / cb : base[b];
          return va - vb || base[a] - base[b];
        });
      }
    }

    for (i = 0; i < ids.length; i++) flowNodeSize(nodes[ids[i]]);

    var vertical = g.dir === 'TD' || g.dir === 'TB' || g.dir === 'BT';
    var rankPrimary = [];
    var maxCross = 0;

    for (var rk = 0; rk <= maxRank; rk++) {
      var lay = layers[rk] || [];
      var primary = 0;
      var cross = 0;
      for (i = 0; i < lay.length; i++) {
        var nd = nodes[lay[i]];
        primary = Math.max(primary, vertical ? nd.h : nd.w);
        cross += vertical ? nd.w : nd.h;
      }
      if (lay.length > 1) cross += GAP_X * (lay.length - 1);
      rankPrimary[rk] = primary;
      if (cross > maxCross) maxCross = cross;
    }

    var offset = 0;
    for (var r2 = 0; r2 <= maxRank; r2++) {
      var l2 = layers[r2] || [];
      var start = 0;
      var size = 0;
      for (i = 0; i < l2.length; i++) {
        var nn = nodes[l2[i]];
        size += vertical ? nn.w : nn.h;
      }
      if (l2.length > 1) size += GAP_X * (l2.length - 1);
      start = (maxCross - size) / 2;
      for (i = 0; i < l2.length; i++) {
        var node = nodes[l2[i]];
        if (vertical) {
          node.x = Math.round(start);
          node.y = Math.round(offset);
          start += node.w + GAP_X;
        } else {
          node.x = Math.round(offset);
          node.y = Math.round(start);
          start += node.h + GAP_X;
        }
      }
      offset += rankPrimary[r2] + GAP_Y;
    }

    var width = vertical ? maxCross : Math.max(1, offset - GAP_Y);
    var height = vertical ? Math.max(1, offset - GAP_Y) : maxCross;

    if (g.dir === 'BT') {
      for (i = 0; i < ids.length; i++) nodes[ids[i]].y = height - nodes[ids[i]].y - nodes[ids[i]].h;
    } else if (g.dir === 'RL') {
      for (i = 0; i < ids.length; i++) nodes[ids[i]].x = width - nodes[ids[i]].x - nodes[ids[i]].w;
    }
    return { width: Math.ceil(width), height: Math.ceil(height) };
  }

  function anchorPoint(nd, tx, ty) {
    var cx = nd.x + nd.w / 2;
    var cy = nd.y + nd.h / 2;
    var dx = tx - cx;
    var dy = ty - cy;
    if (!dx && !dy) return [cx, cy];
    var sx = dx ? (nd.w / 2) / Math.abs(dx) : Infinity;
    var sy = dy ? (nd.h / 2) / Math.abs(dy) : Infinity;
    var s = Math.min(sx, sy);
    return [cx + dx * s, cy + dy * s];
  }

  function nodeShapeSvg(nd) {
    var x = nd.x, y = nd.y, w = nd.w, h = nd.h;
    var cx = x + w / 2, cy = y + h / 2;
    var cls = 'mm-node';
    if (nd.shape === 'circle') {
      return '<circle class="' + cls + '" cx="' + cx + '" cy="' + cy + '" r="' + (w / 2) + '"/>';
    }
    if (nd.shape === 'diamond') {
      return '<polygon class="' + cls + '" points="' +
        cx + ',' + y + ' ' + (x + w) + ',' + cy + ' ' + cx + ',' + (y + h) + ' ' + x + ',' + cy + '"/>';
    }
    var rx = 6;
    if (nd.shape === 'round') rx = 10;
    else if (nd.shape === 'stadium') rx = h / 2;
    return '<rect class="' + cls + '" x="' + x + '" y="' + y + '" width="' + w + '" height="' + h +
      '" rx="' + rx + '" ry="' + rx + '"/>';
  }

  function nodeTextSvg(nd) {
    var cx = nd.x + nd.w / 2;
    var cy = nd.y + nd.h / 2;
    var lines = nd.lines || [nd.label];
    var startY = cy - ((lines.length - 1) * 18) / 2 + 4.5;
    var out = '<text class="mm-node-label" x="' + cx + '" y="' + startY + '" text-anchor="middle">';
    for (var i = 0; i < lines.length; i++) {
      out += '<tspan x="' + cx + '" dy="' + (i ? 18 : 0) + '">' + esc(lines[i]) + '</tspan>';
    }
    return out + '</text>';
  }

  function renderFlow(src) {
    var g = parseFlow(src);
    var size = layoutFlow(g);
    var W = size.width;
    var H = size.height;
    var parts = [];
    parts.push('<svg xmlns="http://www.w3.org/2000/svg" class="mm-svg mm-flow" width="' + W + '" height="' + H +
      '" viewBox="0 0 ' + W + ' ' + H + '">');
    parts.push('<defs><marker id="mm-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="var(--mm-line)"/></marker></defs>');

    for (var ei = 0; ei < g.edges.length; ei++) {
      var e = g.edges[ei];
      var a = g.nodes[e.from];
      var b = g.nodes[e.to];
      if (!a || !b) continue;
      var acx = a.x + a.w / 2, acy = a.y + a.h / 2;
      var bcx = b.x + b.w / 2, bcy = b.y + b.h / 2;
      var p1 = anchorPoint(a, bcx, bcy);
      var p2 = anchorPoint(b, acx, acy);
      var dashed = e.op.indexOf('.') >= 0;
      var noArrow = /^-{3,}$/.test(e.op) || /^={3,}$/.test(e.op);
      var attrs = 'class="mm-edge' + (dashed ? ' mm-dashed' : '') + '" x1="' + p1[0].toFixed(1) + '" y1="' + p1[1].toFixed(1) +
        '" x2="' + p2[0].toFixed(1) + '" y2="' + p2[1].toFixed(1) + '"';
      if (!noArrow) attrs += ' marker-end="url(#mm-arrow)"';
      parts.push('<line ' + attrs + '/>');
      if (e.label) {
        var mx = (p1[0] + p2[0]) / 2;
        var my = (p1[1] + p2[1]) / 2 - 6;
        parts.push('<text class="mm-edge-label" x="' + mx.toFixed(1) + '" y="' + my.toFixed(1) + '" text-anchor="middle">' +
          esc(e.label) + '</text>');
      }
    }

    for (var ni = 0; ni < g.order.length; ni++) {
      var nd = g.nodes[g.order[ni]];
      parts.push(nodeShapeSvg(nd));
      parts.push(nodeTextSvg(nd));
    }

    parts.push('</svg>');
    return parts.join('');
  }

  /* ------------------------------------------------------------------ */
  /* sequenceDiagram                                                    */
  /* ------------------------------------------------------------------ */

  function parseSequence(src) {
    var actors = {};
    var order = [];
    var items = [];
    var started = false;
    var lines = String(src).split(/\r?\n/);

    function addActor(id, label) {
      if (!actors[id]) {
        actors[id] = { id: id, label: label || id };
        order.push(id);
      } else if (label) {
        actors[id].label = label;
      }
      return actors[id];
    }

    for (var li = 0; li < lines.length; li++) {
      var line = lines[li].trim();
      if (!line) continue;
      if (!started) {
        if (/^sequenceDiagram\b/i.test(line)) started = true;
        continue;
      }
      line = line.replace(/%%.*$/, '').trim();
      if (!line) continue;

      var m;
      if ((m = line.match(/^(?:participant|actor)\s+(\S+)(?:\s+as\s+(.+))?$/i))) {
        addActor(m[1], cleanLabel(m[2] || m[1]));
        continue;
      }
      if (/^(autonumber|activate|deactivate|title|box|end box)\b/i.test(line)) continue;

      if ((m = line.match(/^note\s+(over|left of|right of)\s+([^:]+):\s*(.+)$/i))) {
        var ns = m[2].split(',').map(function (s) { return s.trim(); });
        for (var q = 0; q < ns.length; q++) addActor(ns[q], ns[q]);
        items.push({ type: 'note', pos: m[1].toLowerCase(), actors: ns, text: cleanLabel(m[3]) });
        continue;
      }
      if ((m = line.match(/^(loop|alt|opt|par|critical|break|rect)\b\s*(.*)$/i))) {
        items.push({ type: 'frameStart', label: (m[1] + (m[2] ? ' ' + m[2] : '')).trim() });
        continue;
      }
      if ((m = line.match(/^else\b\s*(.*)$/i))) {
        items.push({ type: 'frameElse', label: m[1] });
        continue;
      }
      if (/^end\b/i.test(line)) {
        items.push({ type: 'frameEnd' });
        continue;
      }
      if ((m = line.match(/^(\S+?)\s*(-->>|->>|-->|->|--x|-x|--\)|-\))\s*(\S+?)\s*:\s*(.*)$/))) {
        addActor(m[1], m[1]);
        addActor(m[3], m[3]);
        items.push({ type: 'msg', from: m[1], to: m[3], op: m[2], text: cleanLabel(m[4]) });
        continue;
      }
    }
    if (!order.length) throw new Error('empty sequenceDiagram');
    return { actors: actors, order: order, items: items };
  }

  function layoutSequence(g) {
    var boxH = 34;
    var gap = 32;
    var top = 0;
    var msgTop = 58;
    var step = 40;
    var i;

    for (i = 0; i < g.order.length; i++) {
      var a = g.actors[g.order[i]];
      a.lines = textLines(a.label);
      a.w = Math.max(76, maxLineWidth(a.lines, FS) + 28);
      a.h = boxH;
    }

    var total = 0;
    for (i = 0; i < g.order.length; i++) total += g.actors[g.order[i]].w;
    total += gap * (g.order.length - 1);
    var x = 0;
    for (i = 0; i < g.order.length; i++) {
      var ac = g.actors[g.order[i]];
      ac.cx = x + ac.w / 2;
      x += ac.w + gap;
    }

    var y = msgTop;
    var frames = [];
    var stack = [];
    var maxRight = total;

    for (i = 0; i < g.items.length; i++) {
      var it = g.items[i];
      it.y = y;
      if (it.type === 'msg') {
        var fa = g.actors[it.from];
        var ta = g.actors[it.to];
        it.x1 = fa.cx;
        it.x2 = ta.cx;
        var lw = measure(it.text, 12);
        var mid = (it.x1 + it.x2) / 2;
        maxRight = Math.max(maxRight, mid + lw / 2, it.x1 + 20, it.x2 + 20);
        if (it.from === it.to) y += step + 18;
        else y += step;
      } else if (it.type === 'note') {
        var xs = it.actors.map(function (id) { return g.actors[id].cx; });
        var nmin = Math.min.apply(null, xs);
        var nmax = Math.max.apply(null, xs);
        if (nmin === nmax) { nmin -= 70; nmax += 70; }
        it.nx1 = nmin;
        it.nx2 = nmax;
        maxRight = Math.max(maxRight, nmax + 20);
        y += 46;
      } else if (it.type === 'frameStart') {
        stack.push({ item: it, startY: y - 16 });
        y += 8;
      } else if (it.type === 'frameElse') {
        it.y = y;
        y += 8;
      } else if (it.type === 'frameEnd') {
        var f = stack.pop();
        if (f) {
          f.item.endY = y + 4;
          frames.push(f.item);
        }
        y += 12;
      }
    }
    while (stack.length) {
      var ff = stack.pop();
      ff.item.endY = y + 4;
      frames.push(ff.item);
    }

    var W = Math.max(200, Math.ceil(maxRight + 10));
    var H = Math.ceil(y + 24);
    return { width: W, height: H, bottom: H - 10, frames: frames };
  }

  function renderSequence(src) {
    var g = parseSequence(src);
    var lay = layoutSequence(g);
    var W = lay.width;
    var H = lay.height;
    var parts = [];
    parts.push('<svg xmlns="http://www.w3.org/2000/svg" class="mm-svg mm-seq" width="' + W + '" height="' + H +
      '" viewBox="0 0 ' + W + ' ' + H + '">');
    parts.push('<defs><marker id="mm-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="var(--mm-line)"/></marker></defs>');

    var i;
    // lifelines
    for (i = 0; i < g.order.length; i++) {
      var a = g.actors[g.order[i]];
      parts.push('<line class="mm-life" x1="' + a.cx + '" y1="' + a.h + '" x2="' + a.cx + '" y2="' + lay.bottom + '"/>');
    }
    // frames behind everything else
    for (i = 0; i < lay.frames.length; i++) {
      var fr = lay.frames[i];
      parts.push('<rect class="mm-frame" x="4" y="' + fr.startY + '" width="' + (W - 8) + '" height="' +
        Math.max(20, fr.endY - fr.startY) + '" rx="4"/>');
      parts.push('<text class="mm-frame-label" x="12" y="' + (fr.startY + 14) + '">' + esc(fr.label) + '</text>');
    }
    // items
    for (i = 0; i < g.items.length; i++) {
      var it = g.items[i];
      if (it.type === 'msg') {
        var dashed = it.op.indexOf('--') === 0;
        if (it.from === it.to) {
          var xx = it.x1;
          parts.push('<path class="mm-edge' + (dashed ? ' mm-dashed' : '') + '" d="M' + xx + ',' + (it.y - 6) +
            ' h34 v22 h-34" marker-end="url(#mm-arrow)"/>');
          parts.push('<text class="mm-msg-label" x="' + (xx + 40) + '" y="' + (it.y + 2) + '" text-anchor="start">' +
            esc(it.text) + '</text>');
        } else {
          parts.push('<line class="mm-edge' + (dashed ? ' mm-dashed' : '') + '" x1="' + it.x1 + '" y1="' + it.y +
            '" x2="' + it.x2 + '" y2="' + it.y + '" marker-end="url(#mm-arrow)"/>');
          parts.push('<text class="mm-msg-label" x="' + ((it.x1 + it.x2) / 2) + '" y="' + (it.y - 7) +
            '" text-anchor="middle">' + esc(it.text) + '</text>');
        }
      } else if (it.type === 'note') {
        parts.push('<rect class="mm-note" x="' + it.nx1 + '" y="' + (it.y - 18) + '" width="' + (it.nx2 - it.nx1) +
          '" height="32" rx="4"/>');
        parts.push('<text class="mm-note-label" x="' + ((it.nx1 + it.nx2) / 2) + '" y="' + (it.y + 3) +
          '" text-anchor="middle">' + esc(it.text) + '</text>');
      } else if (it.type === 'frameElse') {
        parts.push('<line class="mm-frame-divider" x1="4" y1="' + it.y + '" x2="' + (W - 4) + '" y2="' + it.y + '"/>');
        if (it.label) parts.push('<text class="mm-frame-label" x="12" y="' + (it.y + 14) + '">' + esc(it.label) + '</text>');
      }
    }
    // actor boxes on top
    for (i = 0; i < g.order.length; i++) {
      var ac = g.actors[g.order[i]];
      parts.push('<rect class="mm-actor" x="' + (ac.cx - ac.w / 2) + '" y="0" width="' + ac.w + '" height="' + ac.h + '" rx="5"/>');
      var lines = ac.lines;
      var sy = ac.h / 2 - ((lines.length - 1) * 17) / 2 + 4;
      var out = '<text class="mm-actor-label" x="' + ac.cx + '" y="' + sy + '" text-anchor="middle">';
      for (var k = 0; k < lines.length; k++) {
        out += '<tspan x="' + ac.cx + '" dy="' + (k ? 17 : 0) + '">' + esc(lines[k]) + '</tspan>';
      }
      parts.push(out + '</text>');
    }

    parts.push('</svg>');
    return parts.join('');
  }

  /* ------------------------------------------------------------------ */
  /* public API                                                         */
  /* ------------------------------------------------------------------ */

  function render(id, text) {
    return new Promise(function (resolve) {
      var src = String(text == null ? '' : text).trim();
      var first = src.split(/\r?\n/)[0].trim();
      var svg;
      if (/^(flowchart|graph)\b/i.test(first)) {
        svg = renderFlow(src);
      } else if (/^sequenceDiagram\b/i.test(first)) {
        svg = renderSequence(src);
      } else {
        throw new Error('unsupported mermaid diagram: ' + (first || '(empty)'));
      }
      resolve({ svg: svg });
    });
  }

  global.mermaid = {
    initialize: function () {},
    render: render
  };
})(typeof window !== 'undefined' ? window : this);
