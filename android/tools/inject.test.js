/**
 * inject.js tests — the injected layer, exercised against a simulated page.
 *
 * inject.js is the other half of the risk: it patches Window/Document/WebSocket
 * inside someone else's production SPA at document-start, and a bug there breaks
 * the user's page rather than merely losing a notification. None of that can be
 * checked on a device without Android, so the page environment is faked here:
 * a minimal EventTarget/Document/Window tree plus a WebSocket that records what
 * was sent, with the shared fake desktop on the other end.
 *
 * Covered: idempotency, the read-only contract (the visibility hijack stays
 * off and the shell writes nothing to the page's socket while in the
 * foreground), hook transparency (statics, instanceof, no feedback loop), the
 * full active-subscribe handshake over the socket, passive reading of the
 * page's own stream, the title-matching task locator, and the promise that
 * credentials never reach the diagnostic log.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const {DesktopCore, encodeBody, fragment, snapshotWire} = require('./fake-desktop.js');

// 3.17.1 把页面手机壳的断点收窄成三条件，壳的 NARROW_QUERY 必须逐字跟着它。
const NARROW_QUERY = '(max-width: 767px) and (hover: none) and (pointer: coarse)';

const PROTOCOL_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'zcode-protocol.js');
const INJECT_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'inject.js');

// ---------------------------------------------------------------------------
// simulated page environment
//
// The classes are module-level, so prototype patches applied by one test would
// leak into the next. PRISTINE records the unpatched implementations and
// setupPage() restores them, which keeps each test isolated.
// ---------------------------------------------------------------------------

function FakeEventTarget() {
    this._listeners = Object.create(null);
}

FakeEventTarget.prototype.addEventListener = function (type, listener) {
    (this._listeners[type] || (this._listeners[type] = [])).push(listener);
};

FakeEventTarget.prototype.removeEventListener = function (type, listener) {
    const list = this._listeners[type];
    if (list) {
        this._listeners[type] = list.filter((fn) => fn !== listener);
    }
};

FakeEventTarget.prototype.dispatchEvent = function (event) {
    const list = this._listeners[event.type];
    if (list) {
        for (const listener of list.slice()) {
            listener.call(this, event);
        }
    }
    return true;
};

class FakeElement extends FakeEventTarget {
    constructor(tagName) {
        super();
        this.nodeType = 1;
        this.tagName = String(tagName).toUpperCase();
        this.children = [];
        this.parentElement = null;
        this._text = '';
        this._attributes = Object.create(null);
        this.dispatched = [];
        this.cursor = 'auto';
        this.isConnected = true;
        // 真实 DOM 里任何元素都有 rect；默认"可见且贴顶"，需要测离屏/零高的用例覆写它。
        this._rect = {top: 0, bottom: 100, height: 100, left: 0, right: 100, width: 100};
    }

    getBoundingClientRect() {
        return this._rect;
    }

    get childElementCount() {
        return this.children.length;
    }

    get textContent() {
        if (this.children.length === 0) {
            return this._text;
        }
        return this._text + this.children.map((child) => child.textContent).join('');
    }

    set textContent(value) {
        this._text = value;
        this.children = [];
    }

    setAttribute(name, value) {
        this._attributes[name] = value;
    }

    getAttribute(name) {
        return name in this._attributes ? this._attributes[name] : null;
    }

    hasAttribute(name) {
        return name in this._attributes;
    }

    appendChild(child) {
        child.parentElement = this;
        this.children.push(child);
        return child;
    }

    insertBefore(child, before) {
        const idx = this.children.indexOf(before);
        child.parentElement = this;
        if (idx < 0) {
            this.children.push(child);
        } else {
            this.children.splice(idx, 0, child);
        }
        return child;
    }

    get firstElementChild() {
        return this.children[0] || null;
    }

    get previousElementSibling() {
        const parent = this.parentElement;
        if (!parent) {
            return null;
        }
        const idx = parent.children.indexOf(this);
        return idx > 0 ? parent.children[idx - 1] : null;
    }

    scrollIntoView() {}

    dispatchEvent(event) {
        this.dispatched.push(event.type);
        return super.dispatchEvent(event);
    }
}

class FakeDocument extends FakeEventTarget {
    constructor() {
        super();
        this.body = new FakeElement('body');
    }

    hasFocus() {
        return false;
    }

    querySelectorAll(selector) {
        if (selector === 'body *') {
            const out = [];
            const walk = (node) => {
                for (const child of node.children) {
                    out.push(child);
                    walk(child);
                }
            };
            walk(this.body);
            return out;
        }
        // '.classname' 单类选择器（状态判定用：.bg-sidebar / .bg-header / .bg-background-win-alt）。
        if (typeof selector === 'string' && /^(\.[A-Za-z0-9_-]+)$/.test(selector)) {
            const name = selector.slice(1);
            return this.getElementsByClassName(name);
        }
        // '[attr]' 属性存在选择器（状态判定用：[data-workspace-sidebar-panel]）。
        // 从 <html> 起走，因为 pageState 分支把页面节点直接挂在 documentElement 下
        // （body 只是它的一个子节点）。
        const attrOnly = typeof selector === 'string' ? /^\[([A-Za-z0-9_-]+)\]$/.exec(selector) : null;
        if (attrOnly) {
            const out = [];
            const walk = (node) => {
                for (const child of node.children) {
                    if (child.hasAttribute(attrOnly[1])) {
                        out.push(child);
                    }
                    walk(child);
                }
            };
            walk(this.documentElement || this.body);
            return out;
        }
        return [];
    }

    querySelector(selector) {
        const out = this.querySelectorAll(selector);
        return out.length ? out[0] : null;
    }

    getElementsByClassName(name) {
        const out = [];
        const walk = (node) => {
            for (const child of node.children) {
                if (String(child.getAttribute('class') || '').split(/\s+/).indexOf(name) >= 0) {
                    out.push(child);
                }
                walk(child);
            }
        };
        walk(this.body);
        return out;
    }
}

/**
 * Minimal MutationObserver: records its targets and lets a test deliver one
 * batch by hand. Only installed for tests that opt into the page-state harness —
 * inject.js must also survive a page where the API is missing, and that is the
 * default (no-option) shape of setupPage().
 */
class FakeMutationObserver {
    constructor(callback) {
        this.callback = callback;
        this.targets = [];
        FakeMutationObserver.instances.push(this);
    }

    observe(target, options) {
        this.targets.push({target, options});
    }

    disconnect() {
        this.targets = [];
    }

    /** Test helper: run the callback once for every live observer. */
    static fire() {
        for (const observer of FakeMutationObserver.instances.slice()) {
            observer.callback([], observer);
        }
    }
}

FakeMutationObserver.instances = [];

/**
 * matchMedia stand-in: one instance per query, so a test can flip `matches` and
 * have the change listeners fire — which is how rotation / a system theme switch
 * reach the script.
 */
class FakeMediaQueryList {
    constructor(query, matches) {
        this.query = query;
        this.matches = matches;
        this.listeners = [];
    }

    addEventListener(type, listener) {
        if (type === 'change') {
            this.listeners.push(listener);
        }
    }

    removeEventListener(type, listener) {
        this.listeners = this.listeners.filter((fn) => fn !== listener);
    }

    setMatches(matches) {
        this.matches = matches;
        for (const listener of this.listeners.slice()) {
            listener({matches});
        }
    }
}

class FakeWindow extends FakeEventTarget {}

class FakeWebSocket extends FakeEventTarget {
    constructor(url, protocols) {
        super();
        this.url = url;
        this.protocols = protocols;
        this.readyState = FakeWebSocket.OPEN;
        this.sent = [];
        FakeWebSocket.instances.push(this);
    }

    send(data) {
        this.sent.push(data);
    }

    close() {
        this.readyState = FakeWebSocket.CLOSED;
        this.dispatchEvent({type: 'close'});
    }

    /** Test helper: deliver a relay frame to the page. */
    receive(frame) {
        this.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
    }
}

FakeWebSocket.CONNECTING = 0;
FakeWebSocket.OPEN = 1;
FakeWebSocket.CLOSING = 2;
FakeWebSocket.CLOSED = 3;
FakeWebSocket.instances = [];

const PRISTINE = {
    eventTargetAdd: FakeEventTarget.prototype.addEventListener,
    webSocketSend: FakeWebSocket.prototype.send,
    webSocketClose: FakeWebSocket.prototype.close
};

