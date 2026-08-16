// ==UserScript==
// @name         车满满保存后创建时机探针
// @namespace    codex.chemanman.create-timing-probe
// @version      0.1.0
// @description  只读探针：抓取保存成功后页面自身发出的信号（oinfo 等）与 DOM 变化时间线，用于确定"可以创建下一个运单"的可靠时机。不点击、不填写、不拦截任何请求。
// @author       User
// @match        https://t800.chemanman.com/Order*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  "use strict";

  const PROBE_VERSION = "0.1.0";
  const PANEL_ID = "cm-create-timing-probe";
  const BRIDGE_EVENT = "__cmProbeNetV1";
  const MAX_EVENTS = 5000;
  const QUIET_WINDOW_MS = 500;      // 多久无 DOM 变更算"安静"
  const POLL_INTERVAL_MS = 200;     // 按钮/表单身份轮询
  const MAX_TIMELINE_MIN = 180;     // 探针最长记录时长，防止无限占用内存

  // 与正式脚本保持一致的关键接口
  const PATH = {
    save: /\/api\/Order\/Order\/coHandle(?:\/|\?|$)/i,
    oinfo: /\/api\/Order\/Order\/oinfo(?:\/|\?|$)/i,
    create: /\/api\/Order\/Order\/co\?(?:.*&)?raw=1/i,
    api: /^\/api\//i,
  };

  const t0 = Date.now();
  const timeline = [];
  let panel = null;
  let panelList = null;
  let lastSaveSettledAt = null;     // 最近一次 errno=0 的保存完成时刻（相对 t0）
  let lastMutationAt = null;
  let quietTimer = 0;

  const now = () => Date.now() - t0;

  function log(type, data = {}) {
    if (timeline.length >= MAX_EVENTS) timeline.splice(0, timeline.length - MAX_EVENTS + 1);
    const event = { t: Math.round(now()), wall: new Date().toISOString(), type, ...data };
    timeline.push(event);
    renderEvent(event);
    return event;
  }

  // ---------------------------------------------------------------------------
  // 页面上下文网络钩子：与正式脚本相同的注入技术，包 fetch / XHR / Response.json/text
  // ---------------------------------------------------------------------------
  function injectPageHook() {
    const bootstrap = `(${pageHookBootstrap.toString()})(${JSON.stringify(BRIDGE_EVENT)});`;
    const script = document.createElement("script");
    script.textContent = bootstrap;
    (document.documentElement || document.head || document).appendChild(script);
    script.remove();
  }

  function pageHookBootstrap(eventName) {
    const marker = "__cmProbePageHookV1";
    if (window[marker]) return;
    const patterns = {
      save: /\/api\/Order\/Order\/coHandle(?:\/|\?|$)/i,
      api: /^\/api\//i,
    };
    const emit = (type, detail = {}) => {
      try { window.dispatchEvent(new CustomEvent(eventName, { detail: { type, ...detail } })); } catch { /* 忽略 */ }
    };
    const pathOf = (url) => { try { const u = new URL(String(url), location.href); return u.pathname + u.search; } catch { return String(url || "").slice(0, 200); } };
    const interesting = (url) => { try { return patterns.api.test(new URL(String(url), location.href).pathname); } catch { return false; } };
    // 从响应 JSON 里提取探针关心的概要字段（不落完整 body，避免泄密和体积膨胀）
    const summarize = (value) => {
      let payload = null;
      if (typeof value === "string") { try { payload = JSON.parse(value); } catch { return { parse_state: "invalid_json", body_length: value.length }; } }
      else if (value && typeof value === "object") payload = value;
      if (!payload || typeof payload !== "object") return { parse_state: "empty" };
      const out = {
        parse_state: "parsed",
        errno: payload.errno ?? "",
        errmsg: String(payload.errmsg ?? "").slice(0, 80),
        body_length: 0,
      };
      try { out.body_length = JSON.stringify(payload).length; } catch { /* 忽略 */ }
      const orderNum = payload?.res?.order_data?.order_num?.value ?? payload?.req?.order_num ?? "";
      if (orderNum) out.order_num = String(orderNum);
      const odLink = payload?.req?.od_link_id ?? payload?.res?.od_link_id ?? "";
      if (odLink) out.od_link_id = String(odLink);
      return out;
    };
    const requestId = () => `p${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;

    const originalFetch = window.fetch;
    if (typeof originalFetch === "function") {
      window.fetch = function probeFetch(input, init) {
        const url = typeof input === "string" ? input : input?.url || "";
        if (!interesting(url)) return originalFetch.call(this, input, init);
        const id = requestId();
        emit("request", { request_id: id, transport: "fetch", method: String(init?.method || "GET"), path: pathOf(url), is_save: patterns.save.test(String(url)) });
        return originalFetch.call(this, input, init).then((response) => {
          emit("response_headers", { request_id: id, transport: "fetch", status: response.status, path: pathOf(response.url || url) });
          return response;
        }, (error) => {
          emit("network_error", { request_id: id, transport: "fetch", error: String(error?.message || error).slice(0, 200) });
          throw error;
        });
      };
    }

    const responsePrototype = window.Response?.prototype;
    for (const method of ["json", "text"]) {
      const original = responsePrototype?.[method];
      if (typeof original !== "function") continue;
      responsePrototype[method] = function probeResponseBody(...args) {
        const response = this;
        const result = original.apply(response, args);
        if (!interesting(response?.url)) return result;
        return Promise.resolve(result).then((value) => {
          emit("response_body", { transport: `response.${method}`, status: response.status, path: pathOf(response.url), is_save: patterns.save.test(String(response.url)), ...summarize(value) });
          return value;
        }, () => result);
      };
    }

    const xhrPrototype = window.XMLHttpRequest?.prototype;
    if (xhrPrototype) {
      const originalOpen = xhrPrototype.open;
      const originalSend = xhrPrototype.send;
      xhrPrototype.open = function probeOpen(method, url, ...rest) {
        this.__cmProbeUrl = String(url || ""); this.__cmProbeMethod = String(method || "GET");
        return originalOpen.call(this, method, url, ...rest);
      };
      xhrPrototype.send = function probeSend(body) {
        if (!interesting(this.__cmProbeUrl)) return originalSend.call(this, body);
        const id = requestId();
        emit("request", { request_id: id, transport: "xhr", method: this.__cmProbeMethod, path: pathOf(this.__cmProbeUrl), is_save: patterns.save.test(this.__cmProbeUrl) });
        this.addEventListener("loadend", () => {
          let value = ""; try { value = typeof this.responseText === "string" ? this.responseText : ""; } catch { /* 忽略 */ }
          emit("response_body", { request_id: id, transport: "xhr.loadend", status: this.status, path: pathOf(this.responseURL || this.__cmProbeUrl), is_save: patterns.save.test(this.responseURL || this.__cmProbeUrl), ...summarize(value) });
        }, { once: true });
        return originalSend.call(this, body);
      };
    }
    window[marker] = true;
    emit("ready", { mechanisms: ["fetch", "xhr", "response.json", "response.text"] });
  }

  function handleNetEvent(event) {
    const detail = event?.detail;
    if (!detail || typeof detail !== "object") return;
    const path = String(detail.path || "");
    if (detail.type === "ready") { log("net_hook_ready", { mechanisms: (detail.mechanisms || []).join("+") }); return; }
    if (detail.type === "request") {
      log("net_request", { transport: detail.transport, method: detail.method, path: shortPath(path) });
      return;
    }
    if (detail.type === "response_headers") {
      log("net_response_headers", { transport: detail.transport, status: detail.status, path: shortPath(path) });
      return;
    }
    if (detail.type === "network_error") {
      log("net_error", { transport: detail.transport, path: shortPath(path), error: detail.error });
      return;
    }
    if (detail.type === "response_body") {
      const kind = classifyPath(path);
      log("net_response_body", {
        transport: detail.transport, status: detail.status, kind,
        path: shortPath(path), errno: detail.errno ?? "", errmsg: detail.errmsg || "",
        order_num: detail.order_num || "", body_length: detail.body_length ?? "",
      });
      // 保存成功落定：记录 t0，供后续事件换算相对时间
      if (kind === "save" && Number(detail.errno) === 0) {
        lastSaveSettledAt = now();
        lastMutationAt = null;
        log("save_settled", { order_num: detail.order_num || "" });
      }
      if (kind === "oinfo" && lastSaveSettledAt != null) {
        log("oinfo_after_save", { since_save_ms: Math.round(now() - lastSaveSettledAt), errno: detail.errno ?? "" });
      }
      if (kind === "create" && lastSaveSettledAt != null) {
        log("create_after_save", { since_save_ms: Math.round(now() - lastSaveSettledAt), order_num: detail.order_num || "" });
      }
    }
  }

  function classifyPath(path) {
    if (PATH.save.test(path)) return "save";
    if (PATH.oinfo.test(path)) return "oinfo";
    if (PATH.create.test(path)) return "create";
    return "api";
  }

  function shortPath(path) {
    // 去掉 logid 等易变参数，便于肉眼比对
    return String(path).replace(/([?&])(logid|gid)=[^&]*/g, "").replace(/[?&]$/, "").slice(0, 160);
  }

  // ---------------------------------------------------------------------------
  // DOM 观察：变更计数、"创建运单"按钮身份、活动表单根身份、安静窗口
  // ---------------------------------------------------------------------------
  const elementIds = new WeakMap();
  let nextElementId = 1;
  const idOf = (el) => {
    if (!el) return 0;
    let id = elementIds.get(el);
    if (!id) { id = nextElementId++; elementIds.set(el, id); }
    return id;
  };

  const normalizeText = (value) => String(value || "").replace(/\s+/g, "");
  const isVisible = (el) => {
    if (!el?.isConnected) return false;
    const rect = el.getBoundingClientRect?.();
    return Boolean(rect && rect.width > 0 && rect.height > 0);
  };

  // 与正式脚本 locateCreateOrderAction 同源的简化版
  function locateCreateButton() {
    const iconBacked = [...document.querySelectorAll("i.fn-icon.fn-icon-file-add")]
      .filter((icon) => !icon.closest(`#${PANEL_ID}`) && isVisible(icon))
      .map((icon) => icon.closest("a,button,[role='button']"))
      .filter((action) => action && isVisible(action) && normalizeText(action.querySelector("span")?.textContent || action.textContent) === "创建运单");
    if (iconBacked.length) return { elements: [...new Set(iconBacked)], source: "icon" };
    const fallback = [...new Set([...document.querySelectorAll("a,button,[role='button'],.el-button,.ant-btn")]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item) && normalizeText(item.textContent) === "创建运单")
      .map((item) => item.closest("a,button,[role='button'],.el-button,.ant-btn") || item))];
    return { elements: fallback, source: "text" };
  }

  function activeFormAnchor() {
    // 活动表单的锚点：可见的 cor_name 控件（发货人），正式脚本用它定位表单根
    const candidates = [...document.querySelectorAll('[data-path="cor_name"]')]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item));
    return candidates.length === 1 ? candidates[0] : null;
  }

  let lastButtonKey = "";
  let lastButtonSince = 0;
  let buttonStableLogged = false;
  let lastAnchorId = 0;

  function pollIdentity() {
    const { elements, source } = locateCreateButton();
    const key = elements.map((el) => idOf(el)).join(",") || "none";
    if (key !== lastButtonKey) {
      const prevKey = lastButtonKey;
      lastButtonKey = key; lastButtonSince = now(); buttonStableLogged = false;
      log("create_button_changed", {
        from: prevKey || "init", to: key, count: elements.length, source,
        since_save_ms: lastSaveSettledAt != null ? Math.round(now() - lastSaveSettledAt) : "",
      });
    } else if (!buttonStableLogged && key !== "none" && elements.length === 1 && now() - lastButtonSince >= QUIET_WINDOW_MS) {
      buttonStableLogged = true;
      log("create_button_stable", {
        id: key, stable_ms: Math.round(now() - lastButtonSince),
        since_save_ms: lastSaveSettledAt != null ? Math.round(now() - lastSaveSettledAt) : "",
      });
    }
    const anchor = activeFormAnchor();
    const anchorId = idOf(anchor);
    if (anchorId !== lastAnchorId) {
      const prev = lastAnchorId;
      lastAnchorId = anchorId;
      log("form_anchor_changed", {
        from: prev || "init", to: anchorId || "none",
        since_save_ms: lastSaveSettledAt != null ? Math.round(now() - lastSaveSettledAt) : "",
      });
    }
  }

  function installMutationObserver() {
    let pendingCount = 0;
    let flushTimer = 0;
    const observer = new MutationObserver((mutations) => {
      const relevant = mutations.filter((m) => !(m.target instanceof Element && m.target.closest?.(`#${PANEL_ID}`)));
      if (!relevant.length) return;
      pendingCount += relevant.length;
      lastMutationAt = now();
      if (!flushTimer) {
        flushTimer = setTimeout(() => {
          flushTimer = 0;
          log("dom_mutation_burst", {
            count: pendingCount,
            since_save_ms: lastSaveSettledAt != null ? Math.round(now() - lastSaveSettledAt) : "",
          });
          pendingCount = 0;
        }, 120);
      }
      scheduleQuietCheck();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  function scheduleQuietCheck() {
    if (quietTimer) clearTimeout(quietTimer);
    quietTimer = setTimeout(() => {
      quietTimer = 0;
      if (lastMutationAt == null) return;
      log("dom_quiet", {
        quiet_ms: QUIET_WINDOW_MS,
        last_mutation_at: Math.round(lastMutationAt),
        since_save_ms: lastSaveSettledAt != null ? Math.round(now() - lastSaveSettledAt) : "",
      });
    }, QUIET_WINDOW_MS);
  }

  // ---------------------------------------------------------------------------
  // 面板：最近事件 + 导出
  // ---------------------------------------------------------------------------
  function installPanel() {
    if (panel || !document.body) return;
    panel = document.createElement("div");
    panel.id = PANEL_ID;
    panel.style.cssText = "position:fixed;right:8px;bottom:8px;z-index:2147483647;width:430px;max-height:300px;background:#fff;border:1px solid #d0d7de;border-radius:6px;box-shadow:0 4px 16px rgba(0,0,0,.18);font:12px/1.5 monospace;color:#1f2328;display:flex;flex-direction:column;overflow:hidden;";
    const header = document.createElement("div");
    header.style.cssText = "padding:6px 8px;background:#f6f8fa;border-bottom:1px solid #d0d7de;display:flex;gap:6px;align-items:center;";
    const title = document.createElement("span");
    title.textContent = `创建时机探针 v${PROBE_VERSION}`;
    title.style.cssText = "flex:1;font-weight:600;";
    const exportButton = document.createElement("button");
    exportButton.textContent = "导出JSON";
    const clearButton = document.createElement("button");
    clearButton.textContent = "清空";
    exportButton.onclick = exportTimeline;
    clearButton.onclick = () => { timeline.length = 0; if (panelList) panelList.innerHTML = ""; log("cleared"); };
    header.append(title, exportButton, clearButton);
    panelList = document.createElement("div");
    panelList.style.cssText = "padding:4px 8px;overflow-y:auto;flex:1;white-space:pre-wrap;word-break:break-all;";
    panel.append(header, panelList);
    document.body.appendChild(panel);
  }

  function renderEvent(event) {
    if (!panelList) return;
    const line = document.createElement("div");
    const { t, wall, type, ...rest } = event;
    const data = Object.entries(rest).filter(([, v]) => v !== "" && v != null).map(([k, v]) => `${k}=${v}`).join(" ");
    line.textContent = `${String(t).padStart(7)}ms ${type} ${data}`;
    if (type === "save_settled" || type === "oinfo_after_save" || type === "create_after_save" || type === "create_button_stable") line.style.color = "#116329";
    panelList.appendChild(line);
    while (panelList.childElementCount > 60) panelList.firstElementChild.remove();
    panelList.scrollTop = panelList.scrollHeight;
  }

  function exportTimeline() {
    const payload = {
      probe_version: PROBE_VERSION,
      exported_at: new Date().toISOString(),
      page: location.href,
      user_agent: navigator.userAgent,
      events: timeline,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `创建时机探针_${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 5000);
  }

  // ---------------------------------------------------------------------------
  function start() {
    injectPageHook();
    window.addEventListener(BRIDGE_EVENT, handleNetEvent);
    log("probe_started", { version: PROBE_VERSION, page: location.href });
    const boot = () => {
      installPanel();
      installMutationObserver();
      setInterval(pollIdentity, POLL_INTERVAL_MS);
      pollIdentity();
    };
    if (document.body) boot();
    else {
      const observer = new MutationObserver(() => {
        if (!document.body) return;
        observer.disconnect(); boot();
      });
      observer.observe(document, { childList: true, subtree: true });
    }
    setTimeout(() => log("probe_max_age_reached"), MAX_TIMELINE_MIN * 60 * 1000);
  }

  start();
})();
