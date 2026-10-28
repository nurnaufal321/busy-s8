// SPDX-License-Identifier: GPL-2.0-or-later
// The JS app globals of services/js_runner, built on a few host bindings (__host_*).
// Everything that keeps an app alive (timers, fetches, the input listener) is a pending host
// call, so the app ends, as on the device, once none is left.
(() => {
  "use strict";
  const g = globalThis;
  const host = {};
  for (const name of Object.getOwnPropertyNames(g)) {
    if (name.startsWith("__host_")) {
      host[name.slice(7)] = g[name];
      delete g[name];
    }
  }

  // ---- console --------------------------------------------------------------------------
  const show = (v) => {
    if (typeof v === "string") return v;
    if (v instanceof Error) return v.stack ? `${v.name}: ${v.message}\n${v.stack}` : `${v.name}: ${v.message}`;
    if (typeof v === "function") return `[Function ${v.name || "anonymous"}]`;
    if (v === undefined || typeof v === "symbol" || typeof v === "bigint") return String(v);
    try {
      const s = JSON.stringify(v);
      return s === undefined ? String(v) : s;
    } catch {
      return String(v);
    }
  };
  const logger = (level) => (...args) => host.console(level, args.map(show).join(" "));
  g.console = { log: logger("log"), debug: logger("log"), info: logger("info"), warn: logger("warn"), error: logger("error") };
  const report = (e) => host.console("error", `Uncaught ${show(e)}`);

  // ---- timers -----------------------------------------------------------------------------
  let nextTimer = 1;
  const timers = new Set();
  const schedule = (fn, ms, args, repeat) => {
    if (typeof fn !== "function") throw new TypeError("callback is not a function");
    const id = nextTimer++;
    const delay = Math.max(0, Number(ms) || 0);
    timers.add(id);
    (async () => {
      while (timers.has(id)) {
        if (!(await host.sleep(id, delay)) || !timers.has(id)) return;
        if (!repeat) timers.delete(id);
        try {
          fn(...args);
        } catch (e) {
          report(e);
        }
      }
    })();
    return id;
  };
  const clear = (id) => {
    if (timers.delete(id)) host.cancelSleep(id);
  };
  g.setTimeout = (fn, ms, ...args) => schedule(fn, ms, args, false);
  g.setInterval = (fn, ms, ...args) => schedule(fn, ms, args, true);
  g.clearTimeout = clear;
  g.clearInterval = clear;
  g.queueMicrotask = (fn) => Promise.resolve().then(fn).catch(report);

  // ---- input ------------------------------------------------------------------------------
  let handler = null;
  g.listen = (type, fn) => {
    if (type !== "input") throw new TypeError("Unknown event type");
    if (typeof fn !== "function") throw new TypeError("handler is not a function");
    if (handler) throw new TypeError("Handler override is forbidden");
    handler = fn;
    const subscription = host.inputSubscribe();
    (async () => {
      for (;;) {
        const event = await host.inputNext(subscription);
        if (event === null || handler !== fn) return;
        try {
          fn(Object.freeze(event));
        } catch (e) {
          report(e);
        }
      }
    })();
    return () => {
      if (handler !== fn) return;
      handler = null;
      host.inputUnsubscribe(subscription);
    };
  };

  // ---- localStorage -----------------------------------------------------------------------
  const stored = (() => {
    try {
      const data = JSON.parse(host.storageLoad() || "{}");
      return new Map(Object.entries(data).filter(([, v]) => typeof v === "string"));
    } catch {
      return new Map();
    }
  })();
  const save = () => host.storageSave(JSON.stringify(Object.fromEntries(stored)));
  g.localStorage = Object.freeze({
    getItem: (k) => (stored.has(String(k)) ? stored.get(String(k)) : null),
    setItem: (k, v) => {
      stored.set(String(k), String(v));
      save();
    },
    removeItem: (k) => {
      if (stored.delete(String(k))) save();
    },
    clear: () => {
      stored.clear();
      save();
    },
    key: (i) => [...stored.keys()][i] ?? null,
    get length() {
      return stored.size;
    },
  });

  // ---- text ------------------------------------------------------------------------------
  class TextEncoder {
    get encoding() {
      return "utf-8";
    }
    encode(s = "") {
      return host.utf8Encode(String(s));
    }
  }
  class TextDecoder {
    get encoding() {
      return "utf-8";
    }
    decode(b) {
      if (b === undefined) return "";
      return host.utf8Decode(toBytes(b));
    }
  }
  g.TextEncoder = TextEncoder;
  g.TextDecoder = TextDecoder;
  const toBytes = (b) => {
    if (b instanceof Uint8Array) return b;
    if (b instanceof ArrayBuffer) return new Uint8Array(b);
    if (ArrayBuffer.isView(b)) return new Uint8Array(b.buffer, b.byteOffset, b.byteLength);
    throw new TypeError("expected bytes");
  };

  // ---- URL --------------------------------------------------------------------------------
  const enc = (s) => encodeURIComponent(s).replace(/%20/g, "+");
  const dec = (s) => {
    try {
      return decodeURIComponent(s.replace(/\+/g, " "));
    } catch {
      return s;
    }
  };
  class URLSearchParams {
    #list = [];
    #onChange;
    constructor(init = "", onChange) {
      this.#onChange = onChange;
      if (typeof init === "string") {
        for (const part of init.replace(/^\?/, "").split("&")) {
          if (!part) continue;
          const i = part.indexOf("=");
          this.#list.push(i < 0 ? [dec(part), ""] : [dec(part.slice(0, i)), dec(part.slice(i + 1))]);
        }
      } else if (init instanceof URLSearchParams) {
        this.#list = [...init];
      } else if (init && typeof init[Symbol.iterator] === "function") {
        for (const [k, v] of init) this.#list.push([String(k), String(v)]);
      } else if (init && typeof init === "object") {
        for (const k of Object.keys(init)) this.#list.push([k, String(init[k])]);
      }
    }
    #changed() {
      if (this.#onChange) this.#onChange(this.toString());
    }
    append(k, v) {
      this.#list.push([String(k), String(v)]);
      this.#changed();
    }
    delete(k) {
      this.#list = this.#list.filter(([n]) => n !== String(k));
      this.#changed();
    }
    get(k) {
      const e = this.#list.find(([n]) => n === String(k));
      return e ? e[1] : null;
    }
    getAll(k) {
      return this.#list.filter(([n]) => n === String(k)).map(([, v]) => v);
    }
    has(k) {
      return this.#list.some(([n]) => n === String(k));
    }
    set(k, v) {
      const i = this.#list.findIndex(([n]) => n === String(k));
      if (i < 0) this.#list.push([String(k), String(v)]);
      else {
        this.#list[i] = [String(k), String(v)];
        this.#list = this.#list.filter(([n], j) => j <= i || n !== String(k));
      }
      this.#changed();
    }
    sort() {
      this.#list.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
      this.#changed();
    }
    forEach(fn, self) {
      for (const [k, v] of this.#list) fn.call(self, v, k, this);
    }
    keys() {
      return this.#list.map(([k]) => k)[Symbol.iterator]();
    }
    values() {
      return this.#list.map(([, v]) => v)[Symbol.iterator]();
    }
    entries() {
      return this.#list.map((e) => [...e])[Symbol.iterator]();
    }
    [Symbol.iterator]() {
      return this.entries();
    }
    get size() {
      return this.#list.length;
    }
    toString() {
      return this.#list.map(([k, v]) => `${enc(k)}=${enc(v)}`).join("&");
    }
  }
  g.URLSearchParams = URLSearchParams;

  const URL_RE = /^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/(?:([^:@/?#]*)(?::([^@/?#]*))?@)?(\[[^\]]*\]|[^:/?#]*)(?::(\d*))?([^?#]*)(\?[^#]*)?(#.*)?$/;
  const DEFAULT_PORTS = { "http:": "80", "https:": "443" };
  const normalizePath = (p) => {
    const out = [];
    for (const seg of p.split("/").slice(1)) {
      if (seg === "..") out.pop();
      else if (seg !== ".") out.push(seg);
    }
    const last = p.split("/").pop();
    return "/" + out.join("/") + (last === "." || last === ".." ? "/" : "");
  };
  class URL {
    #p;
    #params;
    constructor(input, base) {
      let s = String(input).trim();
      let m = URL_RE.exec(s);
      if (!m) {
        if (base === undefined) throw new TypeError(`Invalid URL: ${s}`);
        const b = new URL(base);
        if (s.startsWith("//")) s = b.protocol + s;
        else if (s.startsWith("/")) s = `${b.origin}${s}`;
        else if (s.startsWith("?")) s = `${b.origin}${b.pathname}${s}`;
        else if (s.startsWith("#")) s = `${b.origin}${b.pathname}${b.search}${s}`;
        else s = `${b.origin}${b.pathname.replace(/[^/]*$/, "")}${s}`;
        m = URL_RE.exec(s);
        if (!m) throw new TypeError(`Invalid URL: ${input}`);
      }
      const protocol = m[1].toLowerCase();
      this.#p = {
        protocol,
        username: m[2] ?? "",
        password: m[3] ?? "",
        hostname: (m[4] ?? "").toLowerCase(),
        port: m[5] && m[5] !== DEFAULT_PORTS[protocol] ? m[5] : "",
        pathname: normalizePath(m[6] || "/"),
        search: m[7] && m[7] !== "?" ? m[7] : "",
        hash: m[8] && m[8] !== "#" ? m[8] : "",
      };
      this.#params = new URLSearchParams(this.#p.search, (q) => (this.#p.search = q ? `?${q}` : ""));
    }
    get protocol() { return this.#p.protocol; }
    set protocol(v) { this.#p.protocol = String(v).replace(/:?$/, ":"); }
    get username() { return this.#p.username; }
    get password() { return this.#p.password; }
    get hostname() { return this.#p.hostname; }
    set hostname(v) { this.#p.hostname = String(v); }
    get port() { return this.#p.port; }
    set port(v) { this.#p.port = String(v); }
    get host() { return this.#p.port ? `${this.#p.hostname}:${this.#p.port}` : this.#p.hostname; }
    get origin() { return `${this.#p.protocol}//${this.host}`; }
    get pathname() { return this.#p.pathname; }
    set pathname(v) { this.#p.pathname = normalizePath("/" + String(v).replace(/^\//, "")); }
    get search() { return this.#p.search; }
    set search(v) {
      const q = String(v).replace(/^\?/, "");
      this.#p.search = q ? `?${q}` : "";
      this.#params = new URLSearchParams(q, (s) => (this.#p.search = s ? `?${s}` : ""));
    }
    get searchParams() { return this.#params; }
    get hash() { return this.#p.hash; }
    set hash(v) {
      const h = String(v).replace(/^#/, "");
      this.#p.hash = h ? `#${h}` : "";
    }
    get href() {
      const auth = this.#p.username ? `${this.#p.username}${this.#p.password ? `:${this.#p.password}` : ""}@` : "";
      return `${this.#p.protocol}//${auth}${this.host}${this.#p.pathname}${this.#p.search}${this.#p.hash}`;
    }
    toString() { return this.href; }
    toJSON() { return this.href; }
  }
  g.URL = URL;

  // ---- Headers ----------------------------------------------------------------------------
  class Headers {
    #map = new Map();
    constructor(init) {
      if (init instanceof Headers) init.forEach((v, k) => this.append(k, v));
      else if (Array.isArray(init)) for (const [k, v] of init) this.append(k, v);
      else if (init && typeof init === "object") for (const k of Object.keys(init)) this.append(k, init[k]);
    }
    append(k, v) {
      const n = String(k).toLowerCase();
      const old = this.#map.get(n);
      this.#map.set(n, old === undefined ? String(v) : `${old}, ${v}`);
    }
    set(k, v) { this.#map.set(String(k).toLowerCase(), String(v)); }
    get(k) { return this.#map.get(String(k).toLowerCase()) ?? null; }
    has(k) { return this.#map.has(String(k).toLowerCase()); }
    delete(k) { this.#map.delete(String(k).toLowerCase()); }
    forEach(fn, self) { for (const [k, v] of this.entries()) fn.call(self, v, k, this); }
    entries() { return [...this.#map.entries()].sort(([a], [b]) => (a < b ? -1 : 1))[Symbol.iterator](); }
    keys() { return [...this.entries()].map(([k]) => k)[Symbol.iterator](); }
    values() { return [...this.entries()].map(([, v]) => v)[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
  }
  g.Headers = Headers;

  // ---- AbortController ----------------------------------------------------------------------
  class AbortSignal {
    #listeners = [];
    aborted = false;
    reason = undefined;
    onabort = null;
    addEventListener(type, fn) {
      if (type === "abort") this.#listeners.push(fn);
    }
    removeEventListener(type, fn) {
      this.#listeners = this.#listeners.filter((l) => l !== fn);
    }
    throwIfAborted() {
      if (this.aborted) throw this.reason;
    }
    _abort(reason) {
      if (this.aborted) return;
      this.aborted = true;
      this.reason = reason === undefined ? abortError("signal is aborted without reason") : reason;
      const event = { type: "abort", target: this };
      for (const fn of [...this.#listeners, this.onabort].filter(Boolean)) {
        try {
          fn.call(this, event);
        } catch (e) {
          report(e);
        }
      }
    }
    static abort(reason) {
      const s = new AbortSignal();
      s._abort(reason);
      return s;
    }
    static timeout(ms) {
      const s = new AbortSignal();
      setTimeout(() => s._abort(abortError("signal timed out", "TimeoutError")), ms);
      return s;
    }
  }
  class AbortController {
    signal = new AbortSignal();
    abort(reason) {
      this.signal._abort(reason);
    }
  }
  const abortError = (message, name = "AbortError") => Object.assign(new Error(message), { name });
  g.AbortSignal = AbortSignal;
  g.AbortController = AbortController;

  // ---- ReadableStream (just enough to read a response body) --------------------------------
  class ReadableStream {
    #chunks;
    locked = false;
    constructor(chunks) {
      this.#chunks = chunks;
    }
    getReader() {
      if (this.locked) throw new TypeError("ReadableStream is locked");
      this.locked = true;
      const chunks = this.#chunks;
      return {
        read: async () => (chunks.length ? { done: false, value: chunks.shift() } : { done: true, value: undefined }),
        releaseLock: () => (this.locked = false),
        cancel: async () => void chunks.splice(0),
        closed: Promise.resolve(),
      };
    }
    async *[Symbol.asyncIterator]() {
      const r = this.getReader();
      for (;;) {
        const { done, value } = await r.read();
        if (done) return;
        yield value;
      }
    }
  }
  g.ReadableStream = ReadableStream;

  // ---- Request / Response / fetch ----------------------------------------------------------
  const bodyBytes = (body) => {
    if (body === undefined || body === null) return null;
    if (typeof body === "string") return host.utf8Encode(body);
    if (body instanceof URLSearchParams) return host.utf8Encode(body.toString());
    return toBytes(body);
  };
  class Body {
    #bytes;
    #stream = null;
    bodyUsed = false;
    constructor(bytes) {
      this.#bytes = bytes;
    }
    #take() {
      if (this.bodyUsed) return Promise.reject(new TypeError("Body has already been consumed"));
      this.bodyUsed = true;
      return Promise.resolve(this.#bytes ?? new Uint8Array(0));
    }
    get body() {
      if (this.#bytes === null) return null;
      if (!this.#stream) this.#stream = new ReadableStream(this.#bytes.length ? [this.#bytes] : []);
      return this.#stream;
    }
    async arrayBuffer() {
      const b = await this.#take();
      return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
    }
    async bytes() {
      return new Uint8Array(await this.arrayBuffer());
    }
    async text() {
      return host.utf8Decode(await this.#take());
    }
    async json() {
      return JSON.parse(await this.text());
    }
  }
  class Request extends Body {
    constructor(input, init = {}) {
      const from = input instanceof Request ? input : null;
      super(bodyBytes(init.body) ?? (from ? from._bytes : null));
      this._bytes = bodyBytes(init.body) ?? (from ? from._bytes : null);
      // A relative URL means the bar itself (busy-s8: it serves the apps backend's weather API).
      this.url = from ? from.url : new URL(String(input), "http://localhost/").href;
      this.method = String(init.method ?? from?.method ?? "GET").toUpperCase();
      this.headers = new Headers(init.headers ?? from?.headers);
      this.signal = init.signal ?? from?.signal ?? null;
      this.dispatcher = init.dispatcher ?? from?.dispatcher;
    }
  }
  class Response extends Body {
    constructor(body = null, init = {}) {
      super(bodyBytes(body));
      this.status = init.status ?? 200;
      this.statusText = init.statusText ?? "";
      this.headers = new Headers(init.headers);
      this.url = init.url ?? "";
      this.type = "basic";
      this.redirected = false;
    }
    get ok() {
      return this.status >= 200 && this.status < 300;
    }
    static json(data, init = {}) {
      const headers = new Headers(init.headers);
      if (!headers.has("content-type")) headers.set("content-type", "application/json");
      return new Response(JSON.stringify(data), { ...init, headers });
    }
    static error() {
      return new Response(null, { status: 0 });
    }
  }
  g.Request = Request;
  g.Response = Response;

  let nextFetch = 1;
  g.fetch = (input, init = {}) => {
    let req;
    try {
      req = new Request(input, init);
    } catch (e) {
      return Promise.reject(e instanceof TypeError ? e : new TypeError(String(e)));
    }
    const signal = req.signal;
    if (signal?.aborted) return Promise.reject(signal.reason);
    const id = nextFetch++;
    const onAbort = () => host.fetchAbort(id);
    signal?.addEventListener("abort", onAbort);
    const headers = [];
    req.headers.forEach((v, k) => headers.push([k, v]));
    return host
      .fetch(id, req.url, req.method, JSON.stringify(headers), req._bytes ?? null)
      .then((r) => {
        signal?.removeEventListener("abort", onAbort);
        if (r.aborted) throw signal?.reason ?? abortError("The operation was aborted");
        if (r.error) throw new TypeError(`fetch failed: ${r.error}`);
        return new Response(r.body, { status: r.status, statusText: r.statusText, headers: JSON.parse(r.headers), url: req.url });
      });
  };
})();