function setupPage(options) {
    const previous = new Map();
    const install = (name, value) => {
        if (!previous.has(name)) {
            previous.set(name, name in globalThis ? globalThis[name] : undefined);
        }
        globalThis[name] = value;
    };

    // Undo prototype patches left behind by a previous test.
    FakeEventTarget.prototype.addEventListener = PRISTINE.eventTargetAdd;
    FakeWebSocket.prototype.send = PRISTINE.webSocketSend;
    FakeWebSocket.prototype.close = PRISTINE.webSocketClose;
    FakeWebSocket.instances = [];
    delete globalThis.__zcodeShellInstalled;

    const document = new FakeDocument();
    const window = new FakeWindow();
    const posts = [];
    // 原生 config() 的真实形状：`passiveObserve` + `foreground` + `runningSessions`。
    // 2026-09-17：D7「订阅所有工作区」（`subscribeAll`）删除后这里不再有这个字段，
    // 所以每个测试跑的都是**只读壳**的默认配置——注入层不许再主动开桥。
    // 2026-09-28：`foreground` 可由测试注入（验证"后台加载的文档按真实前后台启动"）。
    const configValue = (options && options.config) || {passiveObserve: true};
    let configReads = 0;
    if (options && options.history) install('history', options.history);

    install('EventTarget', FakeEventTarget);
    install('Document', FakeDocument);
    install('Window', FakeWindow);
    install('document', document);
    install('window', window);
    install('WebSocket', FakeWebSocket);
    install('location', {href: 'https://zcode.z.ai/remote/v4?sid=redacted'});
    install('getComputedStyle', (el) => ({cursor: el.cursor}));
    install('MouseEvent', function (type) {
        return {type, bubbles: true};
    });
    install('PointerEvent', function (type) {
        return {type, bubbles: true};
    });
    install('ZCodeShell', {
        postMessage(json) {
            posts.push(JSON.parse(json));
        },
        config() {
            configReads += 1;
            return JSON.stringify(configValue);
        }
    });

    // ---------------------------------------------------------------------
    // Opt-in page-state harness.
    //
    // The page-state reporter needs a DOM the default fake does not have
    // (documentElement, getElementsByClassName, MutationObserver, matchMedia).
    // It is opt-in so the other tests keep exercising the *bare* page they were
    // written for — inject.js has to degrade quietly when those APIs are absent.
    // ---------------------------------------------------------------------
    const pageState = (options && options.pageState) || null;
    let mediaQueries = [];
    const dom = {classes: Object.create(null)};

    if (pageState) {
        const buildRoot = () => {
            const html = new FakeElement('html');
            html.appendChild(document.body);
            document.documentElement = html;
            return html;
        };
        // `noDocumentElement` reproduces the document-start race: the script runs
        // before <html> exists and must retry rather than give up.
        let root = (options && options.noDocumentElement) ? null : buildRoot();
        document.createElement = (tag) => {
            const el = new FakeElement(tag);
            el.style = {};
            return el;
        };
        document.getElementsByClassName = (name) => {
            const out = [];
            if (!root) {
                return out;
            }
            const walk = (node) => {
                for (const child of node.children) {
                    if (String(child.getAttribute('class') || '').split(/\s+/).indexOf(name) >= 0) {
                        out.push(child);
                    }
                    walk(child);
                }
            };
            walk(root);
            return out;
        };
        // Every element the page would have for a class, added/removed by name.
        dom.classes = Object.create(null);
        dom.pageRoot = new FakeElement('div');
        document.getElementById = (id) => id === 'root' && root ? dom.pageRoot : null;
        dom.materialiseRoot = () => {
            if (!root) {
                root = buildRoot();
            }
        };
        dom.set = (name, present) => {
            if (!root) {
                return;
            }
            const existing = document.getElementsByClassName(name);
            if (present && existing.length === 0) {
                const el = new FakeElement('div');
                el.setAttribute('class', name);
                root.appendChild(el);
            } else if (!present) {
                for (const el of existing) {
                    root.children = root.children.filter((child) => child !== el);
                }
            }
        };
        /**
         * 侧栏面板：真实页面里是 `div[data-workspace-sidebar-panel]`——窄屏
         * `max-md:bg-sidebar` 横置成贴顶导航条，宽屏是左侧竖栏。用数据属性造，
         * 顺带证明判据没有绑死 `.bg-sidebar` 类名（横向导航条的类名是
         * `max-md:bg-sidebar`，不匹配 `.bg-sidebar`）。
         */
        dom.rail = null;
        dom.setRail = (present) => {
            if (!root) {
                return null;
            }
            if (!present) {
                if (dom.rail) {
                    root.children = root.children.filter((child) => child !== dom.rail);
                    dom.rail = null;
                }
                return null;
            }
            if (!dom.rail) {
                dom.rail = new FakeElement('div');
                dom.rail.setAttribute('data-workspace-sidebar-panel', 'true');
                root.appendChild(dom.rail);
            }
            return dom.rail;
        };
        dom.setTheme = (theme) => {
            if (!root) {
                return;
            }
            if (theme === null) {
                delete root._attributes['data-zcode-browser-theme-surface'];
            } else {
                root.setAttribute('data-zcode-browser-theme-surface', theme);
            }
        };
        /**
         * The class the page stamps on <html> for its own theme — written by the
         * bundle's top-level IIFE, i.e. before React mounts. Passing `null` removes
         * it, which is the state the boot shell starts in (unthemed shell =
         * `boot-shell`, the `:root` fallback colour).
         */
        dom.setZaiTheme = (name) => {
            if (!root) {
                return;
            }
            const classes = String(root.getAttribute('class') || '')
                .split(/\s+/)
                .filter((cls) => cls && cls !== 'theme-zai-light' && cls !== 'theme-zai-dark');
            if (name) {
                classes.push(name);
            }
            if (classes.length > 0) {
                root.setAttribute('class', classes.join(' '));
            } else {
                delete root._attributes['class'];
            }
        };
        const media = (options && options.media) || {};
        mediaQueries = [];
        install('matchMedia', (query) => {
            let mql = mediaQueries.find((candidate) => candidate.query === query);
            if (!mql) {
                mql = new FakeMediaQueryList(query, media[query] === true);
                mediaQueries.push(mql);
            }
            return mql;
        });
        FakeMutationObserver.instances = [];
        // `noObserver` is the degraded engine: present document, absent API.
        if (!(options && options.noObserver)) {
            install('MutationObserver', FakeMutationObserver);
        }
    }

    // Fresh protocol module: inject.js reads it off the global.
    delete require.cache[require.resolve(PROTOCOL_PATH)];
    const protocol = require(PROTOCOL_PATH);

    // Heartbeats use setInterval and would otherwise keep Node alive.
    const realSetInterval = globalThis.setInterval;
    const intervals = [];
    globalThis.setInterval = function (fn, ms) {
        const handle = realSetInterval(fn, ms);
        intervals.push(handle);
        return handle;
    };

    delete require.cache[require.resolve(INJECT_PATH)];
    require(INJECT_PATH);

    const teardown = () => {
        for (const handle of intervals) {
            clearInterval(handle);
        }
        globalThis.setInterval = realSetInterval;
        for (const [name, value] of previous) {
            if (value === undefined) {
                delete globalThis[name];
            } else {
                globalThis[name] = value;
            }
        }
        delete globalThis.__zcodeShellInstalled;
        delete globalThis.__zcodeShellLocateTask;
        delete globalThis.__zcodeShellSetAppForeground;
        delete globalThis.__zcodeShellHeartbeat;
        delete globalThis.__zcodeShellReportPageState;
        delete globalThis.__zcodeShellPageStateHooked;
        delete globalThis.__zcodeShellExpectCarrier;
        delete globalThis.__zcodeShellFallback;
    };

    return {document, window, posts, protocol, configValue, configReads: () => configReads,
        dom, mediaQueries, teardown};
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function findPost(posts, event, predicate) {
    return posts.filter((p) => p.event === event && (!predicate || predicate(p.data)));
}

function allText(posts) {
    return posts.map((p) => JSON.stringify(p.data)).join('\n');
}

/**
 * Wires a fake desktop to the page socket. The pump drains what the client
 * sends; sentLog keeps a copy for assertions.
 */
function connectDesktop(socket) {
    const desktop = new DesktopCore({
        deliver: (payload) => socket.receive({type: 'data', payload})
    });
    const sentLog = [];
    const originalSend = socket.send.bind(socket);
    socket.send = (data) => {
        sentLog.push(data);
        originalSend(data);
    };
    const pump = setInterval(() => {
        while (socket.sent.length) {
            const text = socket.sent.shift();
            let frame;
            try {
                frame = JSON.parse(text);
            } catch (e) {
                continue;
            }
            if (frame.type === 'data' && frame.payload) {
                desktop.accepts(frame.payload);
            }
        }
    }, 5);
    return {desktop, sentLog, stop: () => clearInterval(pump)};
}

// ---------------------------------------------------------------------------
// hook safety
// ---------------------------------------------------------------------------

test('the injected script is idempotent', () => {
    const page = setupPage();
    try {
        delete require.cache[require.resolve(INJECT_PATH)];
        require(INJECT_PATH);
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.send(JSON.stringify({type: 'data', payload: {zcode_type: 'x'}}));
        assert.strictEqual(socket.sent.length, 1, 'send must still work exactly once');
        assert.strictEqual(findPost(page.posts, 'ready').length, 1,
            'the second install must bail out immediately');
    } finally {
        page.teardown();
    }
});

test('the WebSocket wrapper keeps statics, prototype and instanceof intact', () => {
    const page = setupPage();
    try {
        assert.strictEqual(globalThis.WebSocket.OPEN, 1);
        assert.strictEqual(globalThis.WebSocket.CONNECTING, 0);
        assert.strictEqual(globalThis.WebSocket.CLOSED, 3);
        assert.strictEqual(globalThis.WebSocket.name, 'WebSocket');
        const socket = new globalThis.WebSocket('wss://relay.example');
        assert.ok(socket instanceof globalThis.WebSocket, 'instanceof must hold');
        assert.ok(socket instanceof FakeWebSocket);
        assert.strictEqual(socket.url, 'wss://relay.example');
    } finally {
        page.teardown();
    }
});

test('the close log names the real caller, not the wrapper itself', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        await flush();

        // Regression pin. The wrapper builds its Error inside itself, so the naive
        // `stack.split('\n')[1]` names the WRAPPER on every close — and a round of
        // field work read that as "the page closed its own socket". The caller has
        // to survive the wrapper frames.
        const closeFromThePage = () => socket.close(1000, 'bye');
        closeFromThePage();
        await flush();

        const line = findPost(page.posts, 'diag')
            .map((p) => p.data.message)
            .filter((m) => m.indexOf('socket.close() 被调用') === 0)
            .pop();
        assert.ok(line, 'the close must be logged');
        assert.ok(line.includes('closeFromThePage'), line);
        assert.ok(!line.includes('wrappedClose'), 'the wrapper must not name itself: ' + line);
        assert.ok(line.includes('(1000)'), 'the close code must be reported: ' + line);
    } finally {
        page.teardown();
    }
});

// 2026-09-15 晚：可见性劫持停用。它挡不住 Chromium 的定时器节流（真机后台 7 分钟里
// 页面的 10s 心跳只跳了 6 次），却把页面自救用的生命周期事件全吞了——于是页面既不
// 知道自己去过后台，也不知道自己回来了（回前台后重订阅收不到 ack，对话 0 行，
// 只有重启应用才恢复）。现在：不再对页面说假话。
test('可见性劫持已停用：页面拿到真实可见性，生命周期事件照常送达', () => {
    const page = setupPage();
    try {
        assert.ok(!Object.getOwnPropertyDescriptor(globalThis.document, 'hidden'),
            'document.hidden must not be replaced by a spoofed getter');
        assert.ok(!Object.getOwnPropertyDescriptor(FakeDocument.prototype, 'visibilityState'),
            'visibilityState must not be replaced by a spoofed getter');
        assert.strictEqual(globalThis.document.hasFocus(), false,
            'hasFocus must keep the page\'s own answer (the fake document says false)');

        let fired = 0;
        globalThis.document.addEventListener('visibilitychange', () => fired++);
        globalThis.window.addEventListener('pagehide', () => fired++);
        globalThis.window.addEventListener('blur', () => fired++);
        const dispatch = (target, type) => {
            const list = target._listeners[type] || [];
            for (const listener of list.slice()) {
                listener.call(target, {type});
            }
        };
        dispatch(globalThis.document, 'visibilitychange');
        dispatch(globalThis.window, 'pagehide');
        dispatch(globalThis.window, 'blur');
        assert.strictEqual(fired, 3,
            'the page\'s own suspend/recover listeners must receive their events again');

        // Ordinary element events still work.
        const input = new FakeElement('input');
        let typed = 0;
        input.addEventListener('input', () => typed++);
        PRISTINE.eventTargetAdd.call(input, 'input', () => typed++);
        dispatch(input, 'input');
        assert.strictEqual(typed, 2, 'non-lifecycle events must pass through');
    } finally {
        page.teardown();
    }
});

test('不再给 document.onvisibilitychange 装空处理器（页面可以自己挂）', () => {
    const page = setupPage();
    try {
        let called = 0;
        const handler = () => {
            called += 1;
        };
        globalThis.document.onvisibilitychange = handler;
        assert.strictEqual(globalThis.document.onvisibilitychange, handler,
            'the page must keep the property it set');
        assert.strictEqual(called, 0, 'assignment alone must not call it');
    } finally {
        page.teardown();
    }
});

// 回前台"死链兜底"（healDeadLinkOnResume）：只有三条判据同时成立才重载页面。
// 这是"长后台回来重订阅收不到 ack、对话 0 行、只能重启应用"那个现场的对策。
test('回前台死链兜底：有帧进来＝页面自己恢复了，一帧都不许重载', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(30);

        // 退后台再回前台：此刻 lastInboundAt 还是刚才那帧 → 静默 <60s，兜底根本不启动。
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellSetAppForeground(true);
        // 观察窗内页面自己恢复了（来一帧）。
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(120);

        assert.strictEqual(reloads.length, 0, 'a live link must never be reloaded');
    } finally {
        restore();
        page.teardown();
    }
});

test('回前台死链兜底：静默＋观察窗零帧＋对话 0 行 → 重载一次', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        // 配对要先成立：回前台判定在"链路未就绪"时会提前返回（那时该由页面自己重连）。
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(30);

        // 体征要能读出"在对话视图里但 0 行"：这正是"重订阅没 ack"的形状。
        const timeline = new FakeElement('div');
        timeline.setAttribute('data-row-count', '0');
        page.document.querySelector = (selector) => {
            if (selector === '[data-mobile-page="chat"]') {
                return page.document.body;
            }
            if (selector === '[data-v4-timeline-scroll]') {
                return timeline;
            }
            return null;
        };

        // 真机没法为这一条去后台待 7 分钟，所以用诊断指令伪造"观测静默 120s"。
        assert.strictEqual(globalThis.__zcodeShellDiag('deadlink_test'), true);
        await wait(5400);

        assert.strictEqual(reloads.length, 1, 'a dead link with an empty conversation must be rebuilt');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('回前台兜底：静默')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('credentials never reach the diagnostic log', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.send(JSON.stringify({
            type: 'auth_init',
            role: 'terminal',
            device_sid: 'SECRET-DEVICE-SID',
            meta: {version: '1.0'}
        }));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);
        assert.ok(!allText(page.posts).includes('SECRET-DEVICE-SID'),
            'device_sid is a credential and must not be logged');
        assert.strictEqual(findPost(page.posts, 'ready').length, 1);
    } finally {
        page.teardown();
    }
});

test('our own injected frames are not re-observed as page traffic', async () => {
    // 这条测试原来用"壳自己发的 workspace-list-request"当"我们自己的帧"——
    // 那是 D7 主动开桥链的第一步，2026-09-17 已删。现在壳唯一会写页面 socket 的
    // 帧是后台补心跳的探针（前台一帧都不写，见"只读壳：前台心跳一帧都不写页面 socket"），
    // 所以改用它：探针发出去之后不许再有任何连带写入——一旦观察层把"我们自己的帧"
    // 当成页面流量回灌，客户端就会自激（这正是当年那条 feedback loop 的形状）。
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        globalThis.__zcodeShellSetAppForeground(false);
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), true, '后台补心跳要真的发出这一帧');
        await wait(250);
        const ours = socket.sent.slice(before)
            .map((raw) => {
                try {
                    return JSON.parse(raw);
                } catch (e) {
                    return null;
                }
            })
            .filter((frame) => frame && frame.type === 'pair_status_query');
        assert.strictEqual(ours.length, 1, 'exactly one probe frame of our own');
        assert.strictEqual(socket.sent.length, before + 1,
            'no feedback loop: our own frame must not make the shell write again');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// end-to-end through the page socket
