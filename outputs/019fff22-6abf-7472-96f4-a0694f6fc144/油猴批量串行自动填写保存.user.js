// ==UserScript==
// @name         车满满批量串行自动填写保存
// @namespace    codex.chemanman.batch-serial.production
// @version      1.0.13
// @description  从本地 XLSX 严格逐条填写保存，避免重复点击已活动的新运单页签及其自动菜单。
// @author       User
// @match        https://t800.chemanman.com/Order*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  "use strict";

  const SCRIPT_VERSION = "1.0.13";
  const CHECKPOINT_VERSION = 3;
  const MAX_ORDERS = 100;
  const SERIAL_CONCURRENCY = 1;
  const PANEL_ID = "cm-batch-serial-production-host";
  const CHECKPOINT_KEY = "cm-batch-serial-checkpoint-v3";
  const PREVIOUS_SERIAL_CHECKPOINT_KEY = "cm-batch-serial-checkpoint-v2";
  const LEGACY_CHECKPOINT_KEY = "cm-batch-parallel-checkpoint-v1";
  const LEDGER_KEY = "cm-batch-order-ledger-v1";
  const LEGACY_LEDGER_KEY = "cm-batch-parallel-ledger-v1";
  const BLOCKED_REQUEST = /\/api\/Order\/Order\/coHandle(?:\/|\?|$)/i;
  const SAVE_BUTTON_TEXT = /^(保存(?:\(F9\))?|保存并打印|保存并关闭|提交运单|继续保存)$/i;
  const DIAGNOSTIC_SCHEMA_VERSION = 1;
  const DIAGNOSTIC_EVENT_LIMIT = 500;
  const POST_SAVE_COOLDOWN_MS = 3000;
  const BRIDGE_EVENT = `cm-save-observer-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const TEXT_DECODER = new TextDecoder("utf-8");
  const REQUIRED_HEADERS = [
    "schema_version", "batch_id", "source_record_id", "source_label", "destination_text",
    "delivery_type", "sender_name", "receiver_name", "receiver_mobile", "goods_name",
    "package", "quantity", "weight", "volume", "freight", "payment_type",
  ];
  const FIELD_MAPPINGS = [
    { key: "destination_text", dataPath: "arr", kind: "autocomplete", required: true },
    { key: "delivery_type", dataPath: "delivery_mode", kind: "choice", required: true, display: { delivery: "送货", pickup: "自提" } },
    { key: "sender_name", dataPath: "cor_name", kind: "text", required: true },
    { key: "receiver_name", dataPath: "cee_name", kind: "text", required: true },
    { key: "receiver_mobile", dataPath: "cee_mobile", kind: "text", required: false },
    { key: "goods_name", dataPath: "name_1", kind: "text", required: true },
    { key: "package", dataPath: "pkg_1", kind: "text", required: false, suggestionOptional: true },
    { key: "quantity", dataPath: "num_1", kind: "number", required: true },
    { key: "weight", dataPath: "weight_1", kind: "number", required: false },
    { key: "volume", dataPath: "volume_1", kind: "number", required: false },
    { key: "freight", dataPath: "co_freight_f", kind: "number", required: true },
    { key: "payment_type", dataPath: "pay_mode", kind: "choice", required: true, display: { pay_billing: "现付" } },
  ];
  const DIRECT_FIELDS = FIELD_MAPPINGS.filter((item) => item.kind === "text" || item.kind === "number");
  const CUSTOM_FIELDS = FIELD_MAPPINGS.filter((item) => item.kind === "autocomplete" || item.kind === "choice");
  const TERMINAL_STATES = new Set(["saved", "abandoned"]);
  const MANUAL_STATES = new Set([
    "mapping_failed", "save_failed", "decision_required", "save_ambiguous", "manual_pending",
    "interrupted_manual", "order_number_blocked", "create_entry_blocked", "abandon_pending_close",
    "legacy_checkpoint_blocked",
  ]);

  const state = {
    tasks: [], parsedImport: null, importErrors: [], duplicateKeys: [], fileName: "", batchId: "",
    running: false, batchStarted: false, pauseRequested: false, stopRequested: false, globalHalt: "",
    authorization: false, safetyBlocks: 0, savePermit: null, clickPermitId: "", baselineTabs: new Set(),
    recovered: false, legacyCheckpoint: false, completed: false, reportExported: false, ui: null,
    diagnostics: { session_id: stableHash(`${Date.now()}-${Math.random()}`), started_at: now(), bridge_ready: false, events: [] },
  };
  let operationMutex = Promise.resolve();
  let safetyListenersInstalled = false;
  let performanceObserverInstalled = false;

  installPageNetworkObserver();
  installSafetyGuards();
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", initializeUi, { once: true });
  else initializeUi();

  function installPageNetworkObserver() {
    window.addEventListener?.(BRIDGE_EVENT, handlePageObserverEvent);
    const inject = () => {
      try {
        const script = document.createElement("script");
        script.textContent = `;(${pageObserverBootstrap.toString()})(${JSON.stringify(BRIDGE_EVENT)});`;
        (document.head || document.documentElement).appendChild(script); script.remove();
        recordDiagnostic("page_observer_injected", { document_state: document.readyState });
      } catch (error) {
        recordDiagnostic("page_observer_injection_failed", { error: errorMessage(error) });
      }
    };
    if (document.documentElement) inject();
    else if (typeof MutationObserver === "function") {
      const observer = new MutationObserver(() => {
        if (!document.documentElement) return;
        observer.disconnect(); inject();
      });
      observer.observe(document, { childList: true, subtree: true });
    } else document.addEventListener?.("DOMContentLoaded", inject, { once: true });
  }

  function pageObserverBootstrap(eventName) {
    const marker = "__cmProductionSaveObserverV1";
    if (window[marker]?.addChannel) { window[marker].addChannel(eventName); return; }
    const channels = new Set([eventName]);
    const match = (value) => /\/api\/Order\/Order\/coHandle(?:\/|\?|$)/i.test(String(value || ""));
    const hash = (value) => { let output = 2166136261; for (const char of String(value)) { output ^= char.charCodeAt(0); output = Math.imul(output, 16777619); } return `fnv1a-${(output >>> 0).toString(16).padStart(8, "0")}`; };
    const emit = (type, detail = {}) => {
      for (const channel of channels) window.dispatchEvent(new CustomEvent(channel, { detail: { type, ...detail } }));
    };
    const describe = (status, value, source, url, contentType = "") => {
      let payload = null; let bodyLength = 0; let bodyHash = ""; let parseState = "not_attempted";
      if (typeof value === "string") {
        bodyLength = value.length; bodyHash = hash(value);
        try { payload = JSON.parse(value); parseState = "parsed"; } catch { parseState = "invalid_json"; }
      } else if (value && typeof value === "object") {
        payload = value; parseState = "parsed_object";
        try { const serialized = JSON.stringify(value); bodyLength = serialized.length; bodyHash = hash(serialized); } catch { /* metadata only */ }
      }
      const orderData = payload?.res?.order_data;
      const preferred = ["od_basic_id", "od_id", "od_link_id", "order_num", "id", "order_id", "order_no", "co_id", "co_num", "oid"];
      const pairs = orderData && typeof orderData === "object" ? preferred.filter((key) => orderData[key] != null && String(orderData[key]) !== "").map((key) => [key, String(orderData[key])]) : [];
      return {
        status: Number(status) || 0, source, path: (() => { try { return new URL(String(url), location.href).pathname; } catch { return ""; } })(),
        content_type: String(contentType || "").slice(0, 120), parse_state: parseState, body_length: bodyLength, body_hash: bodyHash,
        top_level_keys: payload && typeof payload === "object" ? Object.keys(payload).slice(0, 30) : [],
        errno: payload?.errno ?? "", message: String(payload?.errmsg || "").slice(0, 240),
        identity_keys: pairs.map(([key]) => key), identity_fingerprint: pairs.length ? hash(JSON.stringify(pairs)) : "",
      };
    };
    const requestId = () => `page-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    const currentFetch = window.fetch;
    if (typeof currentFetch === "function" && !currentFetch.__cmPageObserved) {
      const observedFetch = function observedFetch(input, init) {
        const url = typeof input === "string" ? input : input?.url || "";
        if (!match(url)) return currentFetch.call(this, input, init);
        const id = requestId(); emit("request", { request_id: id, transport: "page-fetch", path: (() => { try { return new URL(String(url), location.href).pathname; } catch { return ""; } })() });
        return currentFetch.call(this, input, init).then((response) => {
          emit("response_headers", { request_id: id, transport: "page-fetch", status: response.status, path: (() => { try { return new URL(response.url || String(url), location.href).pathname; } catch { return ""; } })(), content_type: response.headers?.get?.("content-type") || "" });
          return response;
        }, (error) => { emit("network_error", { request_id: id, transport: "page-fetch", error: String(error?.message || error).slice(0, 240) }); throw error; });
      };
      observedFetch.__cmPageObserved = true; window.fetch = observedFetch;
    }

    const responsePrototype = window.Response?.prototype;
    for (const method of ["json", "text"]) {
      const original = responsePrototype?.[method];
      if (typeof original !== "function" || original.__cmPageObserved) continue;
      const observed = function observedResponseBody(...args) {
        const response = this; const result = original.apply(response, args);
        if (!match(response?.url)) return result;
        return Promise.resolve(result).then((value) => {
          emit("response_body", describe(response.status, value, `response.${method}`, response.url, response.headers?.get?.("content-type") || ""));
          return value;
        }, (error) => { emit("body_read_error", { source: `response.${method}`, status: response?.status || 0, error: String(error?.message || error).slice(0, 240) }); throw error; });
      };
      observed.__cmPageObserved = true; responsePrototype[method] = observed;
    }

    const xhrPrototype = window.XMLHttpRequest?.prototype;
    if (xhrPrototype && !xhrPrototype.send.__cmPageObserved) {
      const originalOpen = xhrPrototype.open; const originalSend = xhrPrototype.send;
      xhrPrototype.open = function observedOpen(method, url, ...rest) { this.__cmPageSaveUrl = String(url || ""); return originalOpen.call(this, method, url, ...rest); };
      xhrPrototype.send = function observedSend(body) {
        if (!match(this.__cmPageSaveUrl)) return originalSend.call(this, body);
        const id = requestId(); emit("request", { request_id: id, transport: "page-xhr", path: (() => { try { return new URL(this.__cmPageSaveUrl, location.href).pathname; } catch { return ""; } })() });
        this.addEventListener("loadend", () => {
          let value = ""; try { value = typeof this.responseText === "string" ? this.responseText : ""; } catch { /* metadata only */ }
          emit("response_body", { request_id: id, transport: "page-xhr", ...describe(this.status, value, "xhr.loadend", this.responseURL || this.__cmPageSaveUrl, this.getResponseHeader?.("content-type") || "") });
        }, { once: true });
        return originalSend.call(this, body);
      };
      xhrPrototype.send.__cmPageObserved = true;
    }
    window[marker] = { addChannel(channel) { channels.add(channel); emit("ready", { mechanisms: ["fetch", "xhr", "response.json", "response.text"] }); } };
    emit("ready", { mechanisms: ["fetch", "xhr", "response.json", "response.text"] });
  }

  function handlePageObserverEvent(event) {
    const detail = event?.detail;
    if (!detail || typeof detail !== "object") return;
    if (detail.type === "ready") state.diagnostics.bridge_ready = true;
    const permit = state.savePermit;
    recordDiagnostic(`page_${detail.type || "unknown"}`, detail, permit?.taskId || "");
    if (!permit || permit.status === "settled") return;
    if (detail.type === "request") {
      permit.bridgeRequestId = detail.request_id || permit.bridgeRequestId || "";
      if (permit.status === "armed") markPermitSent(permit, detail.transport || "page-observer", detail.path || "");
      return;
    }
    if (detail.type === "network_error" && (!detail.request_id || detail.request_id === permit.bridgeRequestId)) {
      settleSavePermit(permit, ambiguousSave(`保存请求发生网络错误：${detail.error || "未知错误"}`, detail.transport || "page-observer"));
      return;
    }
    if (detail.type === "response_body") {
      settleSavePermit(permit, classifyObservedResponse(detail));
    }
  }

  function classifyObservedResponse(detail) {
    const status = Number(detail.status) || 0; const channel = detail.source || detail.transport || "page-observer";
    if (status === 401 || status === 403) return { state: "session_expired", error: `保存接口返回 HTTP ${status}，登录可能失效`, channel, http_status: status, errno: "", message: "", identity_keys: [], identity_fingerprint: "" };
    if (status < 200 || status >= 300) return { ...ambiguousSave(`保存接口返回 HTTP ${status || "未知"}`, channel), http_status: status || "" };
    if (!String(detail.parse_state || "").startsWith("parsed")) return { ...ambiguousSave(`保存接口响应未能解析为JSON（${detail.parse_state || "未知"}）`, channel), http_status: status };
    const errno = detail.errno; const message = safeDiagnosticText(detail.message || "", state.savePermit?.taskId || "");
    const base = { channel, http_status: status, errno: errno ?? "", message, identity_keys: Array.isArray(detail.identity_keys) ? detail.identity_keys : [], identity_fingerprint: detail.identity_fingerprint || "" };
    if (Number(errno) === 0) return { ...base, state: "saved", error: "" };
    if (Number(errno) === 320) return { ...base, state: "decision_required", error: message || "网站要求人工确认是否继续保存" };
    return { ...base, state: "save_failed", error: message || `网站拒绝保存（errno=${errno ?? "未知"}）` };
  }

  function markPermitSent(permit, channel, url) {
    if (!permit || permit.status !== "armed") return false;
    permit.status = "sent"; permit.channel = channel; permit.requestSeenAt = now(); permit.requestPath = redactUrl(url);
    permit.seenResolve({ channel, at: permit.requestSeenAt });
    recordDiagnostic("permit_request_seen", { permit_id: permit.id, channel, path: permit.requestPath }, permit.taskId);
    return true;
  }

  function recordDiagnostic(type, detail = {}, taskId = "") {
    const task = state.tasks.find((item) => item.id === taskId);
    const event = {
      seq: (state.diagnostics.events.at(-1)?.seq || 0) + 1,
      at: now(), elapsed_ms: Math.round(typeof performance === "object" && performance.now ? performance.now() : 0), type: String(type), task_id: taskId || "",
      detail: sanitizeDiagnosticValue(detail, task),
    };
    state.diagnostics.events.push(event);
    if (state.diagnostics.events.length > DIAGNOSTIC_EVENT_LIMIT) state.diagnostics.events.splice(0, state.diagnostics.events.length - DIAGNOSTIC_EVENT_LIMIT);
    return event;
  }

  function sanitizeDiagnosticValue(value, task, depth = 0, key = "") {
    if (depth > 5) return "<深度限制>";
    if (/cookie|token|authorization|request.?body|post.?data|payload|sender|receiver|mobile|phone|full.?url|order.?number/i.test(key)) return "<已脱敏>";
    if (Array.isArray(value)) return value.slice(0, 40).map((item) => sanitizeDiagnosticValue(item, task, depth + 1, key));
    if (value && typeof value === "object") {
      const output = {};
      for (const [childKey, childValue] of Object.entries(value).slice(0, 60)) output[childKey] = sanitizeDiagnosticValue(childValue, task, depth + 1, childKey);
      return output;
    }
    if (typeof value === "string") return safeDiagnosticText(value, task?.id || "").slice(0, 500);
    return typeof value === "number" || typeof value === "boolean" || value == null ? value : String(value).slice(0, 200);
  }

  function safeDiagnosticText(value, taskId = "") {
    const task = state.tasks.find((item) => item.id === taskId);
    let output = String(value || "");
    if (task) {
      for (const key of ["source_label", "destination_text", "sender_name", "receiver_name", "receiver_mobile", "goods_name", "package"]) {
        const text = String(task.order?.[key] ?? "").trim();
        if (text.length >= 2) output = output.split(text).join("<已脱敏>");
      }
    }
    output = output.replace(/[?&](?:token|key|auth|session|sign)=[^&#\s]+/gi, (match) => `${match.slice(0, match.indexOf("=") + 1)}<已脱敏>`);
    return output.replace(/1\d{10}/g, "<手机号已脱敏>");
  }

  function installSafetyGuards() {
    const originalFetch = window.fetch;
    if (typeof originalFetch === "function" && !originalFetch.__cmProductionGuarded) {
      const guardedFetch = function guardedFetch(input, init) {
        const url = typeof input === "string" ? input : input?.url || "";
        if (!BLOCKED_REQUEST.test(String(url))) return originalFetch.call(this, input, init);
        const permit = takeSavePermit("fetch", String(url));
        if (!permit) { notifySafetyBlock("fetch", String(url)); return Promise.reject(new Error("正式脚本已阻止未授权保存请求")); }
        return originalFetch.call(this, input, init).then(async (response) => {
          let body = "";
          try { body = await response.clone().text(); }
          catch (error) { recordDiagnostic("fetch_body_read_failed", { error: errorMessage(error), status: response.status }, permit.taskId); }
          recordDiagnostic("fetch_response_body", responseDiagnosticMetadata(response.status, body, response.headers?.get?.("content-type") || "", "fetch.clone.text"), permit.taskId);
          settleSavePermit(permit, classifySaveResponse(response.status, body, "fetch"));
          return response;
        }, (error) => {
          settleSavePermit(permit, ambiguousSave(`保存请求发生网络错误：${errorMessage(error)}`, "fetch"));
          throw error;
        });
      };
      guardedFetch.__cmProductionGuarded = true;
      window.fetch = guardedFetch;
      recordDiagnostic("fetch_guard_installed", { replaced_name: originalFetch.name || "anonymous" });
    }
    if (!XMLHttpRequest.prototype.open.__cmProductionGuarded) {
      const originalOpen = XMLHttpRequest.prototype.open;
      const guardedOpen = function guardedOpen(method, url, ...rest) {
        this.__cmProductionSaveUrl = String(url || "");
        return originalOpen.call(this, method, url, ...rest);
      };
      guardedOpen.__cmProductionGuarded = true;
      XMLHttpRequest.prototype.open = guardedOpen;
      recordDiagnostic("xhr_open_guard_installed");
    }
    if (!XMLHttpRequest.prototype.send.__cmProductionGuarded) {
      const originalSend = XMLHttpRequest.prototype.send;
      const guardedSend = function guardedSend(body) {
        if (!BLOCKED_REQUEST.test(this.__cmProductionSaveUrl || "")) return originalSend.call(this, body);
        const permit = takeSavePermit("xhr", this.__cmProductionSaveUrl);
        if (!permit) { notifySafetyBlock("xhr", this.__cmProductionSaveUrl); throw new Error("正式脚本已阻止未授权保存请求"); }
        this.addEventListener("loadend", () => {
          let text = "";
          try { text = typeof this.responseText === "string" ? this.responseText : ""; } catch { /* classified below */ }
          recordDiagnostic("xhr_response_body", responseDiagnosticMetadata(this.status, text, this.getResponseHeader?.("content-type") || "", "xhr.loadend"), permit.taskId);
          if (!this.status) settleSavePermit(permit, ambiguousSave("保存请求已发出，但没有收到可确认响应", "xhr"));
          else settleSavePermit(permit, classifySaveResponse(this.status, text, "xhr"));
        }, { once: true });
        return originalSend.call(this, body);
      };
      guardedSend.__cmProductionGuarded = true;
      XMLHttpRequest.prototype.send = guardedSend;
      recordDiagnostic("xhr_send_guard_installed");
    }
    installDirectResponseObserver();
    installPerformanceObserver();
    if (safetyListenersInstalled) return;
    safetyListenersInstalled = true;
    document.addEventListener("click", (event) => {
      const clickable = event.target?.closest?.("button,a,[role='button'],.el-button,.ant-btn");
      if (!clickable || clickable.closest?.(`#${PANEL_ID}`) || !SAVE_BUTTON_TEXT.test(normalizeText(clickable.textContent))) return;
      const permit = state.savePermit?.id === state.clickPermitId && state.savePermit.status === "armed" ? state.savePermit : null;
      if (permit) { state.clickPermitId = ""; return; }
      event.preventDefault(); event.stopImmediatePropagation();
      notifySafetyBlock("click", normalizeText(clickable.textContent));
    }, true);
    document.addEventListener("keydown", (event) => {
      if (event.key !== "F9" && event.code !== "F9") return;
      event.preventDefault(); event.stopImmediatePropagation(); notifySafetyBlock("keyboard", "F9");
    }, true);
  }

  function installDirectResponseObserver() {
    const prototype = window.Response?.prototype;
    for (const method of ["json", "text"]) {
      const original = prototype?.[method];
      if (typeof original !== "function" || original.__cmProductionGuarded) continue;
      const guarded = function guardedResponseBody(...args) {
        const response = this; const result = original.apply(response, args);
        if (!BLOCKED_REQUEST.test(String(response?.url || ""))) return result;
        return Promise.resolve(result).then((value) => {
          const permit = state.savePermit && state.savePermit.status !== "settled" ? state.savePermit : null;
          if (!permit) return value;
          if (permit.status === "armed") markPermitSent(permit, `response.${method}`, response.url || "");
          let text = ""; try { text = typeof value === "string" ? value : JSON.stringify(value); } catch { /* classified as invalid */ }
          recordDiagnostic("direct_response_body", responseDiagnosticMetadata(response.status, text, response.headers?.get?.("content-type") || "", `response.${method}`), permit.taskId);
          settleSavePermit(permit, classifySaveResponse(response.status, text, `response.${method}`));
          return value;
        }, (error) => {
          const permit = state.savePermit && state.savePermit.status !== "settled" ? state.savePermit : null;
          if (permit) recordDiagnostic("direct_response_body_failed", { source: `response.${method}`, error: errorMessage(error), status: response?.status || 0 }, permit.taskId);
          throw error;
        });
      };
      guarded.__cmProductionGuarded = true; prototype[method] = guarded;
      recordDiagnostic("response_guard_installed", { method });
    }
  }

  function installPerformanceObserver() {
    if (performanceObserverInstalled || typeof PerformanceObserver !== "function") return;
    performanceObserverInstalled = true;
    try {
      const observer = new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          if (!BLOCKED_REQUEST.test(String(entry.name || ""))) continue;
          const permit = state.savePermit && state.savePermit.status !== "settled" ? state.savePermit : null;
          if (!permit) continue;
          recordDiagnostic("performance_resource", {
            path: redactUrl(entry.name), initiator_type: entry.initiatorType || "", duration_ms: Math.round(entry.duration || 0),
            response_status: Number(entry.responseStatus) || 0, transfer_size: Number(entry.transferSize) || 0,
            encoded_body_size: Number(entry.encodedBodySize) || 0, decoded_body_size: Number(entry.decodedBodySize) || 0,
            next_hop_protocol: entry.nextHopProtocol || "",
          }, permit.taskId);
          if (permit.status === "armed") markPermitSent(permit, "performance", entry.name);
          if (!permit.performanceFallbackTimer) {
            permit.performanceFallbackTimer = setTimeout(() => {
              if (permit.status !== "settled") settleSavePermit(permit, ambiguousSave("检测到保存请求已经完成，但网站读取响应时仍未向脚本暴露结果；请导出诊断JSON并查询订单列表确认", "performance"));
            }, 1500);
          }
        }
      });
      observer.observe({ type: "resource", buffered: true });
    } catch { performanceObserverInstalled = false; }
  }

  function armSavePermit(task, reason) {
    if (state.savePermit && state.savePermit.status !== "settled") throw new Error("当前订单的保存请求尚未结束");
    let seenResolve; let resultResolve;
    const permit = {
      id: `permit-${Date.now()}-${Math.random().toString(36).slice(2)}`, taskId: task.id, reason, status: "armed",
      armedAt: now(), requestSeenAt: "", channel: "",
      requestSeenPromise: new Promise((resolve) => { seenResolve = resolve; }),
      resultPromise: new Promise((resolve) => { resultResolve = resolve; }), seenResolve, resultResolve, timer: null, performanceFallbackTimer: null,
    };
    recordDiagnostic("permit_armed", {
      permit_id: permit.id, reason, fetch_guarded: Boolean(window.fetch?.__cmProductionGuarded),
      xhr_open_guarded: Boolean(XMLHttpRequest.prototype.open.__cmProductionGuarded), xhr_send_guarded: Boolean(XMLHttpRequest.prototype.send.__cmProductionGuarded),
      page_observer_ready: state.diagnostics.bridge_ready, visibility: document.visibilityState,
    }, task.id);
    permit.timer = setTimeout(() => {
      if (permit.status === "settled") return;
      settleSavePermit(permit, permit.status === "sent"
        ? ambiguousSave("保存请求已发出但等待响应超时；请导出诊断JSON并人工查询订单", permit.channel || "timeout")
        : ambiguousSave("点击保存后未能观察到请求或响应；网站可能已经保存，请导出诊断JSON并查询订单列表确认", "unobserved"));
    }, 20000);
    state.savePermit = permit;
    return permit;
  }

  function takeSavePermit(channel, url) {
    const permit = state.savePermit?.status === "armed" ? state.savePermit : null;
    if (!permit || !state.authorization) return null;
    markPermitSent(permit, channel, url);
    return permit;
  }

  function settleSavePermit(permit, result) {
    if (!permit || permit.status === "settled") return;
    clearTimeout(permit.timer); clearTimeout(permit.performanceFallbackTimer); permit.status = "settled"; permit.settledAt = now();
    permit.result = { ...result, permit_id: permit.id, task_id: permit.taskId, armed_at: permit.armedAt, request_seen_at: permit.requestSeenAt, settled_at: permit.settledAt };
    recordDiagnostic("permit_settled", {
      permit_id: permit.id, result_state: result.state, channel: result.channel || permit.channel || "", http_status: result.http_status ?? "",
      errno: result.errno ?? "", error: result.error || "", armed_at: permit.armedAt, request_seen_at: permit.requestSeenAt, settled_at: permit.settledAt,
    }, permit.taskId);
    permit.resultResolve(permit.result);
  }

  function responseDiagnosticMetadata(status, text, contentType, source) {
    const output = { status: Number(status) || 0, source, content_type: String(contentType || "").slice(0, 120), body_length: String(text || "").length, body_hash: stableHash(String(text || "")), parse_state: "invalid_json", top_level_keys: [], errno: "" };
    try { const payload = JSON.parse(String(text || "")); output.parse_state = "parsed"; output.top_level_keys = payload && typeof payload === "object" ? Object.keys(payload).slice(0, 30) : []; output.errno = payload?.errno ?? ""; }
    catch { /* structural metadata is enough */ }
    return output;
  }

  function classifySaveResponse(httpStatus, text, channel) {
    if (httpStatus === 401 || httpStatus === 403) return { state: "session_expired", error: `保存接口返回 HTTP ${httpStatus}，登录可能失效`, channel, http_status: httpStatus, errno: "", message: "", identity_keys: [], identity_fingerprint: "" };
    if (httpStatus < 200 || httpStatus >= 300) return { ...ambiguousSave(`保存接口返回 HTTP ${httpStatus}`, channel), http_status: httpStatus };
    let payload;
    try { payload = JSON.parse(text); } catch { return { ...ambiguousSave("保存接口响应无法解析", channel), http_status: httpStatus }; }
    const errno = payload?.errno; const message = String(payload?.errmsg || "");
    const identity = extractOrderIdentity(payload?.res?.order_data);
    const base = { channel, http_status: httpStatus, errno: errno ?? "", message, ...identity };
    if (Number(errno) === 0) return { ...base, state: "saved", error: "" };
    if (Number(errno) === 320) return { ...base, state: "decision_required", error: message || "网站要求人工确认是否继续保存" };
    return { ...base, state: "save_failed", error: message || `网站拒绝保存（errno=${errno ?? "未知"}）` };
  }

  function extractOrderIdentity(orderData) {
    if (!orderData || typeof orderData !== "object") return { identity_keys: [], identity_fingerprint: "" };
    const preferred = ["od_basic_id", "od_id", "od_link_id", "order_num", "id", "order_id", "order_no", "co_id", "co_num", "oid"];
    const pairs = preferred.filter((key) => orderData[key] != null && String(orderData[key]) !== "").map((key) => [key, String(orderData[key])]);
    return { identity_keys: pairs.map(([key]) => key), identity_fingerprint: pairs.length ? stableHash(JSON.stringify(pairs)) : "" };
  }

  function ambiguousSave(message, channel) {
    return { state: "save_ambiguous", error: message, channel, http_status: "", errno: "", message: "结果不确定，禁止自动重试", identity_keys: [], identity_fingerprint: "" };
  }

  function notifySafetyBlock(channel, detail) {
    state.safetyBlocks += 1;
    setStatus(`已阻止未授权保存操作（${channel}），累计 ${state.safetyBlocks} 次。`, "warning");
    renderSummary();
  }

  function initializeUi() {
    if (document.getElementById(PANEL_ID)) return;
    const host = document.createElement("div"); host.id = PANEL_ID;
    const shadow = host.attachShadow({ mode: "open" });
    shadow.innerHTML = buildPanelHtml(); document.documentElement.appendChild(host);
    state.ui = Object.fromEntries(["file", "authorize", "start", "pause", "resume", "stop", "export", "diagnostic", "unlock", "newBatch", "discard", "filter", "status", "stats", "rows", "errors"].map((id) => [id, shadow.getElementById(id)]));
    state.ui.file.addEventListener("change", handleFileSelection);
    state.ui.authorize.addEventListener("change", () => { state.authorization = state.ui.authorize.checked; renderSummary(); });
    state.ui.start.addEventListener("click", startBatch);
    state.ui.pause.addEventListener("click", pauseBatch);
    state.ui.resume.addEventListener("click", resumeBatch);
    state.ui.stop.addEventListener("click", stopBatch);
    state.ui.export.addEventListener("click", exportReportCsv);
    state.ui.diagnostic.addEventListener("click", exportDiagnosticJson);
    state.ui.unlock.addEventListener("click", unlockDuplicates);
    state.ui.newBatch.addEventListener("click", resetForNewBatch);
    state.ui.discard.addEventListener("click", discardRecoveredBatch);
    state.ui.filter.addEventListener("change", renderSummary);
    shadow.addEventListener("click", handlePanelAction);
    restoreCheckpoint();
    if (detectConflictingScript()) state.importErrors = ["检测到旧测试脚本仍在运行。请在油猴中只启用正式版并刷新页面。"];
    renderSummary();
  }

  function buildPanelHtml() {
    return `
      <style>
        :host{all:initial}.panel{position:fixed;right:14px;bottom:14px;width:560px;max-height:86vh;z-index:2147483647;color:#172033;background:#fff;border:1px solid #9db4cc;border-radius:10px;box-shadow:0 12px 32px rgba(10,36,64,.28);font:13px/1.4 "Microsoft YaHei",Arial,sans-serif;overflow:hidden}
        .head{display:flex;justify-content:space-between;padding:10px 12px;background:#17365d;color:#fff}.body{padding:10px;overflow:auto;max-height:calc(86vh - 44px)}
        .notice{background:#fff2cc;color:#7f6000;border:1px solid #e6cf7a;border-radius:6px;padding:7px}.row{display:flex;gap:7px;align-items:center;margin:8px 0;flex-wrap:wrap}
        button,select{border:0;border-radius:5px;padding:7px 9px;font:inherit}button{cursor:pointer;background:#e7eef6;color:#17365d}button.primary{background:#2f75b5;color:#fff}button.danger{background:#fde8e8;color:#991b1b}button:disabled{opacity:.42;cursor:not-allowed}
        input[type=file]{width:100%;font-size:12px}.status{padding:7px;border-radius:6px;background:#eef5fb;white-space:pre-wrap}.status.error{background:#fde8e8;color:#991b1b}.status.warning{background:#fff2cc;color:#7f6000}
        .stats{display:grid;grid-template-columns:repeat(5,1fr);gap:5px;margin:8px 0}.stat{background:#f4f7fa;border-radius:5px;padding:5px;text-align:center}.stat b{display:block;font-size:15px}
        table{width:100%;border-collapse:collapse;font-size:11px;table-layout:fixed}th{background:#2f75b5;color:#fff;text-align:left;padding:5px}td{padding:5px;border-bottom:1px solid #dbe5ee;word-break:break-all;vertical-align:top}.actions button{padding:3px 5px;margin:1px;font-size:10px}
        tr.ok td{background:#e8f5e9}tr.bad td{background:#fde8e8}tr.manual td{background:#fff7df}.errors{color:#991b1b;white-space:pre-wrap;max-height:110px;overflow:auto}
      </style>
      <section class="panel"><div class="head"><strong>批量串行自动填写保存</strong><small>v${SCRIPT_VERSION}</small></div><div class="body">
        <div class="notice">严格串行：当前订单明确保存成功后保留页签；等待网站稳定并出现唯一创建入口后，才会创建下一订单。</div>
        <div class="row"><input id="file" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"></div>
        <label class="row"><input id="authorize" type="checkbox"> 我确认本批订单已经人工核对，并授权脚本真实保存</label>
        <div class="row"><button id="start" class="primary">开始</button><button id="pause">暂停</button><button id="resume">继续</button><button id="stop" class="danger">停止</button><button id="export">导出脱敏报告</button><button id="diagnostic">导出诊断JSON</button></div>
        <div class="row"><button id="unlock">解除所列重复保护</button><button id="discard" class="danger">放弃中断批次</button><button id="newBatch">新批次</button><select id="filter"><option value="all">全部状态</option><option value="manual">仅人工项</option><option value="failed">仅异常</option><option value="saved">仅已保存</option></select></div>
        <div id="status" class="status">请选择 Excel。</div><div id="stats" class="stats"></div>
        <table><thead><tr><th style="width:19%">记录</th><th style="width:16%">状态</th><th style="width:24%">说明</th><th style="width:41%">操作</th></tr></thead><tbody id="rows"></tbody></table>
        <details><summary>导入和结构问题</summary><div id="errors" class="errors">无</div></details>
      </div></section>`;
  }

  function detectConflictingScript() {
    return Boolean(window.__CMMultiFillTest || window.__CMSerialSaveTest || window.__CMSingleSaveTest || window.__CMInternalParallelProbe || window.__CMBatchParallel);
  }

  async function handleFileSelection(event) {
    const file = event.target.files?.[0]; if (!file || state.batchStarted) return;
    state.fileName = file.name; state.parsedImport = null; state.importErrors = []; state.duplicateKeys = [];
    setStatus("正在解析并预检 Excel…");
    try {
      const parsed = await parseXlsxArrayBuffer(await file.arrayBuffer(), "导入数据");
      state.parsedImport = parsed;
      applyValidation(validateOrders(parsed));
    } catch (error) { state.importErrors = [errorMessage(error)]; setStatus(`Excel 解析失败：${errorMessage(error)}`, "error"); }
    renderSummary();
  }

  function applyValidation(validation) {
    state.tasks = validation.orders.map((order, index) => createTask(order, index));
    state.batchId = validation.orders[0]?.batch_id || "";
    state.importErrors = validation.errors;
    state.duplicateKeys = validation.duplicateKeys;
    if (validation.errors.length) setStatus(`导入被阻断：发现 ${validation.errors.length} 个问题。`, "error");
    else setStatus(`预检通过：${state.tasks.length} 条订单，将严格逐条创建、填写并保存。`, "warning");
  }

  function validateOrders(parsed, options = {}) {
    const errors = [...(parsed.errors || [])]; const orders = []; const seen = new Set(); const batchIds = new Set();
    const ledger = loadLedger(); const duplicateKeys = [];
    for (const row of parsed.rows || []) {
      const label = `第 ${row.__rowNumber || "?"} 行`; const order = { ...row }; delete order.__rowNumber; delete order.__formulaFields;
      if (row.__formulaFields?.length) errors.push(`${label}：不允许公式单元格（${row.__formulaFields.join("、")}）`);
      for (const key of REQUIRED_HEADERS) if (typeof order[key] === "string" && /^(null|undefined)$/i.test(order[key].trim())) errors.push(`${label} ${key}：不得用 ${order[key]} 表示空值`);
      if (String(order.schema_version || "").trim() !== "v1.0") errors.push(`${label} schema_version：必须为 v1.0`);
      const id = String(order.source_record_id || "").trim(); const batchId = String(order.batch_id || "").trim();
      if (!id) errors.push(`${label} source_record_id：不能为空`);
      if (seen.has(id)) errors.push(`${label} source_record_id：${id} 重复`); seen.add(id);
      if (!batchId) errors.push(`${label} batch_id：不能为空`); batchIds.add(batchId);
      for (const key of ["destination_text", "sender_name", "receiver_name", "goods_name"]) if (!String(order[key] ?? "").trim()) errors.push(`${label} ${key}：不能为空`);
      if (!["delivery", "pickup"].includes(String(order.delivery_type))) errors.push(`${label} delivery_type：仅支持 delivery/pickup`);
      if (String(order.payment_type) !== "pay_billing") errors.push(`${label} payment_type：正式 v1.0 仅支持 pay_billing`);
      const quantity = toNumber(order.quantity); if (!Number.isInteger(quantity) || quantity <= 0) errors.push(`${label} quantity：必须为正整数`); order.quantity = quantity;
      for (const key of ["weight", "volume"]) {
        if (isBlank(order[key])) order[key] = ""; else { const value = toNumber(order[key]); if (!Number.isFinite(value) || value < 0) errors.push(`${label} ${key}：必须为空或非负数`); order[key] = value; }
      }
      const freight = toNumber(order.freight); if (!Number.isFinite(freight) || freight < 0 || !hasAtMostTwoDecimals(freight)) errors.push(`${label} freight：必须为非负数且最多两位小数`); order.freight = freight;
      order.receiver_mobile = isBlank(order.receiver_mobile) ? "" : String(order.receiver_mobile).trim();
      order.package = isBlank(order.package) ? "" : String(order.package).trim();
      for (const key of ["schema_version", "batch_id", "source_record_id", "source_label", "destination_text", "delivery_type", "sender_name", "receiver_name", "goods_name", "payment_type"]) order[key] = String(order[key] ?? "").trim();
      const key = ledgerKey(batchId, id); if (!options.ignoreLedger && ledger.records[key]) duplicateKeys.push(key);
      orders.push(order);
    }
    if (batchIds.size > 1) errors.push("同一文件中的 batch_id 必须一致");
    if (!orders.length) errors.push("导入数据中没有订单记录");
    if (orders.length > MAX_ORDERS) errors.push(`每份 Excel 最多允许 ${MAX_ORDERS} 条订单，当前为 ${orders.length} 条`);
    if (duplicateKeys.length) errors.push(`本地防重复台账发现 ${duplicateKeys.length} 条已保存记录，默认禁止再次运行`);
    return { orders, errors: [...new Set(errors)], duplicateKeys };
  }

  function createTask(order, index = state.tasks.length) {
    return {
      id: `${order.batch_id}::${order.source_record_id}`, index, order, state: "pending", fields: [], error: "", errorCode: "",
      startedAt: "", completedAt: "", tab: null, root: null, ownedTab: false, tabOrigin: "",
      orderNumber: "", orderNumberSource: "", save: { channel: "", http_status: "", errno: "", message: "", identity_fingerprint: "" },
      saveSettledAt: "", cleanup: "", attempts: 0, creationAttempted: false,
    };
  }

  async function startBatch() {
    if (state.running || state.batchStarted || state.importErrors.length || !state.tasks.length) return;
    if (!state.authorization) { setStatus("请先确认已核对订单并授权真实保存。", "error"); return; }
    if (detectConflictingScript()) { setStatus("检测到其他测试脚本，请只启用正式版并刷新。", "error"); return; }
    state.batchStarted = true; state.baselineTabs = new Set(listTabElements()); state.pauseRequested = false; state.stopRequested = false;
    persistCheckpoint(); await runScheduler();
  }

  async function runScheduler() {
    if (state.running || state.globalHalt) return;
    state.running = true; renderSummary();
    try {
      while (true) {
        if (state.pauseRequested || state.stopRequested || state.globalHalt) break;
        const task = nextPendingTask(state.tasks);
        if (!task) break;
        await processTask(task);
        persistCheckpoint(); renderSummary();
        if (!TERMINAL_STATES.has(task.state)) {
          state.pauseRequested = true;
          break;
        }
      }
    } catch (error) { state.globalHalt = errorMessage(error); setStatus(`批次已停止：${state.globalHalt}`, "error"); }
    finally { state.running = false; persistCheckpoint(); updateBatchOutcome(); renderSummary(); }
  }

  async function processTask(task) {
    try {
      await withOperationLock(() => createAndBindTask(task));
      await withOperationLock(() => switchToTask(task, 250));
      captureAndValidateOrderNumber(task);
      await fillDirectTask(task);
      if (task.state !== "custom_pending") return pauseForTask(task);
      if (state.pauseRequested || state.stopRequested) return transitionTask(task, "manual_pending", "批次在保存前被暂停或停止");
      await withOperationLock(async () => {
        const rebound = await switchToTask(task);
        if (rebound) await reconcileDirectFieldsAfterRebind(task);
        await fillCustomTask(task);
      });
      if (task.state !== "verifying") return pauseForTask(task);
      if (state.pauseRequested || state.stopRequested) return transitionTask(task, "manual_pending", "批次在保存前被暂停或停止");
      await withOperationLock(async () => {
        await switchToTask(task);
        task.fields = verifyCompleteOrder(task);
      });
      const fatalField = task.fields.find((field) => field.fatal);
      if (fatalField) throw fatalError(fatalField.error);
      if (!task.fields.every((field) => field.status === "verified")) throw new Error("12 个字段未全部通过回读");
      transitionTask(task, "ready_to_save");
      if (state.pauseRequested || state.stopRequested) return transitionTask(task, "manual_pending", "批次在保存前被暂停或停止");
      await saveSingleTask(task);
    } catch (error) {
      if (error.createGate) {
        task.error = errorMessage(error); task.errorCode = error.code || "create_entry_error";
        transitionTask(task, "create_entry_blocked", task.error);
      } else if (error.orderNumber) {
        task.error = errorMessage(error); task.errorCode = error.code || "order_number_error";
        transitionTask(task, "order_number_blocked", task.error);
      } else {
        failTask(task, error);
        if (error.fatal) state.globalHalt = errorMessage(error);
      }
      pauseForTask(task);
    }
  }

  function nextPendingTask(tasks) {
    return tasks.find((task) => task.state === "pending") || null;
  }

  function pauseForTask(task) {
    if (!TERMINAL_STATES.has(task.state)) state.pauseRequested = true;
  }

  async function createAndBindTask(task) {
    transitionTask(task, "creating"); task.startedAt ||= now();
    recordDiagnostic("create_phase_started", { task_index: task.index, creation_attempted: task.creationAttempted }, task.id);
    const existing = task.index === 0 || task.creationAttempted ? await resolveExistingDraft() : null;
    if (existing) {
      bindTaskToForm(task, existing.tab, existing.root, "adopted");
      recordDiagnostic("existing_draft_bound", { number_suffix: orderNumberSuffix(existing.orderNumber || "") }, task.id);
      transitionTask(task, "direct_filling");
      return;
    }
    await waitForPostSaveCooldown(task);
    transitionTask(task, "waiting_create_entry", "等待网站保存后页面稳定和创建入口就绪");
    const beforeTabs = new Set(listTabElements()); const previous = getActiveTab();
    const action = await waitForStableCreateOrderAction(15000, 500);
    recordDiagnostic("create_entry_stable", { source: locateCreateOrderAction().source || "", prior_tab_count: beforeTabs.size }, task.id);
    task.creationAttempted = true; persistCheckpoint();
    recordDiagnostic("create_click_dispatch", { prior_tab_count: beforeTabs.size }, task.id);
    dispatchUserLikeClick(action);
    try {
      await waitFor(() => {
        const active = getActiveTab(); const visibleSender = visiblePathElements("cor_name");
        return visibleSender.length === 1 && ((active && !beforeTabs.has(active)) || (previous && active && active !== previous) || listTabElements().some((tab) => !beforeTabs.has(tab)));
      }, 15000, "点击创建运单后未观察到唯一新页签和表单");
    } catch (error) {
      throw createGateError("create_result_unobserved", `${errorMessage(error)}；为避免重复创建，脚本不会自动再次点击`);
    }
    recordDiagnostic("create_result_observed", { current_tab_count: listTabElements().length }, task.id);
    await sleep(500);
    const tab = getActiveTab() || listTabElements().find((item) => !beforeTabs.has(item));
    const sender = findUniqueVisiblePath("cor_name"); const root = inferFormRoot(sender);
    if (!tab || !root) throw createGateError("create_bind_failed", "创建后无法唯一绑定新内部页签及其表单根容器");
    if (state.baselineTabs.has(tab)) throw createGateError("create_bound_baseline", "新订单错误绑定到运行前已有页签");
    bindTaskToForm(task, tab, root, "created");
    recordDiagnostic("new_form_bound", {
      root_connected: root.isConnected, tab_connected: tab.isConnected, tab_active: getActiveTab() === tab,
      form_active: isFormRootActive(root), active_data_path: document.activeElement?.getAttribute?.("data-path") || "",
      dropdown_menu_visible: dropdownMenuState().visible,
    }, task.id);
    transitionTask(task, "direct_filling");
  }

  async function waitForPostSaveCooldown(task) {
    const previous = [...state.tasks].filter((item) => item.index < task.index && item.state === "saved").sort((a, b) => b.index - a.index)[0];
    if (!previous?.saveSettledAt) return;
    const elapsed = Date.now() - Date.parse(previous.saveSettledAt); const remaining = Math.max(0, POST_SAVE_COOLDOWN_MS - (Number.isFinite(elapsed) ? elapsed : 0));
    recordDiagnostic("post_save_cooldown", { previous_record_id: previous.order.source_record_id, configured_ms: POST_SAVE_COOLDOWN_MS, elapsed_ms: Math.max(0, elapsed || 0), remaining_ms: remaining }, task.id);
    if (remaining) await sleep(remaining);
  }

  function bindTaskToForm(task, tab, root, origin) {
    task.tab = tab; task.root = root; task.ownedTab = true; task.tabOrigin = origin;
    tab.setAttribute("data-cm-production-task", task.id); root.setAttribute("data-cm-production-root", task.id);
  }

  async function resolveExistingDraft() {
    const records = await inspectCreationTabs();
    if (!records.length) return null;
    const uninspectable = records.filter((item) => !item.root || !item.orderNumber);
    if (uninspectable.length) throw orderNumberError("existing_draft_uninspectable", `检测到 ${records.length} 个开单页签，但有 ${uninspectable.length} 个无法确认表单或运单号；未创建新订单`);
    const nonEmpty = records.filter((item) => !item.empty);
    if (nonEmpty.length) throw orderNumberError("existing_draft_not_empty", `检测到 ${nonEmpty.length} 个已有开单页签不是空白草稿；为保护现有内容，未关闭也未创建新订单`);

    const groups = new Map();
    for (const record of records) {
      if (!groups.has(record.orderNumber)) groups.set(record.orderNumber, []);
      groups.get(record.orderNumber).push(record);
    }
    if (groups.size > 1) {
      const suffixes = [...groups.keys()].map(orderNumberSuffix).join("、");
      throw orderNumberError("multiple_draft_numbers", `检测到多个空白开单页签，但运单号不同（尾号 ${suffixes}）；未自动关闭，请人工核对`);
    }

    const group = [...groups.values()][0];
    const keep = group.find((item) => item.wasActive) || group[0];
    for (const duplicate of group) {
      if (duplicate === keep) continue;
      await closeVerifiedEmptyDraft(duplicate);
    }
    if (group.length > 1) setStatus(`已自动关闭 ${group.length - 1} 个同运单号空白页签，并保留一个用于当前订单。`, "warning");
    await activateInspectedDraft(keep);
    if (!keep.tab?.isConnected || !keep.root?.isConnected || !isDraftEmpty(keep.root)) {
      throw orderNumberError("kept_draft_changed", "保留的空白开单页签状态发生变化，已停止");
    }
    return keep;
  }

  async function inspectCreationTabs() {
    const original = getActiveTab();
    const tabs = listTabElements().filter((tab) => tab.getAttribute("data-cm-production-completed") !== "1" && normalizeText(tab.querySelector("span")?.textContent || tab.textContent).startsWith("创建运单"));
    if (!tabs.length) {
      if (original?.getAttribute("data-cm-production-completed") === "1") return [];
      const sender = findUniqueVisiblePath("cor_name");
      const root = inferFormRoot(sender);
      if (!root) return [];
      const tab = getActiveTab();
      if (!tab) throw orderNumberError("existing_form_without_tab", "检测到开单表单，但无法绑定对应内部页签");
      return [{ tab, root, wasActive: true, empty: isDraftEmpty(root), orderNumber: safelyReadOrderNumber(root) }];
    }
    const records = [];
    for (const tab of tabs) {
      const wasActive = tab === original;
      dispatchUserLikeClick(tab);
      await waitFor(() => getActiveTab() === tab && visiblePathElements("cor_name").length === 1, 5000, "检查已有开单页签时切换超时");
      await sleep(220);
      const root = inferFormRoot(findUniqueVisiblePath("cor_name"));
      records.push({ tab, root, wasActive, empty: Boolean(root && isDraftEmpty(root)), orderNumber: root ? safelyReadOrderNumber(root) : "" });
    }
    return records;
  }

  async function activateInspectedDraft(record) {
    if (!record.tab?.isConnected || !record.root?.isConnected) throw orderNumberError("draft_removed", "待使用的空白开单页签已被移除");
    dispatchUserLikeClick(record.tab);
    await waitFor(() => getActiveTab() === record.tab && isFormRootActive(record.root), 5000, "激活保留的空白开单页签超时");
    await sleep(220);
  }

  async function closeVerifiedEmptyDraft(record) {
    await activateInspectedDraft(record);
    const currentNumber = safelyReadOrderNumber(record.root);
    if (!record.empty || !isDraftEmpty(record.root) || !currentNumber || currentNumber !== record.orderNumber) {
      throw orderNumberError("duplicate_draft_changed", "重复页签不再满足同号空白条件，未自动关闭");
    }
    const close = findTabCloseControl(record.tab);
    if (!close) throw orderNumberError("duplicate_close_missing", "同号空白页签未找到关闭按钮，未继续执行");
    dispatchUserLikeClick(close);
    await waitFor(() => !record.tab.isConnected || !record.root.isConnected, 4000, "关闭同号空白页签后未观察到 DOM 移除");
  }

  function isDraftEmpty(root) {
    if (!root?.isConnected) return false;
    const corePaths = ["arr", "cor_name", "cee_name", "cee_mobile", "name_1", "pkg_1"];
    if (corePaths.some((path) => normalizeText(readControl(root.querySelector(`[data-path="${path}"]`))))) return false;
    for (const path of ["weight_1", "volume_1", "co_freight_f"]) {
      const value = readControl(root.querySelector(`[data-path="${path}"]`));
      if (!isBlank(value) && Number(value) !== 0) return false;
    }
    const quantity = readControl(root.querySelector('[data-path="num_1"]'));
    if (!isBlank(quantity) && ![0, 1].includes(Number(quantity))) return false;
    return true;
  }

  function safelyReadOrderNumber(root) {
    try { return readOrderNumber(root).value; } catch { return ""; }
  }

  function captureAndValidateOrderNumber(task) {
    const detected = readOrderNumber(task.root);
    task.orderNumber = detected.value;
    task.orderNumberSource = detected.source;
    recordDiagnostic("order_number_captured", { number_suffix: orderNumberSuffix(task.orderNumber), number_fingerprint: stableHash(task.orderNumber), source: detected.source }, task.id);
    const previous = [...state.tasks]
      .filter((item) => item.index < task.index && item.state === "saved" && item.orderNumber)
      .sort((a, b) => b.index - a.index)[0];
    if (previous && !isOrderNumberAdvanced(previous.orderNumber, task.orderNumber)) {
      throw orderNumberError(
        "order_number_not_advanced",
        `新建订单的运单号未递增（上一单尾号 ${orderNumberSuffix(previous.orderNumber)}，当前尾号 ${orderNumberSuffix(task.orderNumber)}），已在填写前停止`,
      );
    }
    persistCheckpoint();
  }

  function readOrderNumber(root) {
    if (!root?.isConnected) throw orderNumberError("order_number_root_missing", "当前表单根容器不可用，无法读取运单号");
    const directSelectors = [
      '[data-path="order_num"]', '[data-path="co_num"]', '[name="order_num"]', '[name="co_num"]',
      '[data-field="order_num"]', '[data-key="order_num"]', '[data-name="order_num"]',
    ];
    const candidates = [];
    for (const selector of directSelectors) {
      for (const element of root.querySelectorAll(selector)) {
        const value = normalizeOrderNumber(readElementValue(element));
        if (value) candidates.push({ value, source: selector });
      }
    }
    if (!candidates.length && isFormRootActive(root)) {
      for (const selector of directSelectors) {
        for (const element of document.querySelectorAll(selector)) {
          if (element.closest(`#${PANEL_ID}`) || !isVisible(element)) continue;
          const value = normalizeOrderNumber(readElementValue(element));
          if (value) candidates.push({ value, source: `active:${selector}` });
        }
      }
    }
    if (!candidates.length) {
      const labelScope = isFormRootActive(root) ? document : root;
      const labels = [...labelScope.querySelectorAll("label,.fn-label,.el-form-item__label,[class*='label']")]
        .filter((element) => !element.closest(`#${PANEL_ID}`) && (labelScope === root || isVisible(element)))
        .filter((element) => /^(运单号|货运单号|托运单号)$/.test(normalizeText(element.textContent)));
      for (const label of labels) {
        const scope = label.closest(".el-form-item,.fn-card__form__card-item,[class*='form-item'],td") || label.parentElement;
        if (!scope) continue;
        const control = scope.querySelector("input,textarea,[data-path],[data-field]");
        const controlValue = normalizeOrderNumber(readElementValue(control));
        if (controlValue) candidates.push({ value: controlValue, source: "label-control" });
        else {
          const scopeValue = normalizeOrderNumber(normalizeText(scope.textContent).replace(normalizeText(label.textContent), ""));
          if (scopeValue) candidates.push({ value: scopeValue, source: "label-text" });
        }
      }
    }
    const unique = [...new Map(candidates.map((item) => [item.value, item])).values()];
    if (unique.length !== 1) {
      const detail = unique.length ? `识别到 ${unique.length} 个不同候选` : "没有识别到候选";
      throw orderNumberError("order_number_not_unique", `无法唯一读取当前页面运单号：${detail}`);
    }
    return unique[0];
  }

  function readElementValue(element) {
    if (!element) return "";
    return String(element.value ?? element.getAttribute?.("value") ?? element.title ?? element.textContent ?? "");
  }

  function normalizeOrderNumber(value) {
    const text = String(value ?? "").replace(/\s+/g, "").replace(/^(运单号|货运单号|托运单号)[：:]?/, "");
    if (!text) return "";
    if (/^[A-Za-z0-9][A-Za-z0-9_-]{2,}$/.test(text)) return text;
    const match = text.match(/[A-Za-z0-9][A-Za-z0-9_-]{2,}/);
    return match?.[0] || "";
  }

  function isOrderNumberAdvanced(previous, current) {
    const before = normalizeOrderNumber(previous);
    const after = normalizeOrderNumber(current);
    if (!before || !after || before === after) return false;
    if (/^\d+$/.test(before) && /^\d+$/.test(after) && before.length === after.length) {
      try { return BigInt(after) > BigInt(before); } catch { return false; }
    }
    return true;
  }

  function orderNumberSuffix(value) {
    const normalized = normalizeOrderNumber(value);
    return normalized ? normalized.slice(-3).padStart(3, "*") : "";
  }

  function orderNumberError(code, message) {
    const error = new Error(message);
    error.orderNumber = true;
    error.code = code;
    return error;
  }

  function createGateError(code, message) {
    const error = new Error(message);
    error.createGate = true;
    error.code = code;
    return error;
  }

  async function fillDirectTask(task) {
    if (task.state !== "direct_filling") return;
    const fields = [];
    for (const mapping of DIRECT_FIELDS) {
      const result = fieldResult(mapping); fields.push(result);
      try {
        const control = uniqueControlInTask(task, mapping.dataPath); const expected = task.order[mapping.key];
        await writeControl(control, isBlank(expected) ? "" : expected, { focus: false, blur: false });
        if (mapping.key === "receiver_name") await sleep(300);
        const actual = readControl(uniqueControlInTask(task, mapping.dataPath));
        const matches = mapping.kind === "number" && !isBlank(expected) ? Number(actual) === Number(expected) : normalizeText(actual) === normalizeText(isBlank(expected) ? "" : expected);
        if (!matches) throw new Error("写入后回读不一致"); result.status = "verified";
      } catch (error) { result.status = "failed"; result.error = errorMessage(error); if (error.fatal) result.fatal = true; }
    }
    task.fields = mergeFields(task.fields, fields);
    const fatal = fields.find((item) => item.fatal);
    if (fatal) { failTask(task, fatalError(fatal.error)); state.globalHalt = fatal.error; return; }
    if (fields.some((item) => item.status !== "verified")) { failTask(task, new Error("普通字段未全部写入并通过回读")); return; }
    transitionTask(task, "custom_pending");
  }

  async function fillCustomTask(task) {
    if (task.state !== "custom_pending") return;
    transitionTask(task, "custom_filling"); const fields = [];
    for (const mapping of CUSTOM_FIELDS) {
      const result = fieldResult(mapping); fields.push(result);
      try {
        const control = uniqueControlInTask(task, mapping.dataPath); const expected = mapping.display ? mapping.display[task.order[mapping.key]] : task.order[mapping.key];
        if (mapping.kind === "autocomplete") await fillScopedAutocomplete(control, String(expected), task);
        else await chooseScopedValue(control, String(expected), task);
        if (control.getAttribute("data-is-select") !== "1") throw new Error("网站未确认下拉选择");
        if (!normalizeText(readControl(control) || control.title).includes(normalizeText(expected))) throw new Error("选择后回读不一致");
        result.status = "verified";
      } catch (error) { result.status = "failed"; result.error = errorMessage(error); result.fatal = Boolean(error.fatal); break; }
    }
    task.fields = mergeFields(task.fields, fields);
    const failure = fields.find((item) => item.status !== "verified");
    if (failure) throw failure.fatal ? fatalError(failure.error) : new Error(`${failure.key}：${failure.error}`);
    transitionTask(task, "verifying");
  }

  async function fillScopedAutocomplete(control, value, task) {
    let attemptStartedAt = performance.now();
    let before = await resetAndEnterAutocompleteQuery(control, value, task);
    let selected = await chooseNewExactOption(value, before, 2400, control, task, { recordTimeout: false, attemptStartedAt });
    if (!selected) {
      recordDiagnostic("candidate_retry", {
        field: control.getAttribute("data-path") || "", reason: "response_not_rendered_or_not_visible",
        ...dropdownMenuEvidence(value), ...suggestionResourceEvidenceSince(attemptStartedAt), value_hash: stableHash(value),
      }, task?.id || "");
      attemptStartedAt = performance.now();
      before = await resetAndEnterAutocompleteQuery(control, value, task, { incremental: true });
      selected = await chooseNewExactOption(value, before, 4500, control, task, { attemptStartedAt });
    }
    if (!selected) throw new Error(`未找到唯一候选：${value}`);
    await waitFor(() => control.getAttribute("data-is-select") === "1" && normalizeText(readControl(control) || control.title) === normalizeText(value), 3500, `候选点击后网站未确认：${value}`);
  }

  async function resetAndEnterAutocompleteQuery(control, value, task, options = {}) {
    if (!control?.isConnected) throw fatalError("到站控件已从 DOM 移除");
    await activelyOpenDropdown(control, task, options.incremental ? "datalist_retry_start" : "datalist_initial_start");
    control.removeAttribute("data-is-select");
    control.removeAttribute("title");
    setNativeControlValue(control, "");
    dispatchAutocompleteInput(control, "");
    await sleep(260);
    const before = new Set([...document.body.querySelectorAll("*")].filter(isVisible));
    if (options.incremental) {
      let entered = "";
      for (const character of String(value)) {
        entered += character;
        try { control.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, composed: true, key: character })); }
        catch { /* input 事件仍会携带当前完整值 */ }
        setNativeControlValue(control, entered);
        dispatchAutocompleteInput(control, entered, character);
        await sleep(150);
      }
    } else {
      setNativeControlValue(control, value);
      dispatchAutocompleteInput(control, value);
      await sleep(180);
    }
    await activelyOpenDropdown(control, task, options.incremental ? "datalist_retry_query_ready" : "datalist_query_ready");
    return before;
  }

  function dispatchAutocompleteInput(control, value, inputData = null) {
    try {
      control.dispatchEvent(new InputEvent("input", { bubbles: true, composed: true, inputType: value ? "insertText" : "deleteContentBackward", data: value ? (inputData ?? String(value).slice(-1)) : null }));
    } catch {
      control.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
    }
    try {
      control.dispatchEvent(new KeyboardEvent("keyup", { bubbles: true, composed: true, key: value ? String(value).slice(-1) : "Backspace" }));
    } catch { /* input 事件已经足够触发大多数站点控件 */ }
  }

  async function chooseScopedValue(control, value, task) {
    let before = new Set([...document.body.querySelectorAll("*")].filter(isVisible));
    await activelyOpenDropdown(control, task, "select_initial", { force: true, expectedValue: value });
    let selected = await chooseNewExactOption(value, before, 1800, control, task, { recordTimeout: false });
    if (!selected) {
      recordDiagnostic("choice_retry", {
        field: control.getAttribute("data-path") || "", reason: "dropdown_not_rendered_or_not_visible",
        ...dropdownMenuEvidence(value), value_hash: stableHash(value),
      }, task?.id || "");
      control.blur(); await sleep(180);
      before = new Set([...document.body.querySelectorAll("*")].filter(isVisible));
      await activelyOpenDropdown(control, task, "select_retry", { force: true, expectedValue: value });
      selected = await chooseNewExactOption(value, before, 3500, control, task);
    }
    if (!selected) throw new Error(`未找到唯一选项：${value}`);
    await waitFor(() => control.getAttribute("data-is-select") === "1" && normalizeText(readControl(control) || control.title).includes(normalizeText(value)), 3000, `选项点击后网站未确认：${value}`);
  }

  async function chooseNewExactOption(value, visibleBefore, timeoutMs, control, task, options = {}) {
    const end = Date.now() + timeoutMs;
    let lastCounts = { exact: 0, new_targets: 0, reusable_targets: 0, dropdown_menu_total: 0, dropdown_menu_visible: 0, dropdown_menu_exact: 0 };
    while (Date.now() < end) {
      const allDropdownMenus = [...document.querySelectorAll(".fn-dropdown__menu")].filter((element) => !element.closest(`#${PANEL_ID}`));
      const visibleDropdownMenus = allDropdownMenus.filter(isVisible);
      const exact = [...document.body.querySelectorAll("*")].filter((element) => !element.closest(`#${PANEL_ID}`) && !control.contains(element) && isVisible(element) && normalizeText(element.textContent) === normalizeText(value));
      const dropdownExact = exact.filter((element) => visibleDropdownMenus.some((menu) => menu === element || menu.contains(element)));
      if (control.getAttribute("data-is-select") === "1" && normalizeText(readControl(control) || control.title) === normalizeText(value)) {
        recordDiagnostic("candidate_auto_confirmed", {
          field: control.getAttribute("data-path") || "", dropdown_menu_total: allDropdownMenus.length,
          dropdown_menu_visible: visibleDropdownMenus.length, dropdown_menu_exact: dropdownExact.length, value_hash: stableHash(value),
        }, task?.id || "");
        return true;
      }
      const scopedExact = dropdownExact.length ? dropdownExact : exact;
      const deepest = scopedExact.filter((element) => ![...element.children].some((child) => isVisible(child) && normalizeText(child.textContent) === normalizeText(value)));
      const pairs = deepest.map((element) => ({ element, target: element.closest("[role='option'],li,button,a,[data-value],[data-id],[class*='option'],[class*='item']") || element }));
      const newTargets = [...new Set(pairs.filter(({ element, target }) => !visibleBefore.has(element) || !visibleBefore.has(target)).map(({ target }) => target))];
      const reusableTargets = [...new Set(pairs.map(({ target }) => target).filter((target) => isTopmostCandidateNearControl(target, control)))];
      lastCounts = {
        exact: exact.length, new_targets: newTargets.length, reusable_targets: reusableTargets.length,
        dropdown_menu_total: allDropdownMenus.length, dropdown_menu_visible: visibleDropdownMenus.length, dropdown_menu_exact: dropdownExact.length,
      };
      if (newTargets.length === 1) {
        recordDiagnostic("candidate_selected", { field: control.getAttribute("data-path") || "", mode: "new_dom", ...lastCounts, value_hash: stableHash(value) }, task?.id || "");
        dispatchUserLikeClick(newTargets[0]); return true;
      }
      if (newTargets.length > 1) {
        recordDiagnostic("candidate_ambiguous", { field: control.getAttribute("data-path") || "", mode: "new_dom", ...lastCounts, value_hash: stableHash(value) }, task?.id || "");
        throw new Error(`候选“${value}”出现 ${newTargets.length} 个新选项，已停止避免误选`);
      }
      if (reusableTargets.length === 1) {
        recordDiagnostic("candidate_selected", { field: control.getAttribute("data-path") || "", mode: "reused_dom", ...lastCounts, value_hash: stableHash(value) }, task?.id || "");
        dispatchUserLikeClick(reusableTargets[0]); return true;
      }
      if (reusableTargets.length > 1) {
        recordDiagnostic("candidate_ambiguous", { field: control.getAttribute("data-path") || "", mode: "reused_dom", ...lastCounts, value_hash: stableHash(value) }, task?.id || "");
        throw new Error(`候选“${value}”存在 ${reusableTargets.length} 个可点击复用选项，已停止避免误选`);
      }
      await sleep(100);
    }
    if (options.recordTimeout !== false) recordDiagnostic("candidate_timeout", {
      field: control.getAttribute("data-path") || "", timeout_ms: timeoutMs, ...lastCounts,
      ...suggestionResourceEvidenceSince(options.attemptStartedAt), value_hash: stableHash(value),
    }, task?.id || "");
    return false;
  }

  function suggestionResourceEvidenceSince(startedAt) {
    if (!Number.isFinite(startedAt) || typeof performance === "undefined" || typeof performance.getEntriesByType !== "function") return { suggestion_request_count: null };
    const resources = performance.getEntriesByType("resource").filter((entry) => {
      try { return entry.startTime >= startedAt - 50 && /\/api\/Basic\/Nsug\/sug\/?$/i.test(new URL(entry.name, location.href).pathname); }
      catch { return false; }
    });
    return {
      suggestion_request_count: resources.length,
      suggestion_completed_count: resources.filter((entry) => Number(entry.duration) > 0).length,
      suggestion_max_duration_ms: resources.length ? Math.round(Math.max(...resources.map((entry) => Number(entry.duration) || 0))) : 0,
      suggestion_statuses: [...new Set(resources.map((entry) => Number(entry.responseStatus) || 0).filter(Boolean))].slice(0, 4),
    };
  }

  function dropdownMenuEvidence(value) {
    const menus = [...document.querySelectorAll(".fn-dropdown__menu")].filter((element) => !element.closest(`#${PANEL_ID}`));
    const visibleMenus = menus.filter(isVisible);
    const exact = visibleMenus.flatMap((menu) => [menu, ...menu.querySelectorAll("*")]).filter((element) => isVisible(element) && normalizeText(element.textContent) === normalizeText(value));
    return { dropdown_menu_total: menus.length, dropdown_menu_visible: visibleMenus.length, dropdown_menu_exact: exact.length };
  }

  async function activelyOpenDropdown(control, task, phase, options = {}) {
    const before = dropdownMenuState();
    if (!options.force && before.visible > 0 && document.activeElement === control) {
      recordDiagnostic("dropdown_open_attempt", { field: control.getAttribute("data-path") || "", field_type: control.getAttribute("data-field-type") || "", phase, route: "already_open_for_active_control", before_total: before.total, before_visible: before.visible, mouse_peak_visible: before.visible, after_mouse_visible: before.visible, after_keyboard_visible: before.visible }, task?.id || "");
      return before;
    }
    const mouseResult = await openDropdownWithStagedMouse(control, { expectedValue: options.expectedValue });
    const afterMouse = mouseResult.state;
    let afterKeyboard = afterMouse;
    let keyboardFallback = false;
    if (!afterMouse.visible) {
      keyboardFallback = true;
      const keyOptions = { bubbles: true, cancelable: true, composed: true, key: "ArrowDown", code: "ArrowDown" };
      try { control.dispatchEvent(new KeyboardEvent("keydown", keyOptions)); control.dispatchEvent(new KeyboardEvent("keyup", keyOptions)); }
      catch { /* 完整鼠标序列仍已发送 */ }
      await sleep(140);
      afterKeyboard = dropdownMenuState();
    }
    recordDiagnostic("dropdown_open_attempt", {
      field: control.getAttribute("data-path") || "", field_type: control.getAttribute("data-field-type") || "", phase,
      route: keyboardFallback ? `${mouseResult.stage}_then_arrow_down` : mouseResult.stage, before_total: before.total, before_visible: before.visible,
      mouse_peak_visible: mouseResult.peakVisible, after_mouse_visible: afterMouse.visible, after_keyboard_visible: afterKeyboard.visible,
    }, task?.id || "");
    return afterKeyboard;
  }

  async function openDropdownWithStagedMouse(control, options = {}) {
    const menuReady = () => options.expectedValue
      ? dropdownMenuEvidence(options.expectedValue).dropdown_menu_exact > 0
      : dropdownMenuState().visible > 0;
    control.scrollIntoView({ block: "nearest", inline: "nearest" });
    control.focus();
    await sleep(35);
    let stateNow = dropdownMenuState();
    let peakVisible = stateNow.visible;
    if (menuReady()) return { stage: "focus", state: stateNow, peakVisible };

    const mouseOptions = { bubbles: true, cancelable: true, composed: true, view: window, button: 0, buttons: 1 };
    if (typeof PointerEvent === "function") control.dispatchEvent(new PointerEvent("pointerover", { ...mouseOptions, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    control.dispatchEvent(new MouseEvent("mouseover", mouseOptions));
    await sleep(20);
    if (typeof PointerEvent === "function") control.dispatchEvent(new PointerEvent("pointerdown", { ...mouseOptions, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    await sleep(30);
    stateNow = dropdownMenuState(); peakVisible = Math.max(peakVisible, stateNow.visible);
    let openedStage = menuReady() ? "pointerdown" : "";
    if (!openedStage) {
      control.dispatchEvent(new MouseEvent("mousedown", mouseOptions));
      await sleep(70);
      stateNow = dropdownMenuState(); peakVisible = Math.max(peakVisible, stateNow.visible);
      if (menuReady()) openedStage = "mousedown";
    }

    control.dispatchEvent(new MouseEvent("mouseup", { ...mouseOptions, buttons: 0 }));
    if (typeof PointerEvent === "function") control.dispatchEvent(new PointerEvent("pointerup", { ...mouseOptions, buttons: 0, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    await sleep(35);
    stateNow = dropdownMenuState(); peakVisible = Math.max(peakVisible, stateNow.visible);
    if (openedStage && menuReady()) return { stage: openedStage, state: stateNow, peakVisible };

    control.dispatchEvent(new MouseEvent("click", { ...mouseOptions, buttons: 0 }));
    await sleep(90);
    stateNow = dropdownMenuState(); peakVisible = Math.max(peakVisible, stateNow.visible);
    return { stage: menuReady() ? "click" : (openedStage ? `${openedStage}_closed_after_release_or_click` : "mouse_no_matching_menu"), state: stateNow, peakVisible };
  }

  function dropdownMenuState() {
    const menus = [...document.querySelectorAll(".fn-dropdown__menu")].filter((element) => !element.closest(`#${PANEL_ID}`));
    return { total: menus.length, visible: menus.filter(isVisible).length };
  }

  function isTopmostCandidateNearControl(target, control) {
    if (!isVisible(target) || !isVisible(control)) return false;
    const targetRect = target.getBoundingClientRect(); const controlRect = control.getBoundingClientRect();
    if (!targetRect.width || !targetRect.height || !controlRect.width || !controlRect.height) return false;
    const horizontalGap = Math.max(0, controlRect.left - targetRect.right, targetRect.left - controlRect.right);
    const verticalGap = Math.max(0, controlRect.top - targetRect.bottom, targetRect.top - controlRect.bottom);
    if (horizontalGap > 500 || verticalGap > 600) return false;
    if (typeof document.elementFromPoint !== "function") return true;
    const x = Math.min(innerWidth - 1, Math.max(0, targetRect.left + Math.min(targetRect.width / 2, 12)));
    const y = Math.min(innerHeight - 1, Math.max(0, targetRect.top + Math.min(targetRect.height / 2, 12)));
    const top = document.elementFromPoint(x, y);
    return Boolean(top && (target === top || target.contains(top) || top.contains(target)));
  }

  function verifyCompleteOrder(task) {
    return FIELD_MAPPINGS.map((mapping) => {
      const result = fieldResult(mapping); const expected = mapping.display ? mapping.display[task.order[mapping.key]] : task.order[mapping.key];
      try {
        const control = uniqueControlInTask(task, mapping.dataPath);
        const selected = !CUSTOM_FIELDS.includes(mapping) || control.getAttribute("data-is-select") === "1";
        const actual = readControl(control) || control.title;
        const matches = mapping.kind === "number" && !isBlank(expected) ? Number(actual) === Number(expected) : normalizeText(actual).includes(normalizeText(isBlank(expected) ? "" : expected));
        result.status = selected && matches ? "verified" : "failed";
        if (result.status === "failed") result.error = "回读或下拉确认不一致";
      } catch (error) { result.status = "failed"; result.error = errorMessage(error); result.fatal = Boolean(error.fatal); }
      return result;
    });
  }

  async function saveSingleTask(task, options = {}) {
    if (!options.manual && (state.pauseRequested || state.stopRequested)) {
      transitionTask(task, "manual_pending", "保存前被暂停或停止");
      return;
    }
    const permit = await withOperationLock(async () => {
      await switchToTask(task);
      transitionTask(task, "saving");
      task.attempts += 1;
      const created = triggerObservedSave(task, options.reason || "initial", Boolean(options.continueOnly));
      await waitForPermitRequest(created, 5000);
      return created;
    });
    applySaveResult(task, await permit.resultPromise);
  }

  function triggerObservedSave(task, reason, continueOnly = false) {
    installSafetyGuards();
    const action = findUniqueSaveAction(continueOnly);
    if (!action) throw new Error(continueOnly ? "未找到唯一可见的“继续保存”按钮" : "未找到唯一可见保存按钮");
    const permit = armSavePermit(task, reason); state.clickPermitId = permit.id;
    recordDiagnostic("save_click_dispatch", { permit_id: permit.id, reason, continue_only: continueOnly, tag: action.tagName, text: normalizeText(action.textContent) }, task.id);
    try { action.click(); } catch (error) {
      state.clickPermitId = "";
      settleSavePermit(permit, { state: "save_failed", error: `触发保存失败：${errorMessage(error)}`, channel: "none", http_status: "", errno: "", message: "未发出请求", identity_keys: [], identity_fingerprint: "" });
    }
    return permit;
  }

  async function waitForPermitRequest(permit, timeoutMs) {
    const outcome = await Promise.race([permit.requestSeenPromise.then(() => "request"), permit.resultPromise.then(() => "result"), sleep(timeoutMs).then(() => "timeout")]);
    recordDiagnostic("permit_initial_wait", { permit_id: permit.id, outcome, status: permit.status, channel: permit.channel || "", timeout_ms: timeoutMs }, permit.taskId);
    if (outcome === "request") return;
    if (permit.status !== "settled") settleSavePermit(permit, ambiguousSave(`未在 ${timeoutMs}ms 内捕获到保存请求；网站可能已经保存，请导出诊断JSON并查询订单列表确认`, "unobserved"));
  }

  function applySaveResult(task, result) {
    recordDiagnostic("save_result_applied", { result_state: result.state, channel: result.channel || "", http_status: result.http_status ?? "", errno: result.errno ?? "", error: result.error || "" }, task.id);
    task.saveSettledAt = result.settled_at || now();
    task.save = { channel: result.channel || "", http_status: result.http_status ?? "", errno: result.errno ?? "", message: result.message || "", identity_fingerprint: result.identity_fingerprint || "" };
    if (result.state === "saved") {
      task.errorCode = "";
      markTaskSavedRetained(task, "保存成功；页签已保留");
      try { addLedgerRecord(task); }
      catch (error) {
        task.errorCode = "ledger_error";
        task.error = `订单已保存，但防重复台账写入失败：${errorMessage(error)}`;
        state.globalHalt = task.error;
      }
    }
    else if (result.state === "decision_required") { transitionTask(task, "decision_required", result.error); state.pauseRequested = true; }
    else if (result.state === "save_failed") { transitionTask(task, "save_failed", result.error); state.pauseRequested = true; }
    else {
      transitionTask(task, "save_ambiguous", result.error);
      state.globalHalt = result.state === "session_expired" ? result.error : `订单 ${task.order.source_record_id} 保存结果不确定，必须人工核对`;
    }
    persistCheckpoint();
  }

  async function handlePanelAction(event) {
    const button = event.target.closest?.("button[data-action]"); if (!button || button.disabled) return;
    const task = state.tasks.find((item) => item.id === button.dataset.task); if (!task) return;
    const action = button.dataset.action;
    try {
      if (action === "activate") await withOperationLock(() => switchToTask(task));
      else if (action === "continue-save") await continueDecision(task);
      else if (action === "repair") await repairAndSave(task);
      else if (action === "abandon") await abandonTask(task);
      else if (action === "confirm-saved") await confirmAmbiguousSaved(task);
      else if (action === "confirm-not-saved") await confirmNotSaved(task);
      else if (action === "confirm-closed") await confirmManuallyClosed(task);
      else if (action === "retry-close") await retryClose(task);
      else if (action === "retry-order") await retryOrderCreation(task);
      else if (action === "retry-preflight") {
        task.errorCode = ""; task.error = ""; transitionTask(task, "pending", "等待重新检查页面开单页签");
      }
      else if (action === "retry-ledger") {
        addLedgerRecord(task); task.errorCode = ""; task.error = ""; state.globalHalt = "";
      }
    } catch (error) { task.error = errorMessage(error); setStatus(`人工操作失败：${task.error}`, "error"); }
    persistCheckpoint(); updateBatchOutcome(); renderSummary(); maybeAutoResume();
  }

  async function continueDecision(task) {
    if (task.state !== "decision_required" || !task.tab?.isConnected) throw new Error("该订单已无法绑定原页签，请人工核对后处理");
    task.errorCode = "";
    await saveSingleTask(task, { manual: true, continueOnly: true, reason: "continue_after_320" });
  }

  async function repairAndSave(task) {
    if (!task.tab?.isConnected || !task.root?.isConnected) throw new Error("原页签引用已丢失；中断恢复时请先确认旧表单状态");
    task.errorCode = "";
    await withOperationLock(async () => {
      await switchToTask(task); task.fields = verifyCompleteOrder(task);
      if (!task.fields.every((field) => field.status === "verified")) throw new Error("人工修正后仍未通过全部 12 字段回读");
      transitionTask(task, "ready_to_save");
    });
    await saveSingleTask(task, { manual: true, reason: "repair_after_manual" });
  }

  async function abandonTask(task) {
    if (!window.confirm(`确认放弃订单 ${task.order.source_record_id}？该订单不会由脚本保存。`)) return;
    const closed = await withOperationLock(() => closeOwnedTab(task, true));
    if (closed) { transitionTask(task, "abandoned", "用户明确放弃并已关闭页签"); task.completedAt = now(); }
    else transitionTask(task, "abandon_pending_close", "放弃已确认，但页签关闭失败；请人工关闭后确认");
  }

  async function confirmAmbiguousSaved(task) {
    if (!window.confirm(`请确认你已在订单列表查到 ${task.order.source_record_id} 对应订单。确认后将按已保存处理，不能自动重试。`)) return;
    markTaskSavedRetained(task, "用户已在订单列表确认保存；现有页签保持原状");
    try { addLedgerRecord(task); state.globalHalt = ""; }
    catch (error) {
      task.errorCode = "ledger_error";
      task.error = `已人工确认订单保存，但防重复台账写入失败：${errorMessage(error)}`;
      state.globalHalt = task.error;
    }
  }

  async function confirmNotSaved(task) {
    if (!window.confirm(`请确认你已查询订单列表、该订单没有生成，并已人工关闭或放弃旧表单页签。确认后允许重新排队：${task.order.source_record_id}`)) return;
    task.tab = null; task.root = null; task.ownedTab = false; task.orderNumber = ""; task.orderNumberSource = ""; task.fields = []; task.errorCode = ""; task.creationAttempted = false; task.save = { channel: "", http_status: "", errno: "", message: "", identity_fingerprint: "" };
    transitionTask(task, "pending", "已人工确认未生成，允许重试"); state.globalHalt = "";
  }

  function markTaskSavedRetained(task, message) {
    task.saveSettledAt ||= now();
    task.cleanup = "retained";
    if (task.tab?.isConnected) task.tab.setAttribute("data-cm-production-completed", "1");
    if (task.root?.isConnected) task.root.setAttribute("data-cm-production-completed", "1");
    transitionTask(task, "saved", message);
    task.completedAt ||= now();
  }

  async function retryClose(task) {
    if (task.state !== "abandon_pending_close") throw new Error("当前订单不在放弃待关闭状态");
    const closed = await withOperationLock(() => closeOwnedTab(task, true));
    if (!closed) throw new Error("页签仍未关闭");
    if (task.errorCode === "close_error") task.errorCode = "";
    transitionTask(task, "abandoned", "用户放弃并已关闭页签");
  }

  async function confirmManuallyClosed(task) {
    if (task.state !== "abandon_pending_close") throw new Error("当前订单不在放弃待关闭状态");
    if (task.tab?.isConnected || task.root?.isConnected) throw new Error("仍检测到页签或表单存在，请先关闭后再确认");
    if (!window.confirm(`确认 ${task.order.source_record_id} 的页签已经由你人工关闭？`)) return;
    task.tab = null; task.root = null; task.cleanup = "manually_confirmed_closed";
    if (task.errorCode === "close_error") task.errorCode = "";
    transitionTask(task, "abandoned", "用户放弃并确认页签已关闭");
  }

  async function retryOrderCreation(task) {
    if (task.state !== "order_number_blocked") throw new Error("当前订单不是运单号门禁拦截状态");
    if (!window.confirm(`将关闭 ${task.order.source_record_id} 当前未保存页签并重新创建，是否继续？`)) return;
    const closed = await withOperationLock(() => closeOwnedTab(task, true));
    if (!closed) throw new Error("当前页签关闭失败，不能重新创建");
    task.orderNumber = ""; task.orderNumberSource = ""; task.fields = []; task.errorCode = ""; task.creationAttempted = false;
    transitionTask(task, "pending", "旧表单已关闭，等待重新创建");
  }

  async function closeOwnedTab(task, allowUnsaved) {
    if (!task.ownedTab || state.baselineTabs.has(task.tab) && task.tabOrigin !== "adopted") return false;
    if (!task.tab?.isConnected || !task.root?.isConnected) {
      task.cleanup = "auto_removed"; task.tab = null; task.root = null; return true;
    }
    if (!allowUnsaved) return false;
    const close = findTabCloseControl(task.tab);
    if (!close) return noteCloseFailure(task, "未找到页签关闭控件");
    dispatchUserLikeClick(close);
    try { await waitFor(() => !task.tab.isConnected || !task.root?.isConnected, 4000, "关闭页签后未观察到页签移除"); }
    catch (error) { return noteCloseFailure(task, errorMessage(error)); }
    task.cleanup = "closed"; task.tab = null; task.root = null; return true;
  }

  function noteCloseFailure(task, message) {
    task.cleanup = `close_failed:${message}`;
    task.error = task.errorCode === "ledger_error" ? `${task.error}；页签关闭失败：${message}` : message;
    if (!task.errorCode) task.errorCode = "close_error";
    state.pauseRequested = true;
    setStatus("页签关闭失败，已立即暂停，不会创建下一单。", "warning");
    return false;
  }

  function findTabCloseControl(tab) {
    if (!tab?.isConnected) return null;
    const selectors = [".fn-icon-close", ".el-icon-close", ".ant-tabs-tab-remove", "[aria-label*='关闭']", "[title*='关闭']", ".close", "button"];
    const candidates = [...new Set(selectors.flatMap((selector) => [...tab.querySelectorAll(selector)]))].filter((item) => item.isConnected);
    return candidates.find((item) => /关闭|close/i.test(`${item.getAttribute?.("aria-label") || ""} ${item.getAttribute?.("title") || ""} ${item.className || ""}`)) || candidates[0] || null;
  }

  function pauseBatch() { if (!state.batchStarted || state.completed) return; state.pauseRequested = true; setStatus("已请求暂停；当前操作将在安全边界结束。", "warning"); renderSummary(); }
  function resumeBatch() {
    if (!state.batchStarted || state.completed) return;
    if (state.legacyCheckpoint) { setStatus("旧并行检查点不能恢复；请人工处理遗留页签后删除检查点。", "warning"); return; }
    if (!state.authorization) { setStatus("恢复前请重新勾选保存授权。", "error"); return; }
    const blockers = state.tasks.filter((task) => MANUAL_STATES.has(task.state));
    if (blockers.length) { setStatus(`仍有 ${blockers.length} 条订单必须先人工处理。`, "warning"); return; }
    state.pauseRequested = false; state.stopRequested = false; state.globalHalt = ""; runScheduler();
  }
  function stopBatch() { if (!state.batchStarted || state.completed) return; state.stopRequested = true; setStatus("已请求停止；不再启动新的填写或保存。", "warning"); renderSummary(); }

  function transitionTask(task, next, message = "") {
    task.state = next; if (message) task.error = message; else if (!["mapping_failed", "save_failed", "decision_required", "save_ambiguous"].includes(next)) task.error = "";
    if (TERMINAL_STATES.has(next)) task.completedAt ||= now(); persistCheckpoint(); renderSummary();
  }

  function failTask(task, error) {
    task.error = errorMessage(error); task.errorCode = error.fatal ? "structure_error" : "mapping_error"; task.state = "mapping_failed"; state.pauseRequested = true; persistCheckpoint();
  }

  function maybeAutoResume() {
    if (state.running || state.completed || state.stopRequested || state.globalHalt || state.legacyCheckpoint || !state.authorization) return;
    if (state.tasks.some((task) => MANUAL_STATES.has(task.state))) return;
    if (!state.tasks.some((task) => task.state === "pending")) return;
    state.pauseRequested = false;
    queueMicrotask(() => runScheduler());
  }

  function updateBatchOutcome() {
    if (!state.batchStarted) return;
    const pending = state.tasks.some((task) => !TERMINAL_STATES.has(task.state));
    const ledgerErrors = state.tasks.filter((task) => task.errorCode === "ledger_error").length;
    if (!pending && ledgerErrors) {
      setStatus(`订单均已结束，但有 ${ledgerErrors} 条防重复台账写入失败；请释放浏览器存储空间后重试台账。`, "error");
      return;
    }
    if (!pending) {
      state.completed = true; state.running = false; localStorage.removeItem(CHECKPOINT_KEY);
      setStatus(`批次完成：保存 ${state.tasks.filter((task) => task.state === "saved").length} 条，放弃 ${state.tasks.filter((task) => task.state === "abandoned").length} 条。`);
      if (!state.reportExported) { state.reportExported = true; exportReportCsv(); }
    } else if (!state.tasks.some((task) => task.state === "pending") && !state.running) {
      setStatus(`自动队列已结束，仍有 ${state.tasks.filter((task) => MANUAL_STATES.has(task.state)).length} 条需要人工收尾。`, "warning");
    } else if (state.pauseRequested) setStatus("批次已暂停，可处理人工项或继续。", "warning");
  }

  function persistCheckpoint() {
    if (!state.batchStarted || state.completed || !state.tasks.length) return;
    const snapshot = {
      version: CHECKPOINT_VERSION, script_version: SCRIPT_VERSION, batch_id: state.batchId, file_name: state.fileName, updated_at: now(),
      diagnostics: state.diagnostics,
      tasks: state.tasks.map((task) => ({
        id: task.id, index: task.index, order: task.order, state: task.state, fields: task.fields, error: task.error, errorCode: task.errorCode,
        startedAt: task.startedAt, completedAt: task.completedAt, save: task.save, cleanup: task.cleanup, attempts: task.attempts,
        orderNumber: task.orderNumber, orderNumberSource: task.orderNumberSource, tabOrigin: task.tabOrigin, creationAttempted: task.creationAttempted, saveSettledAt: task.saveSettledAt,
      })),
    };
    try { localStorage.setItem(CHECKPOINT_KEY, JSON.stringify(snapshot)); }
    catch (error) { state.globalHalt = `无法写入中断检查点：${errorMessage(error)}`; }
  }

  function restoreCheckpoint() {
    let snapshot; let previousSerial = false;
    try { snapshot = JSON.parse(localStorage.getItem(CHECKPOINT_KEY) || "null"); } catch { localStorage.removeItem(CHECKPOINT_KEY); return; }
    if (!snapshot) {
      try { snapshot = JSON.parse(localStorage.getItem(PREVIOUS_SERIAL_CHECKPOINT_KEY) || "null"); previousSerial = Boolean(snapshot); }
      catch { localStorage.removeItem(PREVIOUS_SERIAL_CHECKPOINT_KEY); }
    }
    if (!snapshot) {
      let legacy;
      try { legacy = JSON.parse(localStorage.getItem(LEGACY_CHECKPOINT_KEY) || "null"); } catch { localStorage.removeItem(LEGACY_CHECKPOINT_KEY); return; }
      if (legacy?.tasks?.length) {
        state.tasks = legacy.tasks.map((saved, index) => ({ ...createTask(saved.order, index), ...saved, state: "legacy_checkpoint_blocked", tab: null, root: null, ownedTab: false, error: "v1.0.0 并行检查点不能由串行版自动恢复" }));
        state.batchId = legacy.batch_id || "legacy"; state.fileName = legacy.file_name || ""; state.batchStarted = true; state.pauseRequested = true; state.recovered = true; state.legacyCheckpoint = true;
        setStatus("检测到 v1.0.0 并行检查点。请人工处理遗留页签后删除检查点，脚本不会自动恢复。", "warning");
      }
      return;
    }
    if (!snapshot?.tasks?.length || ![2, CHECKPOINT_VERSION].includes(snapshot.version)) return;
    state.tasks = snapshot.tasks.map((saved, index) => {
      const task = { ...createTask(saved.order, index), ...saved, tab: null, root: null, ownedTab: false };
      if (task.state === "saved_pending_close") {
        task.state = "saved"; task.cleanup = "retained"; task.error = "旧版已明确保存记录已迁移为成功页签保留"; task.completedAt ||= now();
      } else if (!TERMINAL_STATES.has(task.state) && task.state !== "pending" && task.state !== "abandon_pending_close") {
        task.state = task.state === "saving" || task.state === "save_ambiguous" ? "save_ambiguous" : "interrupted_manual";
        task.error = task.state === "save_ambiguous" ? "运行中断时订单可能正在保存，必须查询订单列表" : "页面中断后原页签无法安全自动重绑，请确认旧表单状态";
      } else if (task.state === "abandon_pending_close") {
        task.error = "订单已明确放弃，但刷新后无法确认旧页签是否关闭；请人工关闭并确认";
      }
      return task;
    });
    state.batchId = snapshot.batch_id; state.fileName = snapshot.file_name; state.batchStarted = true; state.pauseRequested = true; state.recovered = true;
    if (snapshot.diagnostics?.events) {
      state.diagnostics = snapshot.diagnostics;
      state.diagnostics.events = state.diagnostics.events.slice(-DIAGNOSTIC_EVENT_LIMIT);
    }
    if (previousSerial) {
      localStorage.removeItem(PREVIOUS_SERIAL_CHECKPOINT_KEY);
      persistCheckpoint();
    }
    setStatus("检测到未完成检查点。脚本不会自动恢复，请先处理标记为中断或结果不确定的订单。", "warning");
  }

  function loadLedger() {
    let current = { version: 1, records: {} }; let legacy = { version: 1, records: {} };
    try { const value = JSON.parse(localStorage.getItem(LEDGER_KEY) || "null"); if (value?.version === 1 && value.records) current = value; } catch { /* ignore */ }
    try { const value = JSON.parse(localStorage.getItem(LEGACY_LEDGER_KEY) || "null"); if (value?.version === 1 && value.records) legacy = value; } catch { /* ignore */ }
    return { version: 1, records: { ...legacy.records, ...current.records } };
  }
  function saveLedger(ledger) { localStorage.setItem(LEDGER_KEY, JSON.stringify(ledger)); }
  function ledgerKey(batchId, recordId) { return `${encodeURIComponent(batchId)}::${encodeURIComponent(recordId)}`; }
  function addLedgerRecord(task) {
    const ledger = loadLedger(); ledger.records[ledgerKey(task.order.batch_id, task.order.source_record_id)] = {
      batch_id: task.order.batch_id, source_record_id: task.order.source_record_id, completed_at: now(), identity_fingerprint: task.save.identity_fingerprint || "",
    }; saveLedger(ledger);
  }

  function unlockDuplicates() {
    if (!state.duplicateKeys.length || !state.parsedImport) return;
    if (!window.confirm(`确认解除当前文件中 ${state.duplicateKeys.length} 条记录的本地重复保护？这可能创建重复订单。`)) return;
    const ledger = loadLedger();
    for (const key of state.duplicateKeys) delete ledger.records[key];
    saveLedger(ledger);
    try {
      const legacy = JSON.parse(localStorage.getItem(LEGACY_LEDGER_KEY) || "null");
      if (legacy?.version === 1 && legacy.records) {
        for (const key of state.duplicateKeys) delete legacy.records[key];
        localStorage.setItem(LEGACY_LEDGER_KEY, JSON.stringify(legacy));
      }
    } catch { /* 新台账已经完成解除；损坏的旧台账不会阻止操作 */ }
    applyValidation(validateOrders(state.parsedImport)); renderSummary();
  }

  function resetForNewBatch() {
    if (state.batchStarted && !state.completed) { setStatus("当前批次尚未完成，不能直接新建批次。", "error"); return; }
    Object.assign(state, {
      tasks: [], parsedImport: null, importErrors: [], duplicateKeys: [], fileName: "", batchId: "",
      running: false, batchStarted: false, pauseRequested: false, stopRequested: false, globalHalt: "",
      authorization: false, safetyBlocks: 0, savePermit: null, clickPermitId: "", baselineTabs: new Set(),
      recovered: false, legacyCheckpoint: false, completed: false, reportExported: false,
      diagnostics: { session_id: stableHash(`${Date.now()}-${Math.random()}`), started_at: now(), bridge_ready: state.diagnostics.bridge_ready, events: [] },
    });
    state.ui.file.value = ""; state.ui.authorize.checked = false; setStatus("请选择 Excel。"); renderSummary();
  }

  function discardRecoveredBatch() {
    if (!state.recovered || !window.confirm("确认删除中断检查点？脚本不会关闭或保存网站中遗留的页签，请先人工处理。")) return;
    localStorage.removeItem(CHECKPOINT_KEY); localStorage.removeItem(PREVIOUS_SERIAL_CHECKPOINT_KEY); localStorage.removeItem(LEGACY_CHECKPOINT_KEY); state.completed = true; state.recovered = false; state.legacyCheckpoint = false; setStatus("中断检查点已删除。网站遗留页签仍需人工处理。", "warning"); renderSummary();
  }

  function withOperationLock(operation) {
    const run = operationMutex.then(operation, operation); operationMutex = run.catch(() => undefined); return run;
  }

  async function switchToTask(task, settleMs = 350) {
    let rebound = false;
    if (!task.tab?.isConnected || !task.root?.isConnected) rebound = await rebindTaskContext(task, "切换前检测到页签或表单DOM已被网站替换");
    const alreadyActive = getActiveTab() === task.tab && isFormRootActive(task.root);
    if (!alreadyActive) dispatchUserLikeClick(task.tab);
    recordDiagnostic("task_switch", {
      already_active: alreadyActive, click_dispatched: !alreadyActive,
      active_data_path: document.activeElement?.getAttribute?.("data-path") || "", dropdown_menu_visible: dropdownMenuState().visible,
    }, task.id);
    try { await waitFor(() => getActiveTab() === task.tab || isFormRootActive(task.root), 5000, "切换订单页签超时"); }
    catch (error) {
      if (!task.tab?.isConnected || !task.root?.isConnected) rebound = await rebindTaskContext(task, "切换过程中页签或表单DOM被网站替换");
      else throw error;
    }
    await sleep(settleMs);
    if (!task.tab?.isConnected || !task.root?.isConnected) rebound = await rebindTaskContext(task, "切换稳定等待期间页签或表单DOM被网站替换");
    return rebound;
  }

  async function rebindTaskContext(task, reason) {
    const oldTabConnected = Boolean(task.tab?.isConnected); const oldRootConnected = Boolean(task.root?.isConnected);
    if (oldTabConnected) dispatchUserLikeClick(task.tab);
    const deadline = Date.now() + 8000; let stableTab = null; let stableRoot = null; let stableSince = 0;
    while (Date.now() < deadline) {
      const tab = getActiveTab(); const sender = findUniqueVisiblePath("cor_name"); const root = inferFormRoot(sender);
      let number = ""; try { number = root ? readOrderNumber(root).value : ""; } catch { /* wait for final form */ }
      const matches = tab?.isConnected && root?.isConnected && number && normalizeOrderNumber(number) === normalizeOrderNumber(task.orderNumber);
      if (matches) {
        if (tab !== stableTab || root !== stableRoot) { stableTab = tab; stableRoot = root; stableSince = Date.now(); }
        if (Date.now() - stableSince >= 500) break;
      } else { stableTab = null; stableRoot = null; stableSince = 0; }
      await sleep(100);
    }
    if (!stableTab?.isConnected || !stableRoot?.isConnected) throw new Error(`网站替换了订单页面DOM，但未能按原运单号尾号 ${orderNumberSuffix(task.orderNumber)} 安全重新绑定`);
    const conflict = state.tasks.find((item) => item !== task && item.root === stableRoot && !TERMINAL_STATES.has(item.state));
    if (conflict) throw new Error("重新绑定的表单已经属于另一条未完成订单，已停止避免串单");
    task.tab = stableTab; task.root = stableRoot; task.ownedTab = true;
    stableTab.setAttribute("data-cm-production-task", task.id); stableRoot.setAttribute("data-cm-production-root", task.id);
    recordDiagnostic("task_context_rebound", { reason, old_tab_connected: oldTabConnected, old_root_connected: oldRootConnected, number_suffix: orderNumberSuffix(task.orderNumber) }, task.id);
    persistCheckpoint();
    return true;
  }

  async function reconcileDirectFieldsAfterRebind(task) {
    let corrected = 0;
    for (const mapping of DIRECT_FIELDS) {
      const control = uniqueControlInTask(task, mapping.dataPath); const expected = task.order[mapping.key];
      const matches = mapping.kind === "number" && !isBlank(expected) ? Number(readControl(control)) === Number(expected) : normalizeText(readControl(control)) === normalizeText(isBlank(expected) ? "" : expected);
      if (matches) continue;
      await writeControl(control, isBlank(expected) ? "" : expected, { focus: false, blur: false });
      const actual = readControl(uniqueControlInTask(task, mapping.dataPath));
      const verified = mapping.kind === "number" && !isBlank(expected) ? Number(actual) === Number(expected) : normalizeText(actual) === normalizeText(isBlank(expected) ? "" : expected);
      if (!verified) throw new Error(`页面重挂载后 ${mapping.key} 重新写入仍不一致`);
      corrected += 1;
    }
    recordDiagnostic("direct_fields_reconciled_after_rebind", { corrected_fields: corrected }, task.id);
  }

  function inferFormRoot(control) {
    if (!control) return null; let current = control; let best = null;
    for (; current && current !== document.body; current = current.parentElement) {
      const count = FIELD_MAPPINGS.filter((item) => current.querySelector(`[data-path="${CSS.escape(item.dataPath)}"]`)).length;
      if (!best || count > best.count) best = { element: current, count };
      const token = `${current.id || ""} ${current.className || ""}`.toLowerCase();
      if (count >= 9 && (current.tagName?.toLowerCase() === "form" || /form|order|tab|pane|content|panel/.test(token))) return current;
    }
    return best?.count >= 8 ? best.element : null;
  }

  function uniqueControlInTask(task, dataPath) {
    if (!task.root?.isConnected) throw fatalError("任务表单根容器已断开");
    const matches = [...task.root.querySelectorAll(`[data-path="${CSS.escape(dataPath)}"]`)].filter((item) => item.isConnected);
    if (matches.length !== 1) throw fatalError(`任务表单内 data-path=${dataPath} 数量为 ${matches.length}`);
    return matches[0];
  }

  function listTabElements() {
    return [...new Set(["[role='tab']", ".el-tabs__item", ".ant-tabs-tab"].flatMap((selector) => [...document.querySelectorAll(selector)]))].filter((item) => !item.closest(`#${PANEL_ID}`) && item.isConnected);
  }
  function getActiveTab() {
    for (const selector of ["[role='tab'][aria-selected='true']", ".el-tabs__item.is-active", ".ant-tabs-tab-active", ".is-active[role='tab']"]) {
      const match = [...document.querySelectorAll(selector)].find((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item)); if (match) return match;
    } return null;
  }
  function isFormRootActive(root) { const visible = visiblePathElements("cor_name"); return visible.length === 1 && root.contains(visible[0]); }
  function visiblePathElements(path) { return [...document.querySelectorAll(`[data-path="${CSS.escape(path)}"]`)].filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item)); }
  function findUniqueVisiblePath(path) { const matches = visiblePathElements(path); return matches.length === 1 ? matches[0] : null; }
  function locateCreateOrderAction() {
    const iconBacked = [...document.querySelectorAll("i.fn-icon.fn-icon-file-add")]
      .filter((icon) => !icon.closest(`#${PANEL_ID}`) && isVisible(icon))
      .map((icon) => icon.closest("a,button,[role='button']"))
      .filter((action) => action && isVisible(action) && normalizeText(action.querySelector("span")?.textContent || action.textContent) === "创建运单");
    const primary = [...new Set(iconBacked)];
    if (primary.length === 1) return { action: primary[0], source: "fn-icon-file-add", error: "" };
    if (primary.length > 1) return { action: null, source: "fn-icon-file-add", error: `发现 ${primary.length} 个可见的“创建运单”图标按钮` };
    const fallback = [...new Set([...document.querySelectorAll("a,button,[role='button'],.el-button,.ant-btn")]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item) && normalizeText(item.textContent) === "创建运单")
      .map((item) => item.closest("a,button,[role='button'],.el-button,.ant-btn") || item))];
    if (fallback.length === 1) return { action: fallback[0], source: "exact-text", error: "" };
    if (fallback.length > 1) return { action: null, source: "exact-text", error: `发现 ${fallback.length} 个可见的“创建运单”文本按钮` };
    return { action: null, source: "none", error: "" };
  }
  async function waitForStableCreateOrderAction(timeoutMs, stableMs) {
    const deadline = Date.now() + timeoutMs;
    let stableAction = null; let stableSince = 0;
    while (Date.now() < deadline) {
      const located = locateCreateOrderAction();
      if (located.error) throw createGateError("create_entry_multiple", `${located.error}；已停止避免误点`);
      if (located.action) {
        if (located.action !== stableAction) { stableAction = located.action; stableSince = Date.now(); }
        if (Date.now() - stableSince >= stableMs && stableAction.isConnected && isVisible(stableAction)) return stableAction;
      } else { stableAction = null; stableSince = 0; }
      await sleep(100);
    }
    throw createGateError("create_entry_timeout", `保存后等待 ${timeoutMs}ms 仍未找到稳定且唯一的“创建运单”按钮`);
  }
  function findUniqueSaveAction(continueOnly = false) {
    const candidates = [...document.querySelectorAll("button,a,[role='button'],.el-button,.ant-btn")].filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item) && (continueOnly ? normalizeText(item.textContent) === "继续保存" : SAVE_BUTTON_TEXT.test(normalizeText(item.textContent))));
    const unique = [...new Set(candidates.map((item) => item.closest("button,a,[role='button'],.el-button,.ant-btn") || item))]; return unique.length === 1 ? unique[0] : null;
  }

  async function writeControl(control, value, options = {}) {
    if (!control?.isConnected) throw fatalError("目标控件已从 DOM 移除");
    if (options.focus !== false) control.focus();
    setNativeControlValue(control, value);
    for (const type of ["input", "change"]) control.dispatchEvent(new Event(type, { bubbles: true }));
    if (options.blur !== false) control.blur(); await sleep(160);
  }

  function setNativeControlValue(control, value) {
    const prototype = control instanceof HTMLInputElement ? HTMLInputElement.prototype : control instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : null;
    const setter = prototype && Object.getOwnPropertyDescriptor(prototype, "value")?.set;
    if (setter) setter.call(control, value); else control.value = value;
  }
  function readControl(control) { return String(control?.value ?? control?.getAttribute?.("value") ?? ""); }
  function dispatchUserLikeClick(target) {
    target.scrollIntoView?.({ block: "nearest", inline: "nearest" }); const options = { bubbles: true, cancelable: true, composed: true, view: window, button: 0, buttons: 1 };
    if (typeof PointerEvent === "function") target.dispatchEvent(new PointerEvent("pointerdown", { ...options, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    target.dispatchEvent(new MouseEvent("mousedown", options)); target.dispatchEvent(new MouseEvent("mouseup", { ...options, buttons: 0 }));
    if (typeof PointerEvent === "function") target.dispatchEvent(new PointerEvent("pointerup", { ...options, buttons: 0, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    target.dispatchEvent(new MouseEvent("click", { ...options, buttons: 0 }));
  }

  function fieldResult(mapping) { return { key: mapping.key, data_path: mapping.dataPath, status: "pending", error: "" }; }
  function mergeFields(existing, incoming) { const map = new Map(existing.map((item) => [item.key, item])); for (const item of incoming) map.set(item.key, item); return FIELD_MAPPINGS.map((item) => map.get(item.key)).filter(Boolean); }
  function fatalError(message) { const error = new Error(message); error.fatal = true; return error; }

  async function parseXlsxArrayBuffer(arrayBuffer, preferredSheetName) {
    const zip = new MinimalZip(new Uint8Array(arrayBuffer));
    const workbookXml = parseXml(await zip.text("xl/workbook.xml"), "workbook.xml");
    const relsXml = parseXml(await zip.text("xl/_rels/workbook.xml.rels"), "workbook relationships");
    const relationships = new Map(
      [...relsXml.getElementsByTagNameNS("*", "Relationship")].map((node) => [node.getAttribute("Id"), node.getAttribute("Target")]),
    );
    const sheets = [...workbookXml.getElementsByTagNameNS("*", "sheet")];
    const sheetNode = sheets.find((node) => node.getAttribute("name") === preferredSheetName);
    if (!sheetNode) throw new Error(`工作簿缺少“${preferredSheetName}”工作表`);
    const relationshipId = sheetNode.getAttribute("r:id") || sheetNode.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
    const target = relationships.get(relationshipId);
    if (!target) throw new Error(`无法解析“${preferredSheetName}”工作表关系`);
    const sheetPath = normalizeZipPath(target.startsWith("/") ? target.slice(1) : `xl/${target}`);

    let sharedStrings = [];
    if (zip.has("xl/sharedStrings.xml")) {
      const sharedXml = parseXml(await zip.text("xl/sharedStrings.xml"), "sharedStrings.xml");
      sharedStrings = [...sharedXml.getElementsByTagNameNS("*", "si")].map((item) => [...item.getElementsByTagNameNS("*", "t")].map((node) => node.textContent || "").join(""));
    }
    const sheetXml = parseXml(await zip.text(sheetPath), preferredSheetName);
    return worksheetToOrders(sheetXml, sharedStrings);
  }

  function worksheetToOrders(sheetXml, sharedStrings) {
    const rows = [];
    for (const rowNode of sheetXml.getElementsByTagNameNS("*", "row")) {
      const rowNumber = Number(rowNode.getAttribute("r") || rows.length + 1);
      const cells = new Map();
      for (const cell of rowNode.getElementsByTagNameNS("*", "c")) {
        const reference = cell.getAttribute("r") || "";
        const column = columnIndex(reference);
        const type = cell.getAttribute("t") || "n";
        const formula = cell.getElementsByTagNameNS("*", "f").length > 0;
        const valueNode = cell.getElementsByTagNameNS("*", "v")[0];
        let value = "";
        if (type === "inlineStr") value = [...cell.getElementsByTagNameNS("*", "t")].map((node) => node.textContent || "").join("");
        else if (type === "s") value = sharedStrings[Number(valueNode?.textContent || 0)] ?? "";
        else if (type === "b") value = valueNode?.textContent === "1";
        else if (type === "str") value = valueNode?.textContent || "";
        else if (valueNode?.textContent != null && valueNode.textContent !== "") value = Number(valueNode.textContent);
        cells.set(column, { value, formula });
      }
      rows.push({ rowNumber, cells });
    }

    const headerRow = rows.find((row) => {
      const values = [...row.cells.values()].map((cell) => String(cell.value).trim());
      return REQUIRED_HEADERS.every((header) => values.includes(header));
    });
    if (!headerRow) throw new Error(`未找到完整表头；需要：${REQUIRED_HEADERS.join(", ")}`);
    const headerColumns = new Map();
    for (const [column, cell] of headerRow.cells) headerColumns.set(String(cell.value).trim(), column);
    const missing = REQUIRED_HEADERS.filter((header) => !headerColumns.has(header));
    const errors = missing.map((header) => `缺少列：${header}`);
    const dataRows = rows.filter((row) => row.rowNumber > headerRow.rowNumber).map((row) => {
      const output = { __rowNumber: row.rowNumber, __formulaFields: [] };
      for (const header of REQUIRED_HEADERS) {
        const cell = row.cells.get(headerColumns.get(header));
        output[header] = cell?.value ?? "";
        if (cell?.formula) output.__formulaFields.push(header);
      }
      return output;
    }).filter((row) => REQUIRED_HEADERS.some((header) => !isBlank(row[header])));
    return { rows: dataRows, errors };
  }

  class MinimalZip {
    constructor(bytes) {
      this.bytes = bytes;
      this.entries = this.readDirectory();
    }
    has(name) { return this.entries.has(normalizeZipPath(name)); }
    async text(name) { return TEXT_DECODER.decode(await this.read(name)); }
    async read(name) {
      const key = normalizeZipPath(name);
      const entry = this.entries.get(key);
      if (!entry) throw new Error(`XLSX 缺少文件：${key}`);
      const view = new DataView(this.bytes.buffer, this.bytes.byteOffset, this.bytes.byteLength);
      const local = entry.localOffset;
      if (view.getUint32(local, true) !== 0x04034b50) throw new Error(`ZIP 本地文件头损坏：${key}`);
      const nameLength = view.getUint16(local + 26, true);
      const extraLength = view.getUint16(local + 28, true);
      const start = local + 30 + nameLength + extraLength;
      const compressed = this.bytes.slice(start, start + entry.compressedSize);
      if (entry.method === 0) return compressed;
      if (entry.method !== 8) throw new Error(`不支持的 ZIP 压缩方式 ${entry.method}：${key}`);
      if (typeof DecompressionStream !== "function") throw new Error("当前浏览器不支持本地 XLSX 解压，请使用最新版 Chrome/Edge");
      const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
      return new Uint8Array(await new Response(stream).arrayBuffer());
    }
    readDirectory() {
      const view = new DataView(this.bytes.buffer, this.bytes.byteOffset, this.bytes.byteLength);
      let eocd = -1;
      for (let offset = this.bytes.length - 22; offset >= Math.max(0, this.bytes.length - 65557); offset -= 1) {
        if (view.getUint32(offset, true) === 0x06054b50) { eocd = offset; break; }
      }
      if (eocd < 0) throw new Error("不是有效的 XLSX/ZIP 文件");
      const count = view.getUint16(eocd + 10, true);
      let offset = view.getUint32(eocd + 16, true);
      const entries = new Map();
      for (let index = 0; index < count; index += 1) {
        if (view.getUint32(offset, true) !== 0x02014b50) throw new Error("ZIP 中央目录损坏");
        const method = view.getUint16(offset + 10, true);
        const compressedSize = view.getUint32(offset + 20, true);
        const nameLength = view.getUint16(offset + 28, true);
        const extraLength = view.getUint16(offset + 30, true);
        const commentLength = view.getUint16(offset + 32, true);
        const localOffset = view.getUint32(offset + 42, true);
        const name = TEXT_DECODER.decode(this.bytes.slice(offset + 46, offset + 46 + nameLength));
        entries.set(normalizeZipPath(name), { method, compressedSize, localOffset });
        offset += 46 + nameLength + extraLength + commentLength;
      }
      return entries;
    }
  }

  function parseXml(text, label) {
    const xml = new DOMParser().parseFromString(text, "application/xml");
    const parserError = xml.querySelector("parsererror");
    if (parserError) throw new Error(`${label} XML 解析失败`);
    return xml;
  }

  function columnIndex(reference) {
    const letters = (reference.match(/^[A-Z]+/i) || [""])[0].toUpperCase();
    let value = 0;
    for (const char of letters) value = value * 26 + char.charCodeAt(0) - 64;
    return value - 1;
  }

  function normalizeZipPath(path) {
    const parts = [];
    for (const part of String(path).replace(/\\/g, "/").split("/")) {
      if (!part || part === ".") continue;
      if (part === "..") parts.pop(); else parts.push(part);
    }
    return parts.join("/");
  }

  function renderSummary() {
    if (!state.ui) return;
    const counts = {
      saved: state.tasks.filter((task) => task.state === "saved").length,
      pending: state.tasks.filter((task) => task.state === "pending").length,
      manual: state.tasks.filter((task) => MANUAL_STATES.has(task.state)).length,
      failed: state.tasks.filter((task) => ["mapping_failed", "save_failed", "save_ambiguous"].includes(task.state)).length,
    };
    state.ui.stats.innerHTML = [["总数", state.tasks.length], ["已保存", counts.saved], ["待执行", counts.pending], ["人工项", counts.manual], ["保存拦截", state.safetyBlocks]]
      .map(([label, value]) => `<div class="stat"><b>${value}</b>${label}</div>`).join("");
    const filter = state.ui.filter.value;
    const visibleTasks = state.tasks.filter((task) => filter === "all"
      || filter === "manual" && MANUAL_STATES.has(task.state)
      || filter === "failed" && ["mapping_failed", "save_failed", "save_ambiguous"].includes(task.state)
      || filter === "saved" && task.state === "saved");
    state.ui.rows.innerHTML = visibleTasks.map((task) => {
      const rowClass = task.state === "saved" ? "ok" : MANUAL_STATES.has(task.state) ? "manual" : task.state === "abandoned" ? "bad" : "";
      return `<tr class="${rowClass}"><td>${escapeHtml(task.order.source_record_id)}</td><td>${escapeHtml(stateLabel(task.state))}</td><td>${escapeHtml(taskExplanation(task))}</td><td class="actions">${taskActions(task)}</td></tr>`;
    }).join("") || `<tr><td colspan="4">暂无记录</td></tr>`;
    state.ui.errors.textContent = state.importErrors.length ? state.importErrors.join("\n") : state.globalHalt || "无";
    state.ui.file.disabled = state.batchStarted;
    state.ui.authorize.disabled = state.running;
    state.ui.start.disabled = state.running || state.batchStarted || !state.tasks.length || state.importErrors.length > 0 || !state.authorization;
    state.ui.pause.disabled = !state.running || state.pauseRequested;
    state.ui.resume.disabled = !state.batchStarted || state.running || state.completed || !state.authorization;
    state.ui.stop.disabled = !state.batchStarted || state.completed || state.stopRequested;
    state.ui.export.disabled = !state.tasks.length;
    state.ui.unlock.disabled = !state.duplicateKeys.length || state.batchStarted;
    state.ui.discard.disabled = !state.recovered || state.running;
    state.ui.newBatch.disabled = state.batchStarted && !state.completed;
  }

  function taskActions(task) {
    if (task.state === "saved" && task.errorCode === "ledger_error") return actionButton(task, "retry-ledger", "重试防重复台账");
    if (TERMINAL_STATES.has(task.state)) return "";
    const buttons = [];
    if (task.tab?.isConnected) buttons.push(actionButton(task, "activate", "打开页签"));
    if (task.state === "decision_required") {
      buttons.push(actionButton(task, "continue-save", "继续保存"));
    }
    if (["mapping_failed", "save_failed", "manual_pending"].includes(task.state) && task.tab?.isConnected) buttons.push(actionButton(task, "repair", "修正后验证保存"));
    if (task.state === "order_number_blocked" && task.ownedTab) buttons.push(actionButton(task, "retry-order", "关闭并重新创建"));
    if (task.state === "order_number_blocked" && !task.ownedTab) buttons.push(actionButton(task, "retry-preflight", "重新检查页面"));
    if (task.state === "create_entry_blocked") buttons.push(actionButton(task, "retry-preflight", "重新检查并创建下一单"));
    if (task.state === "abandon_pending_close") {
      buttons.push(actionButton(task, "retry-close", "重试关闭"));
      buttons.push(actionButton(task, "confirm-closed", "确认已人工关闭"));
    }
    if (["save_ambiguous", "interrupted_manual"].includes(task.state)) {
      buttons.push(actionButton(task, "confirm-saved", "确认已保存"));
      buttons.push(actionButton(task, "confirm-not-saved", "确认未生成并重排"));
    }
    if (MANUAL_STATES.has(task.state) && task.ownedTab && task.tab?.isConnected && !["abandon_pending_close", "save_ambiguous", "interrupted_manual", "legacy_checkpoint_blocked"].includes(task.state)) buttons.push(actionButton(task, "abandon", "放弃本单", false, "danger"));
    return buttons.join("");
  }

  function actionButton(task, action, label, disabled = false, className = "") {
    return `<button data-action="${action}" data-task="${escapeHtml(task.id)}" class="${className}" ${disabled ? "disabled" : ""}>${escapeHtml(label)}</button>`;
  }

  function stateLabel(value) {
    return ({
      pending: "待处理", creating: "检查空白页签", waiting_create_entry: "等待创建入口", direct_filling: "普通字段填写", custom_pending: "等待活动页签", custom_filling: "自定义字段填写",
      verifying: "回读验证", ready_to_save: "待保存", saving: "保存中", saved: "保存成功", mapping_failed: "填写失败",
      save_failed: "保存失败", decision_required: "需要确认", save_ambiguous: "结果不确定", manual_pending: "人工待处理",
      interrupted_manual: "中断待核对", order_number_blocked: "运单号异常", create_entry_blocked: "创建入口待处理",
      abandon_pending_close: "放弃待关闭", legacy_checkpoint_blocked: "旧检查点待处理", abandoned: "已放弃",
    })[value] || value;
  }

  function taskExplanation(task) {
    if (task.errorCode === "ledger_error") return sanitizeForReport(task, task.error);
    if (task.state === "saved") return task.cleanup === "retained" ? "已保存；成功页签保留" : "已保存";
    return sanitizeForReport(task, task.error || task.save.message || "");
  }

  function sanitizeForReport(task, message) {
    let output = String(message || "");
    for (const [key, value] of Object.entries(task?.order || {})) {
      if (["batch_id", "source_record_id", "schema_version", "delivery_type", "payment_type"].includes(key)) continue;
      const text = String(value ?? "").trim(); if (text.length >= 2) output = output.split(text).join("<已脱敏>");
    }
    return output.replace(/1\d{10}/g, "<手机号已脱敏>");
  }

  function exportReportCsv() {
    if (!state.tasks.length) return;
    const header = ["batch_id", "source_record_id", "state", "field", "field_status", "error_code", "error", "save_channel", "save_http_status", "save_errno", "identity_fingerprint", "order_number_suffix", "order_number_fingerprint", "started_at", "completed_at", "cleanup"] ;
    const rows = [header];
    for (const task of state.tasks) {
      const fields = task.fields.length ? task.fields : [{ key: "", status: "" }];
      for (const field of fields) rows.push([
        task.order.batch_id, task.order.source_record_id, task.state, field.key || "", field.status || "", task.errorCode || "",
        sanitizeForReport(task, field.error || task.error), task.save.channel, task.save.http_status, task.save.errno, task.save.identity_fingerprint,
        orderNumberSuffix(task.orderNumber), task.orderNumber ? stableHash(normalizeOrderNumber(task.orderNumber)) : "",
        task.startedAt, task.completedAt, task.cleanup,
      ]);
    }
    const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(",")).join("\r\n")}`;
    const link = document.createElement("a"); link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    link.download = `车满满批量执行脱敏报告-${state.batchId || "未命名"}-${new Date().toISOString().replace(/[:.]/g, "-")}.csv`; link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function buildDiagnosticReport() {
    const events = state.diagnostics.events.map((event) => sanitizeDiagnosticValue(event, state.tasks.find((task) => task.id === event.task_id)));
    const taskSummaries = state.tasks.map((task) => {
      const taskEvents = events.filter((event) => event.task_id === task.id);
      const eventTypes = taskEvents.map((event) => event.type);
      let captureConclusion = "no_save_attempt";
      if (eventTypes.includes("page_response_body")) captureConclusion = "response_body_captured_by_page_observer";
      else if (eventTypes.includes("direct_response_body")) captureConclusion = "response_body_captured_by_response_guard";
      else if (eventTypes.includes("fetch_response_body")) captureConclusion = "response_body_captured_by_fetch_guard";
      else if (eventTypes.includes("xhr_response_body")) captureConclusion = "response_body_captured_by_xhr_guard";
      else if (eventTypes.includes("performance_resource")) captureConclusion = "request_completed_but_response_body_not_observed";
      else if (eventTypes.includes("save_click_dispatch")) captureConclusion = "save_clicked_but_request_not_observed";
      return {
        batch_id: task.order.batch_id, source_record_id: task.order.source_record_id, state: task.state, error_code: task.errorCode || "",
        error: sanitizeForReport(task, task.error || ""), attempts: task.attempts, save: task.save,
        order_number_suffix: orderNumberSuffix(task.orderNumber), order_number_fingerprint: task.orderNumber ? stableHash(normalizeOrderNumber(task.orderNumber)) : "",
        capture_conclusion: captureConclusion, event_count: taskEvents.length,
      };
    });
    return {
      diagnostic_schema_version: DIAGNOSTIC_SCHEMA_VERSION, script_version: SCRIPT_VERSION, exported_at: now(),
      privacy_notice: "不包含请求体、Cookie、Token、姓名、手机号、完整地址、完整运单号或原始响应正文。",
      session: { session_id: state.diagnostics.session_id, started_at: state.diagnostics.started_at, bridge_ready: state.diagnostics.bridge_ready },
      environment: {
        page_path: location.pathname, user_agent: navigator.userAgent, language: navigator.language,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "", visibility: document.visibilityState,
        fetch_guarded: Boolean(window.fetch?.__cmProductionGuarded), xhr_open_guarded: Boolean(XMLHttpRequest.prototype.open.__cmProductionGuarded),
        xhr_send_guarded: Boolean(XMLHttpRequest.prototype.send.__cmProductionGuarded), performance_observer_supported: typeof PerformanceObserver === "function",
      },
      batch: { batch_id: state.batchId, task_count: state.tasks.length, running: state.running, paused: state.pauseRequested, stopped: state.stopRequested, global_halt: safeDiagnosticText(state.globalHalt || "") },
      tasks: taskSummaries, events,
    };
  }

  function exportDiagnosticJson() {
    const report = buildDiagnosticReport();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: "application/json;charset=utf-8" }));
    link.download = `车满满保存链路诊断-${state.batchId || "未命名"}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    setStatus("已导出脱敏诊断JSON。出现结果不确定时，请把该文件发给开发者。", "warning");
  }

  function setStatus(message, type = "") {
    if (!state.ui) return; state.ui.status.className = `status${type ? ` ${type}` : ""}`; state.ui.status.textContent = message;
  }

  function waitFor(predicate, timeoutMs, message) {
    return new Promise((resolve, reject) => {
      const start = Date.now(); const tick = () => {
        try { if (predicate()) return resolve(); } catch { /* retry */ }
        if (Date.now() - start >= timeoutMs) return reject(new Error(message)); setTimeout(tick, 100);
      }; tick();
    });
  }
  function isVisible(element) {
    if (!(element instanceof Element) || !element.isConnected) return false;
    const style = getComputedStyle(element); return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0" && element.getClientRects().length > 0 && element.getAttribute("aria-hidden") !== "true";
  }
  function normalizeText(value) { return String(value ?? "").replace(/\s+/g, "").replace(/[：:]+$/, "").trim(); }
  function isBlank(value) { return value === "" || value === null || value === undefined; }
  function toNumber(value) { return isBlank(value) ? Number.NaN : Number(value); }
  function hasAtMostTwoDecimals(value) { return Math.abs(value * 100 - Math.round(value * 100)) < 1e-8; }
  function errorMessage(error) { return error instanceof Error ? error.message : String(error); }
  function csvCell(value) { const text = String(value ?? ""); return `"${text.replace(/"/g, '""')}"`; }
  function escapeHtml(value) { return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char])); }
  function redactUrl(value) { try { return new URL(String(value), location.href).pathname; } catch { return String(value).slice(0, 120); } }
  function stableHash(value) { let hash = 2166136261; for (const char of String(value)) { hash ^= char.charCodeAt(0); hash = Math.imul(hash, 16777619); } return `fnv1a-${(hash >>> 0).toString(16).padStart(8, "0")}`; }
  function now() { return new Date().toISOString(); }
  function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

  window.__CMBatchSerial = Object.freeze({
    version: SCRIPT_VERSION, maxOrders: MAX_ORDERS, concurrency: SERIAL_CONCURRENCY, parallelism: SERIAL_CONCURRENCY,
    requiredHeaders: [...REQUIRED_HEADERS], fieldMappings: FIELD_MAPPINGS,
    parseXlsxArrayBuffer, validateOrders, classifySaveResponse, extractOrderIdentity, sanitizeForReport, ledgerKey,
    readOrderNumber, normalizeOrderNumber, isOrderNumberAdvanced, orderNumberSuffix, locateCreateOrderAction,
    nextPendingTask, unlockDuplicates, pauseBatch, stopBatch, switchToTask, buildDiagnosticReport, state,
  });
})();