// ---------------------------------------------------------------------------

test('只读壳：默认配置下配对之后注入层不主动开桥（D7 已删，2026-09-17）', async () => {
    // 这条测试原来是 "active subscription works end to end through the page socket"：
    // 它钉住的是 D7「订阅所有工作区」打开时壳会**在页面那条 socket 上**给每个工作区
    // 开桥 + 订索引。那条链已在 2026-09-17 删除（与页面自己的订阅争用 → 页面卡
    // "工作中"+转圈），等价断言因此反过来：**默认配置也必须和"关"完全一样**——
    // 配对之后零协议写入、零订阅，只跟随页面（跟随的正面证据见下一条 passive 测试）。
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const link = connectDesktop(socket);
        // 旧实现（D7 打开时）就是拿这份工作区列表去开桥的；留着它是为了证明
        // "网还张在那里，只是没人来撞"——fake desktop 只在收到 workspace-list-request
        // 时才会读它，所以它本身不会诱发任何流量。
        link.desktop.workspaces = [{workspacePath: '/repo/a', workspaceIdentity: 'ws-a'}];

        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        // 等过旧实现的起步窗（当年的 ACTIVE_START_DELAY_MS = 1.5s，已随 D7 删除）：
        // 这不是在等某个现存常量，而是给"万一有人把主动开桥调度器加回来"留出它当年
        // 最早会动手的那一刻——否则这条断言在时间上就没有对手。
        await wait(1900);

        assert.strictEqual(link.desktop.subscriptions.length, 0,
            '壳永不给自己开桥：默认配置与旧「订阅所有工作区＝关」必须完全一样');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-list-request')).length, 0,
            '配对之后不得再有 workspace-list-request（主动开桥链的第一步）');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-bridge-open')).length, 0,
            '不得有任何开桥请求');
        link.stop();
    } finally {
        page.teardown();
    }
});

test('passive mode follows the page stream without writing anything itself', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const link = connectDesktop(socket);

        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(600);

        // The page's own bridge: a ready notification, its event listen, then
        // the sessions-index event. None of this is ours.
        const pageBridge = 'page-bridge-1';
        socket.receive({type: 'data', payload: {
            zcode_type: 'workspace-bridge-ready',
            bridgeSessionId: pageBridge,
            bridge: {bridgeSessionId: pageBridge, workspaceKey: 'ws-page'}
        }});
        const listenBody = encodeBody(
            [page.protocol.REQ_EVENT_LISTEN, 5, 'zcode-agent', page.protocol.EVENT_SESSIONS_INDEX],
            {workspacePath: '/repo/page', workspaceIdentity: 'ws-page'});
        for (const payload of fragment(listenBody, pageBridge, 2)) {
            socket.send(JSON.stringify({type: 'data', payload}));
        }
        const fireBody = encodeBody([page.protocol.RES_EVENT_FIRE, 5], snapshotWire([
            {sessionId: 'sp', title: '页面里的任务', phase: 'prewarming', lastActivityAt: 3}
        ], 1, 'sessions-index/ws-page'));
        for (const payload of fragment(fireBody, pageBridge, 3)) {
            socket.receive({type: 'data', payload});
        }
        await wait(60);

        const updates = findPost(page.posts, 'sessions');
        assert.strictEqual(updates.length, 1);
        assert.strictEqual(updates[0].data.key, 'ws-page');
        assert.strictEqual(updates[0].data.source, 'passive');
        assert.strictEqual(updates[0].data.sessions[0].title, '页面里的任务');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-list-request')).length, 0,
            '只读壳必须零协议写入（D7 删除后这也包括默认配置）');
        link.stop();
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// 第三条源：页面自己的 controller/tasks-index
// ---------------------------------------------------------------------------

test('the page controller stream is forwarded to native as a passive frame', async () => {
    // 为什么单钉这一条（2026-09-18 真机）：壳"哪些任务在跑"原来只有两条腿——页面正在听的
    // 那**一个**工作区的 sessions-index，和已订阅会话的 turnHeader.state。用户在别的工作区里
    // 继续跑任务时 `runningTaskRefs()` 是空的：页面显示 7 个工作区 41 个任务、任务正在 default
    // 里跑，壳只看到另一个工作区的 2 个已完成。页面自己订的 controller 流是全局的，
    // 这条测试钉住"它会被转发给原生"，而且转发**不需要**我们知道那个工作区。
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const link = connectDesktop(socket);

        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(600);

        // 页面自己的 controller 桥：壳既不认识这个 bridge，也没订过这个 topic。
        const pageBridge = 'page-controller-bridge';
        const frame = {
            topic: 'controller/tasks-index',
            subscriptionId: 'sub-1',
            logEpoch: 'e1',
            fromSeq: 0,
            toSeq: 1,
            payload: {kind: 'snapshot', snapshot: {logEpoch: 'e1', tasks: [
                {
                    address: {workspacePath: 'C:\\ws\\default', workspaceIdentity: 'ws-default', taskId: 'sess_run'},
                    meta: {title: '正在跑的任务', workspacePath: 'C:\\ws\\default'},
                    liveStatus: 'running'
                }
            ]}}
        };
        const fireBody = encodeBody([page.protocol.RES_EVENT_FIRE, 7], frame);
        for (const payload of fragment(fireBody, pageBridge, 2)) {
            socket.receive({type: 'data', payload});
        }
        await wait(60);

        const posts = findPost(page.posts, 'controllertasks');
        assert.strictEqual(posts.length, 1, '运行态帧必须转给原生');
        assert.strictEqual(posts[0].data.kind, 'complete');
        assert.strictEqual(posts[0].data.frame.topic, 'controller/tasks-index');
        assert.strictEqual(posts[0].data.frame.payload.snapshot.tasks[0].liveStatus, 'running');
        assert.strictEqual(
            link.sentLog.filter((text) => text.includes('workspace-list-request')).length, 0,
            '只读壳：转发观测到的帧不等于写协议');
        link.stop();
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// task locator
// ---------------------------------------------------------------------------

test('the locator clicks the element carrying the task title', async () => {
    const page = setupPage();
    try {
        const row = new FakeElement('div');
        row.cursor = 'pointer';
        const label = new FakeElement('span');
        label.textContent = '重构登录模块';
        row.appendChild(label);
        page.document.body.appendChild(row);

        globalThis.__zcodeShellLocateTask('s1', '重构登录模块');
        await wait(400);
        assert.ok(row.dispatched.includes('click'),
            'the row must be clicked; posts=' + allText(page.posts) + ' dispatched=' + JSON.stringify(row.dispatched));
        assert.strictEqual(
            findPost(page.posts, 'diag', (data) => data.message.startsWith('已定位任务')).length, 1);
    } finally {
        page.teardown();
    }
});

test('the locator gives up quietly when the title is gone', async () => {
    const page = setupPage();
    try {
        const el = new FakeElement('span');
        el.textContent = '完全不同的内容';
        page.document.body.appendChild(el);

        globalThis.__zcodeShellLocateTask('s1', '找不到的任务标题');
        await wait(3600);
        assert.strictEqual(findPost(page.posts, 'sessions').length, 0);
        assert.strictEqual(
            findPost(page.posts, 'diag', (data) => data.level === 'error').length, 0,
            'a locator miss is not an error');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// background-survival instrument
//
// The migration doc's step 4 (§8) is "background the app for 30 minutes and
// confirm frames are still arriving" — the milestone that decides whether the
// whole thin-shell route stands. It cannot be run in CI, so the counters that
// make it decidable are pinned here instead: if `pairAcks` stopped counting, the
// survival verdict the app reports would be meaningless.
// ---------------------------------------------------------------------------

test('liveness counters report inbound frames, pairing acks and socket churn', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        // The desktop answers the client's heartbeat; that ack is the proof of a
        // live link even when no task happens to be running.
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        globalThis.__zcodeShellReportLiveness();
        await flush();

        const reports = findPost(page.posts, 'liveness');
        assert.ok(reports.length >= 1, 'a liveness report must be posted');
        const last = reports[reports.length - 1].data;
        assert.ok(last.inboundFrames >= 2, 'inbound frames must be counted');
        assert.ok(last.pairAcks >= 2, 'pair_status_ack frames must be counted separately');
        assert.strictEqual(last.socketsOpened, 1);
        assert.strictEqual(last.socketsClosed, 0);
        assert.strictEqual(last.paired, true);
        assert.strictEqual(last.deviceSidKnown, true);
        assert.strictEqual(last.socketState, 1, 'the socket must report itself OPEN');
        assert.ok(last.lastInboundAgoMs >= 0, 'the age of the last inbound frame must be reported');

        // After a close the app can distinguish "link died" from "nothing happened".
        socket.close();
        globalThis.__zcodeShellReportLiveness();
        await flush();
        const afterClose = findPost(page.posts, 'liveness').pop().data;
        assert.strictEqual(afterClose.socketsClosed, 1);
        assert.strictEqual(afterClose.paired, false);
    } finally {
        page.teardown();
    }
});

test('the perf line carries the link counters that judge background reconnects', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        // One pump-driven tick while backgrounded. Both numbers have to end up on
        // the line: the probe is what should keep the page's own ack watchdog
        // quiet, and the ack is what proves the desktop still answers — a field
        // log without these counters could not tell "we stopped probing" from
        // "the page reconnected anyway".
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellHeartbeat();
        // The counters are reported BEFORE the tick sends its probe, so the window
        // that contains both a probe and an ack is the next one. Waiting past the
        // rate gate (5s) is what lets a second tick through at all.
        await wait(5100);
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        globalThis.__zcodeShellHeartbeat();
        await flush();

        const lines = findPost(page.posts, 'diag')
            .map((p) => p.data.message)
            .filter((m) => m.indexOf('页面开销') === 0);
        assert.ok(lines.length >= 2, 'a perf line per tick, even when only the link was active');
        const line = lines.filter(
            (m) => m.includes('链路 ack 1') && m.includes('探针 1')
        )[0];
        assert.ok(line, 'one window must show both the probe and the ack: ' + lines.join(' | '));
        assert.ok(line.includes('paired true'), line);
        assert.ok(line.includes('socket 1'), line);
        assert.ok(line.includes('页面心跳 '), line);
    } finally {
        page.teardown();
    }
});

test('the page-heartbeat counter sees the page and not our own probe', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        // The page's own heartbeat, then ours from the native pump. Ours is sent
        // while `injecting` is set, so it must not inflate the page's count — the
        // whole point is to tell whether the PAGE's timer is still running while
        // hidden.
        socket.send(JSON.stringify({type: 'pair_status_query', device_sid: 'sid-1', client_ts: 1}));
        globalThis.__zcodeShellHeartbeat();
        await flush();

        const line = findPost(page.posts, 'diag')
            .map((p) => p.data.message)
            .filter((m) => m.indexOf('页面开销') === 0)
            .pop();
        assert.ok(line.includes('页面心跳 1'), line);
        assert.ok(line.includes('探针 0'), 'our probe is counted only after the report: ' + line);
    } finally {
        page.teardown();
    }
});

test('liveness reporting is safe before any socket exists', () => {
    const page = setupPage();
    try {
        globalThis.__zcodeShellReportLiveness();
        const last = findPost(page.posts, 'liveness').pop().data;
        assert.strictEqual(last.socketState, -1);
        assert.strictEqual(last.lastInboundAgoMs, -1);
        assert.strictEqual(last.paired, false);
    } finally {
        page.teardown();
    }
});

// The foreground service drives this while the app is backgrounded, because the
// page's own setInterval is suspended there (measured on device: zero ticks in
// 10+ minute background windows). Two drivers now exist, so the gap guard — not
// the interval — is what keeps the heartbeat from being doubled.
test('the native pump can drive a heartbeat, and the tick is rate limited', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        globalThis.__zcodeShellSetAppForeground(false);
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), true, 'the first pumped tick must be accepted');
        const queries = socket.sent.slice(before)
            .map((raw) => {
                try {
                    return JSON.parse(raw);
                } catch (e) {
                    return null;
                }
            })
            // 控制帧是**顶层 `type`**（页面自己的客户端就是这么发的——本文件伪造页面
            // 心跳那行用的也是顶层形状）。这里原先按 `frame.payload.type` 计数，等于
            // 把壳的封装 bug 钉死在测试里：真机上那种帧从来没被服务端 ack 过。
            .filter((frame) => frame && frame.type === 'pair_status_query');
        assert.strictEqual(queries.length, 1, 'a pumped tick must put exactly one pair_status_query on the wire');
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), false, 'a tick inside the gap must be dropped');

        globalThis.__zcodeShellReportLiveness();
        await flush();
        const last = findPost(page.posts, 'liveness').pop().data;
        assert.strictEqual(last.backgroundTicks, 1, 'the survival verdict reads this counter');
        assert.ok(
            last.backgroundFirstTickDelayMs >= 0,
            'the delay of the first background tick is what separates a real background heartbeat from a resume burst'
        );
    } finally {
        page.teardown();
    }
});

// 只读壳的核心承诺：**前台一个字节都不往页面的连接里写**。页面自己的 10s 心跳
// 在前台是准的（真机日志里 `页面心跳` 就是 ~1/10s），壳再插一帧只是往它正用着的
// 连接上加噪声；而"壳不碰页面链路"正是这一版政策的全部意义。
test('只读壳：前台心跳一帧都不写页面 socket', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        // 返回 true ＝ 这一跳被接受（没被 5s 间隔门吃掉），所以"没有新帧"这件事
        // 只可能是只读门挡下的，而不是限流挡下的。
        assert.strictEqual(globalThis.__zcodeShellHeartbeat(), true, 'the tick itself is accepted');
        assert.strictEqual(
            socket.sent.length, before,
            'a foreground tick must not write anything into the page socket'
        );
    } finally {
        page.teardown();
    }
});

// The resume posture (borrowed from the reference client): the decision is made
// on the frames that were still arriving, not on the ack clock. Closing the
// socket is the heaviest thing this layer can do — the page's own recovery is
// what follows it, and that costs a full re-open of workspaces and tasks — so it
// must never happen to a link that is demonstrably alive.
test('只读壳：回前台对活的链路连一帧都不写（更不许拆）', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(1700);

        const before = socket.sent.length;
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellSetAppForeground(true);
        await flush();

        assert.strictEqual(socket.readyState, FakeWebSocket.OPEN, 'a live link must not be torn down');
        assert.strictEqual(
            socket.sent.length, before,
            'the resume must not write into the live page socket either'
        );
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳不介入')).length === 1);
    } finally {
        page.teardown();
    }
});

test('a throwing DOM query is contained, never propagated', async () => {
    const page = setupPage();
    try {
        page.document.querySelectorAll = () => {
            throw new Error('detached');
        };
        globalThis.__zcodeShellLocateTask('s1', 'x');
        await wait(400);
        // Matched on the message, not just the level: the assertion is about the
        // locator containing its own failure, and counting every warning made it
        // fail the moment an unrelated one was added.
        const warned = findPost(page.posts, 'diag', (data) =>
            data.level === 'warn' && String(data.message).indexOf('定位脚本出错') === 0);
        assert.strictEqual(warned.length, 1, 'the failure must be downgraded to a warning');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// page visual state -> native status bar surface
//
// The strip behind the status bar is painted natively from a fixed table, so the
// only thing this layer owes it is the state *name* (and the theme the page
// resolved), kept in step with the DOM as the page boots, navigates, rotates and
// switches theme.
// ---------------------------------------------------------------------------

/** The `pagestate` reports the shell received, in order. */
function pageStates(posts) {
    return findPost(posts, 'pagestate').map((p) => p.data.state + '/' + p.data.theme);
}

test('handoff returns to the first page frame and never hides workspace connection status', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        await flush();
        assert.strictEqual(findPost(page.posts, 'pagestate').at(-1).data.presentation, 'loading');
        page.dom.set('zcode-boot-loading', true);
        // 真实页面里主题类是主包顶层 IIFE 在 React 挂载**之前**挂上的，所以
        // 首帧之后看到的壳与随后的状态页必然同色（boot）。这里照实补上，
        // 否则测的是一个页面上不存在的「未着主题就进 React」时序。
        page.dom.setZaiTheme('theme-zai-light');
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        const logo = findPost(page.posts, 'pagestate').at(-1).data;
        assert.strictEqual(logo.presentation, 'logo', 'show the page own logo as soon as it renders');
        page.dom.set('zcode-boot-loading', false);
        page.dom.pageRoot.appendChild(new FakeElement('section'));
        FakeMutationObserver.fire();
        await flush();
        const status = findPost(page.posts, 'pagestate').at(-1).data;
        assert.strictEqual(status.presentation, 'page', 'unpaired connection cards must already be exposed');
        assert.strictEqual(status.state, logo.state, 'the status and logo share the boot colour, not the same cover duration');
        assert.strictEqual(status.documentId, findPost(page.posts, 'ready')[0].data.documentId);
        assert.ok(status.documentId);
        socket.receive({type: 'pair_status_ack', pair_status: 'waiting'});
        await flush();
        assert.strictEqual(findPost(page.posts, 'pagestate').at(-1).data.presentation, 'page',
            'waiting for pairing must not bring the logo cover back');
        socket.receive({type: 'auth_ack', pair_status: 'matched'});
        await flush();
        assert.strictEqual(findPost(page.posts, 'pagestate').at(-1).data.presentation, 'page',
            'workspace loading must not bring the logo cover back either');
    } finally {
        page.teardown();
    }
});

test('foreground KICK remains visible to page listeners and is reported as a terminal failure', async () => {
    const page = setupPage({pageState: true});
    try {
        page.dom.set('bg-background-win-alt', true);
        const socket = new globalThis.WebSocket('wss://relay.example');
        const received = [];
        socket.addEventListener('message', (event) => received.push(JSON.parse(event.data)));
        socket.receive({type: 'auth_ack', pair_status: 'matched'});
        await flush();
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        await flush();
        const failure = findPost(page.posts, 'pagefailure').at(-1).data;
        assert.strictEqual(failure.kind, 'kicked');
        assert.strictEqual(failure.foreground, true);
        assert.strictEqual(failure.documentId, findPost(page.posts, 'ready')[0].data.documentId);
        assert.strictEqual(received.at(-1).code, 'KICKED', 'the observer must not swallow or rewrite KICK');
        assert.strictEqual(findPost(page.posts, 'pagestate').at(-1).data.presentation, 'kicked',
            'stale main DOM and paired flags cannot report the kicked page as usable');
    } finally {
        page.teardown();
    }
});

test('background KICK and other relay failures keep their origin and original page event', async () => {
    const page = setupPage({config: {passiveObserve: true, foreground: false}});
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const received = [];
        socket.addEventListener('message', (event) => received.push(JSON.parse(event.data)));
        socket.receive({type: 'error', code: 'KICKED'});
        const background = findPost(page.posts, 'pagefailure').at(-1).data;
        assert.strictEqual(background.foreground, false);
        assert.strictEqual(background.kind, 'kicked');
        socket.receive({type: 'error', code: 'AUTH_FAILED'});
        const failure = findPost(page.posts, 'pagefailure').at(-1).data;
        assert.strictEqual(failure.kind, 'error');
        assert.strictEqual(failure.documentId, background.documentId);
        assert.deepStrictEqual(received.map((event) => event.code), ['KICKED', 'AUTH_FAILED']);
        await flush();
    } finally {
        page.teardown();
    }
});

test('the page reports its visual state, and only when it changes', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        // Boot shell is up and the page has not stamped its theme class yet: the
        // unthemed shell is the one that falls back to the :root token.
        page.dom.set('zcode-boot-loading', true);
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['boot-shell/light'],
            'the unthemed boot shell is the first state');

        // The page's own top-level IIFE stamps the theme class: same shell, now
        // themed — and it is the same colour the status pages use.
        page.dom.setZaiTheme('theme-zai-light');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['boot-shell/light', 'boot/light'],
            'themed shell and status pages share one name');

        // React mounts: the boot shell goes away and the control view appears.
        page.dom.set('zcode-boot-loading', false);
        page.dom.set('bg-background-win-alt', true);
        page.dom.set('bg-header', true);
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts),
            ['boot-shell/light', 'boot/light', 'main-header/light'],
            'a narrow page puts its own title bar under the status bar');

        // A second mutation with nothing changed must not report again.
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(pageStates(page.posts).length, 3, 'unchanged state is not re-reported');

        // The page's theme is the page's own decision, not the system's.
        page.dom.setTheme('dark');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-header/dark']);
    } finally {
        page.teardown();
    }
});

test('状态判定：窄屏首页/任务列表（无贴顶标题栏）→ boot（整页背景色）', async () => {
    // 2026-11-05 用户真机报：概览页条带 #202020 压在 #161616 页面上。
    // 根因：旧判定只按宽窄二分，首页没有贴顶 .bg-header 也被给了 main-header。
    // 照鸿蒙 computeState：贴顶 header 才是 main-header；否则窄屏一律 boot（bg-background）。
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.setZaiTheme('theme-zai-dark');
        page.dom.setTheme('dark');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['boot/dark'],
            'the overview top surface is bg-background, not the header colour');

        // header 在 DOM 里但不贴顶（对话框/侧栏里的离屏头部）同样不算。
        page.dom.set('bg-header', true);
        const header = document.getElementsByClassName('bg-header')[0];
        header._rect = {top: 40, bottom: 140, height: 100, left: 0, right: 100, width: 100};
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['boot/dark'],
            'an off-screen header must not claim the top');
    } finally {
        page.teardown();
    }
});

test('状态判定：贴顶导航条（窄屏）优先于内容标题栏 → main-surface', async () => {
    // 2026-11-05 用户报：加载页/工作区主页/设置页/对话详情页条带都有色差。
    // 3.17.1 快照实证：web 远控外壳在窄屏把侧栏面板横置成贴顶导航条
    // （div[data-workspace-sidebar-panel] + max-md:bg-sidebar），内容区（含对话页
    // 自己的 bg-header 标题栏）全部在它**下面**；两者都是 --color-sidebar。
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        page.dom.setZaiTheme('theme-zai-light');
        page.dom.setTheme('light');
        page.dom.setRail(true);
        page.dom.set('bg-header', true);
        const header = document.getElementsByClassName('bg-header')[0];
        header._rect = {top: 44, bottom: 88, height: 44, left: 0, right: 100, width: 100};
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-surface/light'],
            'the top navigation bar is the surface under the status bar');

        // 设置页：没有自己的标题栏，贴顶的只有导航条。
        page.dom.set('bg-header', false);
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-surface/light'],
            'settings has no header of its own — the navigation bar still owns the strip');

        // 导航面不在顶部（侧栏折叠后的离屏面板）：不许抢态，落回整页背景。
        page.dom.rail._rect = {top: 48, bottom: 148, height: 100, left: 0, right: 100, width: 100};
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['boot/light'],
            'a navigation panel that does not touch the top must not claim the strip');
    } finally {
        page.teardown();
    }
});

test('状态判定：宽屏左栏与标题栏同时贴顶时按左列（main-surface）', async () => {
    // 用户口径（2026-11-05）：平板式布局的状态栏取色对齐左列背景色。
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: false}});
    try {
        page.dom.setZaiTheme('theme-zai-light');
        page.dom.setTheme('light');
        page.dom.setRail(true);
        page.dom.set('bg-header', true);
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-surface/light'],
            'the left rail wins over the content header');

        // 左栏折叠/不存在时才轮到内容区标题栏。
        page.dom.setRail(false);
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-header/light']);
    } finally {
        delete globalThis.getComputedStyle;
        page.teardown();
    }
});

test('状态判定：设置页左列（透明图标栏）露出窗口外框 → main-surface', async () => {
    // 用户 2026-11-05 两次指正：设置页条带对齐"左侧窄列的较暗背景"。设置页左列
    // 68px 图标栏自身全透明，命中的是窗口外框 --color-background-win-alt
    // (#ECECEE/#2B2B2B)。判据用左上角命中链，不猜宽窄。
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: false}});
    try {
        page.dom.setZaiTheme('theme-zai-light');
        page.dom.setTheme('light');
        const frame = new FakeElement('div');
        frame.setAttribute('class', 'flex h-dvh flex-col overflow-hidden bg-background-win-alt');
        const rail = new FakeElement('div');
        rail.setAttribute('class', 'h-12 [app-region:drag]');
        const settings = new FakeElement('section');
        settings.setAttribute('data-settings-content-frame', 'true');
        frame.appendChild(rail);
        frame.appendChild(settings);
        document.documentElement.appendChild(frame);
        document.elementFromPoint = () => rail;
        // 采样色：注入层读左上角那一点的计算样式（窗口外框 #ECECEE）。
        globalThis.getComputedStyle = (el) => ({
            backgroundColor: el === rail ? 'rgb(236, 236, 238)' : 'rgba(0, 0, 0, 0)',
        });
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-surface/light'],
            'the settings page takes the left rail colour, not the panel colour');
        assert.strictEqual(
            findPost(page.posts, 'pagestate').at(-1).data.color, '#ececee',
            'the sampled top colour rides along with the state name');

        // 对话页：左上角命中 bg-header → 标题栏色。
        page.dom.set('bg-header', true);
        const header = document.getElementsByClassName('bg-header')[0];
        header.appendChild(new FakeElement('button'));
        document.elementFromPoint = () => header.children[0];
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-header/light']);
    } finally {
        page.teardown();
    }
});

test('crossing the page\'s breakpoint without a DOM change is still reported', async () => {
    // Rotation, split screen and foldables change the layout without mutating the
    // DOM, so the media query has to be watched in its own right.
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.set('bg-header', true);
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-header/light']);

        const narrow = page.mediaQueries.find((mql) => mql.query === NARROW_QUERY);
        assert.ok(narrow, 'the reporter must watch the page\'s own breakpoint');
        // 宽屏：顶部是 workspace 外壳而不是页面标题栏——把贴顶 header 拿掉再切宽。
        page.dom.set('bg-header', false);
        narrow.setMatches(false);
        await flush();
        assert.deepStrictEqual(pageStates(page.posts).slice(-1), ['main-surface/light'],
            'wide layout means the shell area is the top surface');
    } finally {
        page.teardown();
    }
});

test('the native side can ask for a fresh report after a background stint', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.set('bg-header', true);
        page.dom.setTheme('light');
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(pageStates(page.posts).length, 1);

        // A system theme switch while the app was away leaves the DOM untouched,
        // so nothing would have been reported: the forced read is the only way.
        page.dom.setTheme('light');
        globalThis.__zcodeShellReportPageState();
        await flush();
        assert.strictEqual(pageStates(page.posts).length, 2, 'a forced report bypasses the dedupe');
    } finally {
        page.teardown();
    }
});

test('a page without MutationObserver still boots, and still follows its breakpoint', async () => {
    // Degradation, not failure: without MutationObserver the DOM is no longer
    // re-checked, but the initial read and the media-query watchers still work —
    // so the bar is correct for the layout the page boots into and keeps up with
    // rotation and a system theme switch.
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}, noObserver: true});
    try {
        page.dom.set('bg-background-win-alt', true);
        page.dom.set('bg-header', true);
        page.dom.setTheme('light');
        await flush();
        assert.deepStrictEqual(
            pageStates(page.posts), ['main-header/light'],
            'the state is read once even without an observer');
        const narrow = page.mediaQueries.find((mql) => mql.query === NARROW_QUERY);
        page.dom.set('bg-header', false);
        narrow.setMatches(false);
        await flush();
        assert.deepStrictEqual(
            pageStates(page.posts).slice(-1), ['main-surface/light'],
            'rotation is still followed');
        assert.ok(
            findPost(page.posts, 'diag', (data) => String(data.message).indexOf('缺少 MutationObserver') >= 0,).length === 1,
            'and the degradation is stated once');
        assert.ok(findPost(page.posts, 'ready').length === 1, 'the rest of the layer still comes up');
    } finally {
        page.teardown();
    }
});

test('a document-start arrival before <html> exists retries instead of giving up', async () => {
    // The race installScrollbarWidth already guards against. Giving up here would
    // be silent and permanent for that document: no status-bar colour at all.
    const page = setupPage({
        pageState: true,
        noDocumentElement: true,
        media: {[NARROW_QUERY]: true},
    });
    try {
        assert.deepStrictEqual(pageStates(page.posts), [],
            'nothing can be read before the tree exists');
        // <html> arrives; the page fires DOMContentLoaded.
        page.dom.materialiseRoot();
        page.document.dispatchEvent({type: 'DOMContentLoaded'});
        page.dom.set('bg-background-win-alt', true);
        page.dom.set('bg-header', true);
        page.dom.setTheme('dark');
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(pageStates(page.posts), ['main-header/dark'],
            'the reporter must install itself once the tree exists');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// header refresh button（§8）——壳在对话页头部主题按钮左侧注入的「刷新」
//
// 契约：外观克隆主题按钮的 className（不硬编码类名）、锚点是头部行内最后一个
// button[aria-haspopup="menu"]（locale 无关）、按钮离开 DOM（React 重挂头部）
// 后借页面状态观察器的同一拍重建、点击做进程内 reload。
// ---------------------------------------------------------------------------

/** A chat header row the way the page renders it: [back button, title, theme menu]. */
function stubChatHeader(page) {
    const theme = new FakeElement('button');
    theme.setAttribute('aria-haspopup', 'menu');
    theme.setAttribute('aria-label', '选择主题');
    theme.className = 'ghost icon-sm-classes';
    const row = new FakeElement('div');
    row.querySelectorAll = (sel) => (sel === 'button[aria-haspopup="menu"]' ? [theme] : []);
    row.appendChild(theme);
    const section = new FakeElement('section');
    section.setAttribute('data-mobile-page', 'chat');
    section.appendChild(row);
    page.document.querySelector = (sel) => (sel === 'section[data-mobile-page="chat"]' ? section : null);
    return {section, row, theme};
}

function bootPageState(page) {
    page.dom.set('bg-background-win-alt', true);
    page.dom.setTheme('light');
    FakeMutationObserver.fire();
    return flush();
}

const refreshButtonsIn = (row) =>
    row.children.filter((child) => child.getAttribute('data-zcode-shell-refresh') === '1');

test('the refresh button lands left of the theme menu trigger with cloned styling', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        const {row, theme} = stubChatHeader(page);
        await bootPageState(page);

        const buttons = refreshButtonsIn(row);
        assert.strictEqual(buttons.length, 1, 'exactly one refresh button');
        assert.strictEqual(theme.previousElementSibling, buttons[0],
            'it sits immediately left of the theme trigger');
        assert.strictEqual(buttons[0].className, 'ghost icon-sm-classes',
            'the look is cloned from the page button, not hardcoded');
        assert.ok(buttons[0].getAttribute('aria-label'), 'it carries a label');
        assert.ok(findPost(page.posts, 'diag', (data) =>
            String(data.message).indexOf('刷新按钮已注入') === 0).length === 1,
            'and says so once in the log');

        // A second settled tick must not duplicate it.
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(refreshButtonsIn(row).length, 1, 'still exactly one');
    } finally {
        page.teardown();
    }
});

test('the refresh icon clones the page icon shell and swaps in the family glyph', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        const {row, theme} = stubChatHeader(page);
        // The page's own icon, as React renders it (a lucide shell).
        const shell = {
            attrs: {'class': 'lucide lucide-palette size-4'},
            getAttribute(name) { return this.attrs[name] || null; },
            cloneNode() {
                const c = {attrs: {}, innerHTML: '', setAttribute(n, v) { c.attrs[n] = v; }};
                return c;
            },
        };
        theme.querySelector = (sel) => (sel === 'svg' ? shell : null);
        await bootPageState(page);

        const icon = refreshButtonsIn(row)[0].children[0];
        assert.ok(icon, 'the button carries an icon');
        assert.strictEqual(icon.attrs['class'], 'lucide lucide-rotate-cw size-4',
            'the class list is inherited from the page icon, with the glyph name swapped');
        assert.ok(String(icon.innerHTML).indexOf('M21 12a9 9 0 1 1-9-9') >= 0,
            'and the glyph is the lighter rotate-cw');
    } finally {
        page.teardown();
    }
});

test('a remounted header gets a fresh button, and a page without the anchor stays untouched', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        const {section, row, theme} = stubChatHeader(page);
        await bootPageState(page);
        const first = refreshButtonsIn(row)[0];

        // React remount: the old row (our node included) leaves the document,
        // a fresh header renders in the same section.
        const newRow = new FakeElement('div');
        const theme2 = new FakeElement('button');
        theme2.setAttribute('aria-haspopup', 'menu');
        newRow.querySelectorAll = (sel) => (sel === 'button[aria-haspopup="menu"]' ? [theme2] : []);
        newRow.appendChild(theme2);
        section.children = [newRow];
        first.isConnected = false;

        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(theme2.previousElementSibling.getAttribute('data-zcode-shell-refresh'), '1',
            'the fresh header carries the button again');
        assert.notStrictEqual(refreshButtonsIn(newRow)[0], first, 'as a fresh node');

        // Page redesign: the row exists but has no menu trigger any more.
        newRow.children = [];
        newRow.querySelectorAll = () => [];
        theme2.parentElement = null;
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(refreshButtonsIn(newRow).length, 0,
            'the failure mode is a quiet absence, not a stray node');
    } finally {
        page.teardown();
    }
});

test('clicking the refresh button reloads in-process and leaves a log line', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        const {row} = stubChatHeader(page);
        await bootPageState(page);
        const reload = stubReload();
        try {
            refreshButtonsIn(row)[0].dispatchEvent({type: 'click'});
            assert.strictEqual(reload.reloads.length, 1, 'the click reloads the page itself');
            assert.ok(findPost(page.posts, 'diag', (data) =>
                data.level === 'info' && String(data.message).indexOf('刷新按钮：重载') === 0).length === 1,
                'and the reload is visible in the log');
        } finally {
            reload.restore();
        }
    } finally {
        page.teardown();
    }
});

test('on the wide shell the button anchors to the workspace header (no mobile header)', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: false}});
    try {
        // The wide/desktop shell never renders section[data-mobile-page="chat"];
        // the right-most header menu (measured: the help menu) is the anchor.
        const help = new FakeElement('button');
        help.setAttribute('aria-haspopup', 'menu');
        help.className = 'header-action';
        const header = new FakeElement('header');
        header.setAttribute('data-testid', 'workspace-header');
        header.querySelectorAll = (sel) => (sel === 'button[aria-haspopup="menu"]' ? [help] : []);
        header.appendChild(help);
        page.document.querySelector = (sel) =>
            (sel === 'header[data-testid="workspace-header"]' ? header : null);
        await bootPageState(page);

        const buttons = refreshButtonsIn(header);
        assert.strictEqual(buttons.length, 1, 'exactly one refresh button in the wide header');
        assert.strictEqual(help.previousElementSibling, buttons[0],
            'it sits immediately left of the header menu trigger, i.e. top-right');
        assert.strictEqual(buttons[0].className, 'header-action', 'the look is cloned, not hardcoded');
    } finally {
        page.teardown();
    }
});

test('the testid toggle fallback anchors the button and strips the selected highlight', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: false}});
    try {
        const bar = new FakeElement('div');
        const toggle = new FakeElement('button');
        toggle.setAttribute('data-testid', 'side-pane-toggle');
        toggle.className = 'plain !bg-selected icon-sm';
        bar.appendChild(toggle);
        page.document.querySelector = (sel) =>
            (sel === 'button[data-testid="side-pane-toggle"]' ? toggle : null);
        await bootPageState(page);

        const buttons = refreshButtonsIn(bar);
        assert.strictEqual(buttons.length, 1, 'the last-resort anchor still gets the button');
        assert.strictEqual(toggle.previousElementSibling, buttons[0], 'placed left of the toggle');
        assert.strictEqual(buttons[0].className, 'plain icon-sm',
            'the open-state !bg-selected highlight is stripped from the clone');
    } finally {
        page.teardown();
    }
});

test('login and authorization surfaces never get the refresh button', async () => {
    const surfaces = [
        'https://bigmodel.cn/login',
        'https://chat.z.ai/auth/oauth/authorize',
        'https://zcode.z.ai/web-remote/callback?code=redacted',
    ];
    for (const href of surfaces) {
        const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
        try {
            const {row} = stubChatHeader(page);
            globalThis.location.href = href;
            await bootPageState(page);
            assert.strictEqual(refreshButtonsIn(row).length, 0,
                'the anchor exists here, so only the auth guard can keep it out: ' + href);
        } finally {
            page.teardown();
        }
    }
});

// ---------------------------------------------------------------------------
// 进对话卡住（§5b）——**只读壳口径：只记账，不刷新**
//
// 这一节的历史：2026-09-12 用户拍板过"进对话 3s 后查一次，状态 A（头部停在
// 新建任务）或状态 B（输入框灰）→ 直接刷新"的粗暴版；2026-09-15 只读壳定案后
// **刷新这条路被废掉**（壳永不自动重载页面），下面几条测试断言的是现在的口径：
// 判定照做、状态照记（`__zcodeShellFallback` 的 state/ready 记账），但
// `reloads` 恒为 0，终止性不再靠"15s 最小刷新间隔"。
//
// 4b（2026-09-18）：§5c 卡死看门狗的决策机器整体拆除，`__zcodeShellFallback` 的
// `arm`/`cancel`/`fire`/`stall` 四个出口随之消失。本节那两条钉子测试里原先读
// "放弃态"的断言，改用**幸存**的 §5b 状态 + "副作用缺席"重写，契约不变
// （逐条理由写在各自的断言旁）。
// ---------------------------------------------------------------------------

const FB = () => globalThis.__zcodeShellFallback;

function appendHeader(page, titleText) {
    const title = new FakeElement('div');
    title.textContent = titleText;
    page.document.body.appendChild(title);
}

function appendComposer(page, placeholder, disabled) {
    const composer = new FakeElement('textarea');
    if (placeholder !== null) {
        composer.setAttribute('placeholder', placeholder);
    }
    if (disabled) {
        composer.setAttribute('disabled', '');
    }
    page.document.body.appendChild(composer);
}

function stubReload() {
    const reloads = [];
    const previous = globalThis.location;
    globalThis.location = {href: 'https://zcode.z.ai/remote/v4', reload: () => reloads.push(1)};
    return {reloads, restore: () => { globalThis.location = previous; }};
}

function stubSessionStorage() {
    const store = new Map();
    const previous = globalThis.sessionStorage;
    globalThis.sessionStorage = {
        getItem: (k) => (store.has(k) ? store.get(k) : null),
        setItem: (k, v) => store.set(k, String(v)),
        removeItem: (k) => store.delete(k)
    };
    return {store, restore: () => { globalThis.sessionStorage = previous; }};
}

test('进对话铁判准：信标后页面桥有入站帧 → 判就绪不刷', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, '继续输入以排队后续修改', false);
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_hot'}});
        assert.ok(FB().timer() !== null, 'the beacon arms the 5s deadline');
        // 信标之后桌面端回了真实对话动态事件——内容在路上。
        const conversation = encodeBody([page.protocol.RES_EVENT_FIRE, 77], {
            topic: 'conversation/ws',
            subscriptionId: 'sub-hot',
            kind: 'complete',
            frame: {payload: {kind: 'snapshot', snapshot: {rows: {window: []}}}}
        });
        for (const payload of fragment(conversation, 'page-bridge-13', 1)) {
            socket.receive({type: 'data', payload});
        }
        FB().check();
        assert.strictEqual(reloads.length, 0, 'conversation traffic must not reload');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('对话流有新帧')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：页面自述 store 已连上 → 不刷（新任务也不会被误刷）', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '上午好呀，有什么想让我帮忙的吗');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_new'}});
        // 空会话没有行，但页面自己的 store 一样会连上——这就是"就绪"的权威信号。
        FB().ready('v4.conversation.store.connect.completed');
        FB().check();
        assert.strictEqual(reloads.length, 0,
            'a brand-new empty task reaches store.connect.completed and must not be reloaded');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('已就绪（v4.conversation.store.connect.completed）')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：页面日志的订阅确认也算就绪', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_p'}});
        FB().ready('v4.conversation.subscribe.acknowledged');
        FB().check();
        assert.strictEqual(reloads.length, 0, 'an acknowledged subscription is readiness');
    } finally {
        restore();
        page.teardown();
    }
});

test('只读壳：进对话 5s 无详情只记账，不轻推也不刷新', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_a'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'the first deadline must not reload anything');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('进对话 3s 未出对话详情')).length === 1);
        // 真机定罪的那条链（轻推←fallbackCheck，40 秒里拆了 10 次）在只读壳下
        // 只剩这一行日志：说明"我们本来会在这一刻动手"。
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳：不轻推页面 socket')).length === 1);
        clearTimeout(FB().timer());
        FB().check();
        assert.strictEqual(reloads.length, 0, 'a read-only shell never reloads the page');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳：不因')).length === 1);
        // 4b（2026-09-18）：这里原来断言的是看门狗出口（`__zcodeShellFallback` 的
        // `.stall()`，现已删除）里那个"放弃态"字段——"到点就停下、不升级"。看门狗的决策
        // 机器连同该字段已整体删除，于是这条契约改由**幸存**的观测钉住，语义一字不变
        // （README:247 的钉子「进对话卡住不重载」）：
        //   ① 第二次到点是**终止步**：它走"只记录"那条路，不再武装第三个窗口；
        //   ② 第一次到点确实记过账（nudgedAt）——证明上面的"不升级"不是"根本没跑到"；
        //   ③ 全程零重载（本用例上面两条 reloads 断言）。
        assert.strictEqual(findPost(page.posts, 'diag', (d) =>
            d.message.includes('二次判定仍未出对话详情')).length, 1,
            'the second deadline is the terminal step — no escalation, no third window');
        assert.ok(FB().state().nudgedAt > 0, 'the first deadline was charged');
        assert.strictEqual(FB().timer(), null, 'the ladder stops: no third deadline is armed');
    } finally {
        restore();
        page.teardown();
    }
});

test('只读壳：状态 B（输入框未就绪）同样只记账不刷新', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, null, true);
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_b'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'state B must not reload either');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('输入框未就绪')).length === 1);
        clearTimeout(FB().timer());
        FB().check();
        assert.strictEqual(reloads.length, 0, 'state B also stays read-only');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('只读壳：不因')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('只读壳：进对话卡住不会重载，也不会连刷；就绪信号照样复位', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'the first deadline nudges nothing');
        assert.ok(findPost(page.posts, 'diag', (d) => d.message.includes('进对话 3s 未出对话详情')).length === 1);
        clearTimeout(FB().timer());

        // 第二次判定：旧版在这里进"整体刷新保底"，只读壳到此为止。
        FB().check();
        assert.strictEqual(reloads.length, 0, 'the read-only shell never escalates to a reload');

        // 无论判定多少次，页面都不会被壳重载。
        // （此处原来先把 `stallState.lastReloadAt` 拨旧 16s，好让"15s 最小刷新间隔"
        // 不再是借口。A7 把那个字段与它服务的刷新闸门一并删了——只读壳下刷新路径
        // 整个不存在，于是这句设置也删掉；断言本身照旧。）
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_1'}});
        FB().check();
        assert.strictEqual(reloads.length, 0, 'no ladder, no reload — ever');

        // 就绪信号到达 → 本轮干净收工，下一轮照旧能判。
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        FB().ready('v4.conversation.store.connect.completed');
        FB().check();
        // 4b（2026-09-18）：这里原来断言的是看门狗那个"放弃态"闩锁复位为假。放弃态随
        // 看门狗的决策机器一起删了，但**"就绪信号照样被认账、本轮干净收工、下一轮还能
        // 重新判"**这条语义仍在（README:247 的钉子「卡住不连刷」），改由幸存的 §5b 记账
        // + 副作用缺席钉住：
        //   ① 就绪证据落在本轮的 readyAt/readyBy 上；
        //   ② 判定器收工后不再有任何挂起的窗口（没有下一拍）。
        const state = FB().state();
        assert.strictEqual(state.readyBy, 'v4.conversation.store.connect.completed',
            'readiness is recorded on the surviving §5b state');
        assert.ok(state.readyAt >= state.beaconAt,
            'the readiness evidence belongs to this beacon window');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('已就绪（v4.conversation.store.connect.completed）')).length === 1,
            'the ready line proves the round closed as "ready" instead of escalating');
        assert.strictEqual(FB().timer(), null, 'nothing is left armed after the ready round');
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：链路换代那一拍不判（页面正按自己的梯子重连）', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '真实任务名');
        appendComposer(page, '继续输入以排队后续修改', false);
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_e'}});
        // 链路重建：pair 状态翻负 → resetClient → client 为 null（新客户端尚未出生）
        socket.receive({type: 'pair_status_ack', pair_status: 'unmatched'});
        FB().check();
        assert.strictEqual(reloads.length, 0,
            'a rebuilt link must not be reloaded mid-recovery');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('期间链路换代')).length === 1);
    } finally {
        restore();
        page.teardown();
    }
});

test('进对话铁判准：窗口内的重复信标不顺延（页面重试不会把 5s 拖长）', () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        appendHeader(page, '新建任务');
        appendComposer(page, '向 ZCode 提问…', false);
        FB().note({name: 'zcode-agent.subscribeConversationV4', args: {sessionId: 'sess_1'}});
        const first = FB().state().beaconAt;
        // 卡住时页面每 ~10s 重发一次同样的请求；真机实测这些重试把判定一路
        // 顺延到 ~9s 才刷，所以窗口起点必须钉在第一次进对话那一刻。
        FB().note({name: 'zcode-agent.conversationRowsRangeV4', args: {sessionId: 'sess_1'}});
        assert.strictEqual(FB().state().beaconAt, first,
            'a retry must not slide the deadline');
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('落在此前已武装的 3s 窗内')).length === 1);
        assert.strictEqual(reloads.length, 0, 'no reload before the deadline');
    } finally {
        if (FB().timer()) {
            clearTimeout(FB().timer());
        }
        restore();
        page.teardown();
    }
});

test('进对话铁判准：页面真实发出的信标会武装 5s 判定', () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        const body = encodeBody(
            [page.protocol.REQ_PROMISE, 11, 'zcode-agent', 'conversationRowsRangeV4'],
            {sessionId: 'sess_wire'}
        );
        for (const payload of fragment(body, 'page-bridge-13', 1)) {
            socket.send(JSON.stringify({type: 'data', payload}));
        }
        assert.ok(FB().timer() !== null,
            'the observed rows-range beacon must arm the 5s deadline');
        // 武装出来的判定器必须清掉：否则 5s 后它会落到后续测试的 location 上刷新
        // （整跑时表现为"别的用例多出一次 reload"）。
        clearTimeout(FB().timer());
    } finally {
        page.teardown();
    }
});

test('KICKED 归因：落在承载窗口内 → 自我接管，注入层不重载、交给原生', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellExpectCarrier(Date.now(), 'ws-key', 'sess-1');
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('落在承载窗口内（自我接管）')).length === 1,
            'diag: ' + JSON.stringify(findPost(page.posts, 'diag').map((p) => p.data.message)));
        globalThis.__zcodeShellSetAppForeground(true);
        await wait(50);
        assert.strictEqual(reloads.length, 0,
            'the native side owns the recovery: it covers, hands back, then reloads');
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED 归因：窗口外（外部抢占）→ 壳不动手，不罩也不重载', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('外部抢占，壳不动手')).length === 1);
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellSetAppForeground(true);
        await wait(1700);
        assert.strictEqual(reloads.length, 0, 'an external conflict is the official flow');
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED 归因：承载窗口关闭（交还）后，迟到的 KICK 归外部', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellExpectCarrier(Date.now(), 'ws-key', '');
        globalThis.__zcodeShellExpectCarrier(0, '', '');
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('外部抢占，壳不动手')).length === 1,
            'a closed window must not claim the kick');
        assert.strictEqual(reloads.length, 0);
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED 归因：窗口超过 60s 自动失效（原生没来得及关也不误判）', async () => {
    const page = setupPage();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellExpectCarrier(Date.now() - 61_000, 'ws-key', '');
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('外部抢占，壳不动手')).length === 1);
    } finally {
        storage.restore();
        page.teardown();
    }
});

test('KICKED 归因：窗口 workspace 与页面当前 workspace 不符 → 外部抢占（审查注入层 P1-3）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellExpectCarrier(Date.now(), 'ws-a', '');
        // 页面自己正在看的是另一个工作区：这条 KICK 不是我们造成的。
        socket.send(JSON.stringify({
            type: 'data', payload: {zcode_type: 'workspace-bridge-open', workspaceKey: 'ws-b'}
        }));
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('外部抢占，壳不动手')).length === 1,
            'workspace mismatch must not be attributed to our carrier');
        assert.strictEqual(reloads.length, 0);
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('KICKED 归因：窗口 workspace 与页面一致 → 仍算自我接管', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    const storage = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellExpectCarrier(Date.now(), 'ws-a', '');
        socket.send(JSON.stringify({
            type: 'data', payload: {zcode_type: 'workspace-bridge-open', workspaceKey: 'ws-a'}
        }));
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('落在承载窗口内（自我接管）')).length === 1,
            'matching workspace keeps the self-attribution');
    } finally {
        storage.restore();
        restore();
        page.teardown();
    }
});

test('后台期间重载的文档按原生真实前后台启动（config.foreground）', async () => {
    // 2026-09-27 17:07 现场的一环：文档在后台被重载后，注入层启动默认自认前台，
    // 归因日志就缺了那一路信号。原生现在在 config() 里给出权威前后台。
    const page = setupPage({config: {passiveObserve: true, foreground: false}});
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'error', code: 'KICKED', message: 'session-conflict'});
        assert.strictEqual(findPost(page.posts, 'pagefailure').at(-1).data.foreground, false,
            'a config.foreground=false document must report the kick as background');
    } finally {
        page.teardown();
    }
});

test('hook 安全：晚注入经原型层收编页面已有 socket（零重连）', () => {
    const page = setupPage();
    try {
        // 绕过构造包装直建实例 = 模拟「hook 装上之前页面已建好的连接」
        const orphan = new FakeWebSocket('wss://relay.example');
        // 它的下一次 send 走原型补丁 → 当场收编
        orphan.send(JSON.stringify({type: 'data', payload: {zcode_type: 'x'}}));
        assert.ok(findPost(page.posts, 'diag', (d) =>
            d.message.includes('已从原型层收编现有 socket')).length === 1,
            'adoption must be logged');
        // 收编后：入站帧恢复观测（配对 ack 让 relayPaired 翻真）
        orphan.receive({type: 'pair_status_ack', pair_status: 'matched'});
        globalThis.__zcodeShellHeartbeat();
        assert.ok(findPost(page.posts, 'liveness', (d) => d.paired === true).length === 1,
            'the adopted socket must deliver inbound frames to the shell');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// 后台原生承载（2026-09-16）：交还兜底与"仅取证"的推动函数
//
// 生产逻辑里"谁来接住连接"是原生侧（ShellRuntime.maybeStartNativeCarrier），
// 注入层只留两件：① 交还后极窄的兜底（页面掉进失败态才重载一次）；
// ② 取证用的推动（合成 online / 关 socket）——真机已证它救不了后台，别再拿它当方案。
// ---------------------------------------------------------------------------

/** 给合成事件测试装上最小 Event / dispatchEvent（注入层里 G === globalThis）。 */
function stubSyntheticEvents() {
    const dispatched = [];
    const previousEvent = globalThis.Event;
    const previousDispatch = globalThis.dispatchEvent;
    const previousWindowDispatch = globalThis.window && globalThis.window.dispatchEvent;
    globalThis.Event = function (type) { return {type}; };
    globalThis.dispatchEvent = (ev) => { dispatched.push(ev && ev.type); return true; };
    if (globalThis.window) {
        globalThis.window.dispatchEvent = globalThis.dispatchEvent;
    }
    return {
        dispatched,
        restore: () => {
            globalThis.Event = previousEvent;
            globalThis.dispatchEvent = previousDispatch;
            if (globalThis.window) {
                globalThis.window.dispatchEvent = previousWindowDispatch;
            }
        }
    };
}

test('后台承载：恢复推动 event 档只派发合成 online，并回报推动前现场', async () => {
    const page = setupPage();
    const events = stubSyntheticEvents();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        const before = globalThis.__zcodeShellNudgeRecover('event');
        assert.deepStrictEqual(events.dispatched, ['online'], '只派发一个 online，不要 pageshow');
        assert.ok(String(before).indexOf('socket=1') >= 0,
            '返回值是推动前的链路现场（原生日志与它对齐看时序）');
        assert.strictEqual(
            findPost(page.posts, 'diag', (d) => d.message.indexOf('恢复推动 event') === 0).length,
            1);
    } finally {
        events.restore();
        page.teardown();
    }
});

test('后台承载：恢复推动 close 档关掉页面那条 socket（仅取证）', () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        const before = globalThis.__zcodeShellNudgeRecover('close');
        assert.ok(String(before).indexOf('socket=1') >= 0, '返回值是关线前的现场');
        assert.strictEqual(socket.readyState, FakeWebSocket.CLOSED,
            'close 档必须真的关掉它（生产逻辑不用，仅取证）');
        assert.strictEqual(
            findPost(page.posts, 'diag', (d) => d.message.indexOf('恢复推动 close') === 0).length,
            1);
    } finally {
        page.teardown();
    }
});



// ---------------------------------------------------------------------------
// 档 0 观测（2026-09-19）：`__zcodeShellReportViewSnapshot` —— 回前台视图判据的数据源
//
// 为什么需要这一档：三道回前台兜底（KICKED 自愈 / healDeadLinkOnResume /
// afterCarrierReturn）全都只在"页面已彻底失败"时动手，而假死形态的页面**看起来完全健康**
// （socket OPEN、有帧、paired=true）⟹ 全部判"无需介入"，日志里一行都不留。
// 这一档只读一帧快照交给原生，让那种形态变得可见。契约要点（钉住三条）：
//   ① 纯读：不往页面 socket 写一个字节；
//   ② 快照要带 `historyChat`（页面自己的路由记忆，跨 reload 存活、**不跨进程**）
//      与 `sessionId`（用户在看哪个会话）；
//   ③ 读不到 DOM 时不许抛——判据不齐宁可不判。
// ---------------------------------------------------------------------------

test('档0：视图快照是纯读，且带上 historyChat 与当前会话', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(30);

        // 页面"在对话视图里、时间线有 39 行"——真机 W9 离开时的形状。
        const timeline = new FakeElement('div');
        timeline.setAttribute('data-row-count', '39');
        page.document.querySelector = (selector) => {
            if (selector === '[data-mobile-page="chat"]') return page.document.body;
            if (selector === '[data-v4-timeline-scroll]') return timeline;
            return null;
        };
        // 页面自己的路由状态（`history.state.zcodeMobilePage`）。
        globalThis.history = {state: {zcodeMobilePage: 'chat'}};

        const before = socket.sent.length;
        assert.strictEqual(
            globalThis.__zcodeShellReportViewSnapshot('leave'), true,
            '上报接口必须返回 true（原生据此知道注入层是活的）',
        );
        await flush();

        // ① 纯读：一个字节都没往页面那条 socket 写。
        assert.strictEqual(
            socket.sent.length, before,
            '档 0 是纯观测：快照不许写页面 socket',
        );

        const snaps = findPost(page.posts, 'viewsnap');
        assert.strictEqual(snaps.length, 1, '一次上报恰好一帧');
        const data = snaps[0].data;
        assert.strictEqual(data.phase, 'leave', 'phase 原样带回（原生用它区分离开/返回）');
        // ② 快照内容。
        assert.strictEqual(data.snap.chat, true, '在对话视图里');
        assert.strictEqual(data.snap.rows, 39, '行数从 DOM 属性读');
        assert.strictEqual(data.snap.historyChat, true, 'historyChat 必须带上——它区分"能自己落回来"与"回不来"');
        assert.ok('sessionId' in data.snap, 'sessionId 字段必须在（空串也是有意义的读数）');
    } finally {
        delete globalThis.history;
        page.teardown();
    }
});

test('档0：DOM 读不到时快照降级为空读数，不抛异常', async () => {
    const page = setupPage();
    try {
        // 不装任何 DOM 覆盖：默认假文档里 querySelector 不存在/返回空。
        page.document.querySelector = () => null;
        const ok = globalThis.__zcodeShellReportViewSnapshot('return_late');
        await flush();
        assert.strictEqual(ok, true, '读不到 DOM 也必须成功返回——判据不齐宁可不判，但不能炸');
        const snaps = findPost(page.posts, 'viewsnap');
        assert.strictEqual(snaps.length, 1);
        assert.strictEqual(snaps[0].data.snap.chat, false, '读不到就是不在对话视图（保守读数）');
        assert.strictEqual(snaps[0].data.snap.rows, -1, '没有时间线时 rows 必须是 -1，不能是 0');
    } finally {
        page.teardown();
    }
});

// ---------------------------------------------------------------------------
// 档 3（2026-09-19）：`__zcodeShellRecoverConversation` —— 回前台把对话拉回来的那一次写入
//
// **这是壳体对页面的第七处写入面**（原六处见 README「实现要点」14）。它存在的理由由
// 三个真机对照实验支撑（`android-shell/scratch/PROOF-RESULTS.md`）：
//   ① 卡死态 + 钉 history.state + reload → 落回对话（rows 610）
//   ② 卡死态 + 不钉 + reload            → 落 home 任务列表
//   ③ **冷启动**（state 已归零）+ 钉 + reload → 落回对话（rows 645）
// ⟹ `history.state.zcodeMobilePage==='chat'` 是唯一开关，且**不跨进程存活**。
//
// 契约要点（本组测试钉的就是它们）：
//   A. 两个动作**都发生**：钉 state（保留原字段）+ reload；
//   B. 幂等/干净：已是 chat 就不重复 replaceState；不写任务槽位（与第六处不同）；
//   C. 终止性：跨 reload 计数封顶 + 5 分钟限流；
//   D. 不碰页面 socket（它不该退化成"轻推"）。
// ---------------------------------------------------------------------------

test('档3：钉住路由状态并重载一次（两个动作都发生）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        const replaced = [];
        globalThis.history = {
            state: {zcodeMobilePage: 'home', keepMe: 1},
            replaceState: (st) => replaced.push(st)
        };

        const ok = globalThis.__zcodeShellRecoverConversation('离开时在对话(sess_a) / 12s 后 chat=false');
        assert.strictEqual(ok, true, '原生判出 S3 后调用它，应当安排重载');

        // A① 钉住路由状态，且**保留原有字段**（页面可能往 state 里放别的东西）。
        assert.strictEqual(replaced.length, 1, '恰好一次 replaceState');
        assert.strictEqual(replaced[0].zcodeMobilePage, 'chat', '必须把 zcodeMobilePage 钉成 chat');
        assert.strictEqual(replaced[0].keepMe, 1, '原有字段必须保留（整份覆盖会弄丢页面的状态）');

        // replaceState 同步生效，直接重载，不额外排一个 300ms 定时器。
        assert.strictEqual(reloads.length, 1, '同一调用内恰好重载一次');
    } finally {
        restore();
        delete globalThis.history;
        page.teardown();
    }
});

test('档3：版本闸门阻断时不重载（同一个 modal 只会再来一次，审查注入层 P2-2）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        page.document.querySelector = (sel) =>
            (sel === 'dialog[aria-labelledby="remote-version-title"][open]'
                ? new FakeElement('dialog') : null);
        const replaced = [];
        globalThis.history = {
            state: {zcodeMobilePage: 'home'},
            replaceState: (st) => replaced.push(st)
        };
        assert.strictEqual(globalThis.__zcodeShellRecoverConversation('S3 成立但闸门挡路'), false,
            'blocked gate must refuse the reload');
        assert.strictEqual(replaced.length, 0, 'refused recovery must not touch history.state');
        await wait(400);
        assert.strictEqual(reloads.length, 0, 'and it must not reload');
    } finally {
        restore();
        delete globalThis.history;
        page.teardown();
    }
});

test('档3：已是 chat 时不重复 replaceState，但仍重载（幂等）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        const replaced = [];
        globalThis.history = {
            state: {zcodeMobilePage: 'chat'},
            replaceState: (st) => replaced.push(st)
        };
        assert.strictEqual(globalThis.__zcodeShellRecoverConversation('x'), true);
        assert.strictEqual(replaced.length, 0, '已经是 chat 就不该再写一次 history.state');
        await wait(400);
        assert.strictEqual(reloads.length, 1, '延时到点必须真的重载');
    } finally {
        restore();
        delete globalThis.history;
        page.teardown();
    }
});

// ⚠️ **限流不在这层**（2026-09-20 真机实测后改的）：注入层能用的两个计数
// （sessionStorage 与内存变量）在**进程被杀**后都会归零，而档 3 治的 S3 恰恰是
// "进程被杀"——限流在最需要它的场景下失效（实测第二次 S3 仍打「第 1/2 次」）。
// 闸门已移到原生侧（`Prefs.conversationRecovery` + `ShellRuntime` 的
// `allowConversationRecovery`）。这条测试钉的就是**这一层不许自己加闸门**：
// 连调两次必须都执行（决策权在原生，这里只是执行者）。
test('档3：本层不加闸门——连调两次都执行（限流的决策权在原生侧）', async () => {
    const page = setupPage();
    const {reloads, restore} = stubReload();
    try {
        globalThis.history = {state: {zcodeMobilePage: 'chat'}, replaceState: () => {}};
        assert.strictEqual(globalThis.__zcodeShellRecoverConversation('first'), true);
        assert.strictEqual(
            globalThis.__zcodeShellRecoverConversation('second'), true,
            '本层是执行者：原生放行几次就执行几次（闸门在原生，见 Prefs.conversationRecovery）',
        );
        await wait(400);
        assert.strictEqual(reloads.length, 2, '两次调用各安排一次重载');
    } finally {
        restore();
        delete globalThis.history;
        page.teardown();
    }
});

test('档3：不写任务槽位——与第六处（通知定位）的关键区别', async () => {
    const page = setupPage();
    const {restore} = stubReload();
    try {
        const writes = [];
        globalThis.localStorage = {
            setItem: (k, v) => writes.push(k + '=' + v),
            getItem: () => null,
            removeItem: () => {}
        };
        globalThis.history = {state: {zcodeMobilePage: 'chat'}, replaceState: () => {}};
        globalThis.__zcodeShellRecoverConversation('x');
        await wait(400);
        assert.strictEqual(
            writes.filter((w) => w.indexOf('zcode-v4-last-session') === 0).length, 0,
            '这条恢复是"放回用户离开的地方"，不是"跳到某个任务"——不写任务槽位',
        );
    } finally {
        restore();
        delete globalThis.history;
        delete globalThis.localStorage;
        page.teardown();
    }
});

test('档3：不碰页面 socket（它不许退化成轻推）', async () => {
    const page = setupPage();
    const {restore} = stubReload();
    const session = stubSessionStorage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.dispatchEvent({type: 'open'});
        socket.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        await wait(30);

        globalThis.history = {state: {zcodeMobilePage: 'chat'}, replaceState: () => {}};
        const before = socket.sent.length;
        globalThis.__zcodeShellRecoverConversation('x');
        await wait(400);

        assert.strictEqual(socket.readyState, FakeWebSocket.OPEN, '不许关页面那条 socket');
        assert.strictEqual(socket.sent.length, before, '也不许往它上面写帧');
    } finally {
        session.restore();
        restore();
        delete globalThis.history;
        page.teardown();
    }
});


test('快速交接：启动前恢复聊天路由并保留网页其他历史字段', () => {
    const history = {state: {zcodeMobilePage: 'home', keep: 7}, length: 2, replaceState(next) { this.state = next; }};
    const page = setupPage({config: {passiveObserve: true, foreground: true, restoreChat: true}, history});
    try {
        assert.deepStrictEqual(history.state, {zcodeMobilePage: 'chat', keep: 7});
        assert.strictEqual(page.configReads(), 1, 'document config is read once across all consumers');
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'pair_status_ack', pair_status: 'matched'});
        assert.strictEqual(page.configReads(), 1, 'creating protocol clients reuses the config');
    } finally { page.teardown(); }
});

test('快速交接：后台加载不擅自恢复聊天路由', () => {
    const history = {state: {zcodeMobilePage: 'home'}, replaceState(next) { this.state = next; }};
    const page = setupPage({config: {foreground: false, restoreChat: true}, history});
    try { assert.strictEqual(history.state.zcodeMobilePage, 'home'); }
    finally { page.teardown(); }
});

test('冷启动：历史栈只有一条时不钉聊天路由（否则返回按钮的 history.back() 无处可回）', () => {
    const storage = stubSessionStorage();
    storage.store.set('zcodeShellPinChat', '1'); // 上一份文档留下的标记
    const history = {state: null, length: 1, replaceState(next) { this.state = next; }};
    const page = setupPage({config: {passiveObserve: true, foreground: true, restoreChat: true}, history});
    try {
        assert.strictEqual(history.state, null,
            'no back entry: pinning chat would make the page back button a no-op');
        assert.strictEqual(storage.store.get('zcodeShellPinChat'), '',
            'the stale pin marker must be consumed instead of leaking into a later navigation');
    } finally {
        storage.restore();
        page.teardown();
    }
});

test('旁观收窄：窗口控制 socket 不改变 relay 状态、不解析也不补心跳', () => {
    const page = setupPage();
    try {
        const relay = new globalThis.WebSocket('wss://relay.example');
        relay.send(JSON.stringify({type: 'auth_init', role: 'terminal', device_sid: 'sid-1'}));
        relay.receive({type: 'pair_status_ack', pair_status: 'matched'});
        const other = new globalThis.WebSocket('wss://relay.example/ws/remote-control/window/token');
        other.dispatchEvent({type: 'open'});
        other.receive({type: 'window-control-ready'});
        other.send(JSON.stringify({type: 'data', payload: {zcode_type: 'unrelated'}}));
        other.close();
        globalThis.__zcodeShellReportLiveness();
        const live = findPost(page.posts, 'liveness').at(-1).data;
        assert.strictEqual(live.inboundFrames, 1, 'only relay input is counted');
        assert.strictEqual(live.paired, true, 'closing the window socket must not reset relay pairing');
        globalThis.__zcodeShellSetAppForeground(false);
        globalThis.__zcodeShellHeartbeat();
        assert.ok(relay.sent.some(s => JSON.parse(s).type === 'pair_status_query'));
        assert.strictEqual(other.sent.length, 1, "only the page's own window message was sent");
    } finally { page.teardown(); }
});

// ---------------------------------------------------------------------------
// 3.17.1 适配（2026-11-04）：版本闸门 ②/③、登录面刷新按钮二判据、闸门① 重钉路由
// ---------------------------------------------------------------------------

test('版本闸门②：从页面自己的 bootstrap 帧学到 desktopAppVersion（拿不到就不发）', async () => {
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket('wss://relay.example');
        socket.receive({type: 'data', payload: {
            zcode_type: 'bootstrap-response',
            requestId: 'boot-1',
            result: {desktopAppVersion: '3.17.1', workspaces: []}
        }});
        assert.deepStrictEqual(
            findPost(page.posts, 'desktopversion').map((p) => p.data.version),
            ['3.17.1'],
            'the value the gate would compare against must reach the native side',
        );
        // 旧桌面端/字段缺失：不猜，不发（原生留着闸门①/③兜底）。
        socket.receive({type: 'data', payload: {
            zcode_type: 'bootstrap-response', result: {workspaces: []}
        }});
        socket.receive({type: 'data', payload: {
            zcode_type: 'bootstrap-response', result: {desktopAppVersion: ''}
        }});
        assert.strictEqual(findPost(page.posts, 'desktopversion').length, 1,
            'an absent/empty version must not be reported');
        // 去重（审查注入层 P2-4）：重连/刷新带来的同一版本不再发帧；变了才发。
        socket.receive({type: 'data', payload: {
            zcode_type: 'bootstrap-response', result: {desktopAppVersion: '3.17.1'}
        }});
        assert.strictEqual(findPost(page.posts, 'desktopversion').length, 1,
            'an unchanged version must not be reported twice');
        socket.receive({type: 'data', payload: {
            zcode_type: 'bootstrap-response', result: {desktopAppVersion: '3.17.2'}
        }});
        assert.deepStrictEqual(
            findPost(page.posts, 'desktopversion').map((p) => p.data.version),
            ['3.17.1', '3.17.2'],
            'a changed version must be reported again',
        );
    } finally {
        page.teardown();
    }
});

test('版本闸门③：阻断式 modal 出现/消失各上报一次（重载决策据此同步跳过）', async () => {
    const page = setupPage({pageState: true});
    try {
        const dialog = new FakeElement('dialog');
        const realQuery = page.document.querySelector;
        page.document.querySelector = (sel) =>
            sel === 'dialog[aria-labelledby="remote-version-title"][open]' ? dialog : realQuery(sel);

        page.dom.set('bg-background-win-alt', true);
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(findPost(page.posts, 'versiongate').map((p) => p.data.blocked), [true]);

        // 同一状态重复上报不刷屏。
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(findPost(page.posts, 'versiongate').length, 1, 'deduped until it changes');

        page.document.querySelector = () => null;
        FakeMutationObserver.fire();
        await flush();
        assert.deepStrictEqual(findPost(page.posts, 'versiongate').map((p) => p.data.blocked), [true, false]);
    } finally {
        page.teardown();
    }
});

test('版本闸门③：关闭态的常驻 <dialog> 不算阻断（[open] 判据，审查注入层 P2-1）', async () => {
    const page = setupPage({pageState: true});
    try {
        // 页面若把 dialog 常驻挂载、只在弹出时写 open：关闭态查不到 [open] ⇒ 判据必须为假。
        page.document.querySelector = () => null;
        page.dom.set('bg-background-win-alt', true);
        FakeMutationObserver.fire();
        await flush();
        assert.strictEqual(findPost(page.posts, 'versiongate').length, 0,
            'a resident closed dialog must not arm the gate');
    } finally {
        page.teardown();
    }
});

test('刷新按钮：没有 aria-haspopup 时用 aria-label 二判据兜住', async () => {
    const page = setupPage({pageState: true, media: {[NARROW_QUERY]: true}});
    try {
        const theme = new FakeElement('button');
        // Radix 换实现：运行时不再写 aria-haspopup，只剩语义化的 aria-label。
        theme.setAttribute('aria-label', 'Switch theme');
        theme.className = 'ghost';
        const row = new FakeElement('div');
        row.querySelectorAll = (sel) => {
            if (sel === 'button[aria-haspopup="menu"]') return [];
            if (sel === 'button[aria-label]') return [theme];
            return [];
        };
        row.appendChild(theme);
        const section = new FakeElement('section');
        section.setAttribute('data-mobile-page', 'chat');
        section.appendChild(row);
        page.document.querySelector = (sel) => (sel === 'section[data-mobile-page="chat"]' ? section : null);

        await bootPageState(page);
        assert.strictEqual(theme.previousElementSibling.getAttribute('data-zcode-shell-refresh'), '1',
            'the second criterion keeps the button alive when the Radix attribute disappears');
    } finally {
        page.teardown();
    }
});

test('闸门①：version-gate 导航后按上一次的决定重钉聊天路由（state 已被清空）', () => {
    const storage = stubSessionStorage();
    storage.store.set('zcodeShellPinChat', '1');
    const history = {state: null, length: 2, replaceState(next) { this.state = next; }};
    // restoreChat=false：原生那一刻已经翻成 false（viewAtLeave 被消费掉了）——正是
    // `location.replace()` 之后那次文档加载的真实形状。
    const page = setupPage({config: {passiveObserve: true, foreground: true}, history});
    try {
        assert.strictEqual(history.state.zcodeMobilePage, 'chat',
            'the pin must survive the gate navigation, not depend on restoreChat');
        // 用完即焚（审查注入层 P2-3）：否则登出→重新登录回到 /remote 时，
        // 旧标记会把用户直接拽进上一轮的对话页。
        assert.strictEqual(storage.store.get('zcodeShellPinChat'), '',
            'the pin must be consumed right after it is used');
    } finally {
        storage.restore();
        page.teardown();
    }
});

test('闸门①：没有记忆时不动 history.state（不把用户拽进对话页）', () => {
    const storage = stubSessionStorage();
    const history = {state: {zcodeMobilePage: 'home'}, replaceState(next) { this.state = next; }};
    const page = setupPage({config: {passiveObserve: true, foreground: true}, history});
    try {
        assert.strictEqual(history.state.zcodeMobilePage, 'home');
    } finally {
        storage.restore();
        page.teardown();
    }
});

test('承载凭证：移交 JWT（不复用页面那枚一次性 token），url 去掉凭证查询串', () => {
    // 2026-11-05 真机 CDP：relay token 一次性——复用页面那枚第二次连接直接 1006。
    // 原生必须拿 JWT 自己现铸；页面 token 绝不外流。
    globalThis.localStorage = {
        getItem: (k) => (k === 'zcodejwttoken' ? 'jwt-abc' : null),
        setItem: () => {},
        removeItem: () => {},
    };
    const page = setupPage();
    try {
        const socket = new globalThis.WebSocket(
            'wss://zcode.z.ai/api/v1/remote/ws2?token=tok-abc&mid=mid-1');
        socket.send(JSON.stringify({type: 'auth_init', device_sid: 'sid-1'}));
        const creds = findPost(page.posts, 'relaycreds').at(-1);
        assert.ok(creds, 'JWT must be handed over');
        assert.strictEqual(creds.data.deviceSid, 'redacted', 'sid comes from the page URL');
        assert.strictEqual(creds.data.jwt, 'jwt-abc');
        assert.ok(!('authToken' in creds.data), 'the page token must never be reused');
        assert.ok(!String(creds.data.url).includes('token='),
            'url must be stripped of credential query params');
        assert.ok(!JSON.stringify(findPost(page.posts, 'diag')).includes('jwt-abc'),
            'the JWT must never reach the diagnostic log');
    } finally {
        delete globalThis.localStorage;
        page.teardown();
    }
});
