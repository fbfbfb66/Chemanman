// ==UserScript==
// @name         车满满串行批量自动保存测试
// @namespace    codex.chemanman.single-save.test
// @version      0.1.8
// @description  从本地 XLSX 导入多条获准的虚构订单，逐条创建、填写、回读并保存。
// @author       User
// @match        https://t800.chemanman.com/Order*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  "use strict";

  const SCRIPT_VERSION = "0.1.8";
  const REQUIRED_HEADERS = [
    "schema_version",
    "batch_id",
    "source_record_id",
    "source_label",
    "destination_text",
    "delivery_type",
    "sender_name",
    "receiver_name",
    "receiver_mobile",
    "goods_name",
    "package",
    "quantity",
    "weight",
    "volume",
    "freight",
    "payment_type",
  ];
  const BLOCKED_REQUEST = /\/api\/Order\/Order\/coHandle(?:\/|\?|$)/i;
  const SAVE_BUTTON_TEXT = /^(保存(?:\(F9\))?|保存并打印|保存并关闭|提交运单|继续保存)$/i;
  const PANEL_ID = "cm-serial-save-test-host";
  const MAX_TEST_ORDERS = 2;
  const LEGACY_CONFLICT_MESSAGE = "检测到 v0.1.6 只填表脚本仍在运行，它会拦截保存请求。请先在油猴中停用旧脚本并刷新页面。";
  const TEXT_DECODER = new TextDecoder("utf-8");

  const FIELD_MAPPINGS = [
    { key: "destination_text", dataPath: "arr", labels: ["到站", "目的地"], kind: "autocomplete", required: true },
    { key: "delivery_type", dataPath: "delivery_mode", labels: ["送货方式", "交货方式", "配送方式"], kind: "choice", required: true, display: { delivery: "送货", pickup: "自提" } },
    { key: "sender_name", dataPath: "cor_name", labels: ["发货人", "托运人", "发货人姓名"], kind: "text", required: true },
    { key: "receiver_name", dataPath: "cee_name", labels: ["收货人", "提货人", "收货人姓名"], kind: "text", required: true },
    { key: "receiver_mobile", dataPath: "cee_mobile", labels: ["收货人手机号", "收货电话", "收货人手机", "收货人电话", "手机号码"], kind: "text", required: false },
    { key: "goods_name", dataPath: "name_1", labels: ["货物名称", "品名", "货名"], kind: "text", required: true, tableField: true },
    { key: "package", dataPath: "pkg_1", labels: ["包装", "包装方式"], kind: "text", required: false, tableField: true, suggestionOptional: true },
    { key: "quantity", dataPath: "num_1", labels: ["件数", "数量"], kind: "number", required: true, tableField: true },
    { key: "weight", dataPath: "weight_1", labels: ["重量"], kind: "number", required: false, tableField: true },
    { key: "volume", dataPath: "volume_1", labels: ["体积"], kind: "number", required: false, tableField: true },
    { key: "freight", dataPath: "co_freight_f", labels: ["运费", "基本运费"], kind: "number", required: true },
    { key: "payment_type", dataPath: "pay_mode", labels: ["付款方式", "支付方式"], kind: "choice", required: true, display: { pay_billing: "现付" } },
  ];

  const state = {
    orders: [],
    cursor: 0,
    results: [],
    importErrors: [],
    busy: false,
    safetyBlocks: 0,
    batchStarted: false,
    saveAttempts: 0,
    saveInFlight: false,
    saveClickPermit: false,
    saveRequestPermit: false,
    saveRequestSeen: false,
    saveResolver: null,
    saveTimer: null,
    fileName: "",
    ui: null,
  };

  installSafetyGuards();

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initializeUi, { once: true });
  } else {
    initializeUi();
  }

  function installSafetyGuards() {
    const originalFetch = window.fetch;
    if (typeof originalFetch === "function") {
      window.fetch = function guardedFetch(input, init) {
        const url = typeof input === "string" ? input : input?.url || "";
        if (BLOCKED_REQUEST.test(String(url))) {
          if (!consumeSaveRequestPermit("fetch", String(url))) {
            notifySafetyBlock("fetch", String(url));
            return Promise.reject(new Error("串行测试门禁已阻止当前订单的额外保存请求 /coHandle"));
          }
          return originalFetch.call(this, input, init).then(async (response) => {
            let body = "";
            try { body = await response.clone().text(); } catch { /* classified as ambiguous below */ }
            settleObservedSave(classifySaveResponse(response.status, body, "fetch"));
            return response;
          }, (error) => {
            settleObservedSave(ambiguousSaveResult(`保存请求发生网络错误：${errorMessage(error)}`, "fetch"));
            throw error;
          });
        }
        return originalFetch.call(this, input, init);
      };
    }

    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function guardedOpen(method, url, ...rest) {
      this.__cmMultifillUrl = String(url || "");
      return originalOpen.call(this, method, url, ...rest);
    };
    XMLHttpRequest.prototype.send = function guardedSend(body) {
      if (BLOCKED_REQUEST.test(this.__cmMultifillUrl || "")) {
        if (!consumeSaveRequestPermit("xhr", this.__cmMultifillUrl)) {
          notifySafetyBlock("xhr", this.__cmMultifillUrl);
          throw new Error("串行测试门禁已阻止当前订单的额外保存请求 /coHandle");
        }
        this.addEventListener("loadend", () => {
          let responseText = "";
          try { responseText = typeof this.responseText === "string" ? this.responseText : ""; } catch { /* classified below */ }
          if (!this.status) {
            settleObservedSave(ambiguousSaveResult("保存请求已发出，但没有收到可确认的响应", "xhr"));
          } else {
            settleObservedSave(classifySaveResponse(this.status, responseText, "xhr"));
          }
        }, { once: true });
      }
      return originalSend.call(this, body);
    };

    document.addEventListener("click", (event) => {
      const clickable = event.target?.closest?.("button,a,[role='button'],.el-button,.ant-btn");
      if (!clickable || clickable.closest(`#${PANEL_ID}`)) return;
      const text = normalizeText(clickable.textContent);
      if (SAVE_BUTTON_TEXT.test(text)) {
        if (state.saveClickPermit) {
          state.saveClickPermit = false;
          return;
        }
        event.preventDefault();
        event.stopImmediatePropagation();
        notifySafetyBlock("click", text);
      }
    }, true);

    document.addEventListener("keydown", (event) => {
      if (event.key === "F9" || event.code === "F9") {
        event.preventDefault();
        event.stopImmediatePropagation();
        notifySafetyBlock("keyboard", "F9");
      }
    }, true);
  }

  function consumeSaveRequestPermit(channel, url) {
    if (!state.saveRequestPermit || !state.saveResolver) return false;
    state.saveRequestPermit = false;
    state.saveRequestSeen = true;
    if (state.ui) setStatus(`保存请求已发出（${channel}），正在等待网站结果…`, "warning");
    return true;
  }

  function armSingleSaveObservation() {
    if (!state.batchStarted) throw new Error("批次尚未开始，禁止保存");
    if (state.saveInFlight || state.saveResolver) throw new Error("上一条订单仍在等待保存结果，禁止并发保存");
    state.saveInFlight = true;
    state.saveAttempts += 1;
    state.saveClickPermit = true;
    state.saveRequestPermit = true;
    state.saveRequestSeen = false;
    return new Promise((resolve) => {
      state.saveResolver = resolve;
      state.saveTimer = setTimeout(() => {
        if (state.saveRequestSeen) {
          settleObservedSave(ambiguousSaveResult("保存请求已发出，但等待网站响应超时；请人工查询是否已经生成订单", "timeout"));
        } else {
          settleObservedSave({
            state: "save_failed",
            error: "点击保存后 15 秒内未检测到保存请求；可能被网站页面校验阻止",
            save_channel: "none",
            save_http_status: "",
            save_errno: "",
            save_message: "未发出保存请求",
          });
        }
      }, 15000);
    });
  }

  function settleObservedSave(result) {
    if (!state.saveResolver) return;
    clearTimeout(state.saveTimer);
    const resolve = state.saveResolver;
    state.saveResolver = null;
    state.saveTimer = null;
    state.saveClickPermit = false;
    state.saveRequestPermit = false;
    state.saveInFlight = false;
    resolve(result);
  }

  function classifySaveResponse(httpStatus, text, channel) {
    const base = { save_channel: channel, save_http_status: httpStatus, save_errno: "", save_message: "" };
    if (httpStatus < 200 || httpStatus >= 300) {
      return { ...ambiguousSaveResult(`保存接口返回 HTTP ${httpStatus}；请人工查询订单是否已经生成`, channel), save_http_status: httpStatus };
    }
    let payload;
    try { payload = JSON.parse(text); } catch {
      return { ...ambiguousSaveResult("保存接口返回了无法解析的内容；请人工查询订单是否已经生成", channel), save_http_status: httpStatus };
    }
    const errno = payload?.errno;
    const message = String(payload?.errmsg || "");
    base.save_errno = errno ?? "";
    base.save_message = message;
    if (Number(errno) === 0) return { ...base, state: "saved", error: "" };
    if (Number(errno) === 320) {
      return { ...base, state: "decision_required", error: message || "网站要求人工确认是否继续保存；测试版未自动继续" };
    }
    return { ...base, state: "save_failed", error: message || `网站拒绝保存（errno=${errno ?? "未知"}）` };
  }

  function ambiguousSaveResult(message, channel) {
    return {
      state: "save_ambiguous",
      error: message,
      save_channel: channel,
      save_http_status: "",
      save_errno: "",
      save_message: "结果不确定，禁止自动重试",
    };
  }

  function notifySafetyBlock(channel, detail) {
    state.safetyBlocks += 1;
    window.dispatchEvent(new CustomEvent("cm-multifill-safety-block", { detail: { channel, detail } }));
    if (state.ui) {
      setStatus(`已阻止保存操作（${channel}）。本轮累计 ${state.safetyBlocks} 次。`, "warning");
      renderSummary();
    }
  }

  function initializeUi() {
    if (document.getElementById(PANEL_ID)) return;
    const host = document.createElement("div");
    host.id = PANEL_ID;
    const shadow = host.attachShadow({ mode: "open" });
    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        .panel { position: fixed; right: 16px; bottom: 16px; width: 440px; max-height: 78vh; z-index: 2147483647;
          color: #172033; background: #fff; border: 1px solid #9db4cc; border-radius: 10px; box-shadow: 0 12px 32px rgba(10,36,64,.28);
          font: 13px/1.45 "Microsoft YaHei", Arial, sans-serif; overflow: hidden; }
        .head { display:flex; align-items:center; justify-content:space-between; padding:10px 12px; background:#17365d; color:#fff; }
        .head strong { font-size:14px; } .head small { opacity:.8; }
        .body { padding:12px; overflow:auto; max-height:calc(78vh - 44px); }
        .safety { background:#fff2cc; color:#7f6000; border:1px solid #e6cf7a; border-radius:6px; padding:8px; margin-bottom:10px; }
        .row { display:flex; gap:8px; align-items:center; margin:8px 0; flex-wrap:wrap; }
        input[type=file] { width:100%; font-size:12px; }
        button { border:0; border-radius:6px; padding:8px 10px; cursor:pointer; font:inherit; }
        button.primary { background:#2f75b5; color:white; } button.secondary { background:#e7eef6; color:#17365d; }
        button:disabled { opacity:.45; cursor:not-allowed; }
        .status { padding:8px; border-radius:6px; background:#eef5fb; margin:8px 0; white-space:pre-wrap; }
        .status.error { background:#fde8e8; color:#991b1b; } .status.warning { background:#fff2cc; color:#7f6000; }
        .stats { display:grid; grid-template-columns:repeat(4,1fr); gap:6px; margin:8px 0; }
        .stat { background:#f4f7fa; border-radius:6px; padding:6px; text-align:center; }
        .stat b { display:block; font-size:16px; color:#17365d; }
        table { width:100%; border-collapse:collapse; font-size:11px; table-layout:fixed; }
        th { text-align:left; color:#fff; background:#2f75b5; padding:5px; }
        td { padding:5px; border-bottom:1px solid #dbe5ee; word-break:break-all; }
        tr.failed td { background:#fde8e8; } tr.filled td { background:#e8f5e9; }
        details { margin-top:8px; } summary { cursor:pointer; color:#245b8f; }
        .errors { color:#991b1b; white-space:pre-wrap; max-height:130px; overflow:auto; }
      </style>
      <section class="panel">
        <div class="head"><strong>多运单填表初测</strong><small>v${SCRIPT_VERSION}</small></div>
        <div class="body">
          <div class="safety"><strong>串行真实保存测试：</strong>本脚本将逐条创建、填写并保存。上一条明确结束后才处理下一条。</div>
          <input id="file" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet">
          <label class="row"><input id="confirm" type="checkbox"> 我确认文件中全部是获准保存的虚构测试订单</label>
          <div class="row">
            <button id="fill" class="primary" disabled>开始串行创建、填写并保存</button>
            <button id="export" class="secondary" disabled>导出核对报告</button>
          </div>
          <div id="status" class="status">请选择配套测试 Excel。</div>
          <div id="stats" class="stats"></div>
          <table><thead><tr><th>记录</th><th>场景</th><th>状态</th><th>页签</th></tr></thead><tbody id="rows"></tbody></table>
          <details><summary>导入/映射问题</summary><div id="errors" class="errors">无</div></details>
        </div>
      </section>`;
    document.documentElement.appendChild(host);

    state.ui = {
      host,
      file: shadow.getElementById("file"),
      confirm: shadow.getElementById("confirm"),
      fill: shadow.getElementById("fill"),
      export: shadow.getElementById("export"),
      status: shadow.getElementById("status"),
      stats: shadow.getElementById("stats"),
      rows: shadow.getElementById("rows"),
      errors: shadow.getElementById("errors"),
    };
    state.ui.file.addEventListener("change", handleFileSelection);
    state.ui.confirm.addEventListener("change", renderSummary);
    state.ui.fill.addEventListener("click", fillNextBatch);
    state.ui.export.addEventListener("click", exportReportCsv);
    if (window.__CMMultiFillTest) {
      state.importErrors = [LEGACY_CONFLICT_MESSAGE];
      setStatus(state.importErrors[0], "error");
    }
    renderSummary();
  }

  async function handleFileSelection(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (window.__CMMultiFillTest) {
      state.importErrors = [LEGACY_CONFLICT_MESSAGE];
      setStatus(LEGACY_CONFLICT_MESSAGE, "error");
      renderSummary();
      return;
    }
    resetRunState();
    state.fileName = file.name;
    setStatus("正在解析 Excel…");
    try {
      const parsed = await parseXlsxArrayBuffer(await file.arrayBuffer(), "导入数据");
      const validation = validateOrders(parsed);
      state.orders = validation.orders;
      state.importErrors = validation.errors;
      if (validation.errors.length) {
        setStatus(`导入被阻断：发现 ${validation.errors.length} 个问题。`, "error");
      } else {
        setStatus(`已导入 ${state.orders.length} 条订单。勾选确认框后，脚本将逐条创建、填写并真实保存。`, "warning");
      }
    } catch (error) {
      state.importErrors = [errorMessage(error)];
      setStatus(`Excel 解析失败：${errorMessage(error)}`, "error");
    }
    renderSummary();
  }

  function resetRunState() {
    state.orders = [];
    state.cursor = 0;
    state.results = [];
    state.importErrors = [];
    state.busy = false;
    if (state.ui?.confirm) state.ui.confirm.checked = false;
  }

  function validateOrders(parsed) {
    const errors = [...parsed.errors];
    const orders = [];
    const seen = new Set();
    const batchIds = new Set();

    for (const row of parsed.rows) {
      const label = `第 ${row.__rowNumber} 行`;
      const order = { ...row };
      delete order.__rowNumber;
      delete order.__formulaFields;

      if (row.__formulaFields.length) errors.push(`${label}：导入数据不允许公式单元格（${row.__formulaFields.join("、")}）`);
      for (const key of REQUIRED_HEADERS) {
        if (typeof order[key] === "string" && /^(null|undefined)$/i.test(order[key].trim())) {
          errors.push(`${label} ${key}：不得使用字符串 ${JSON.stringify(order[key])} 表示空值`);
        }
      }
      if (String(order.schema_version || "").trim() !== "v1.0") errors.push(`${label} schema_version：必须为 v1.0`);
      const id = String(order.source_record_id || "").trim();
      if (!id) errors.push(`${label} source_record_id：不能为空`);
      if (seen.has(id)) errors.push(`${label} source_record_id：${id} 重复`);
      seen.add(id);
      if (!String(order.batch_id || "").trim()) errors.push(`${label} batch_id：不能为空`);
      batchIds.add(String(order.batch_id || "").trim());
      for (const key of ["destination_text", "sender_name", "receiver_name", "goods_name"]) {
        if (!String(order[key] ?? "").trim()) errors.push(`${label} ${key}：不能为空`);
      }
      if (!["delivery", "pickup"].includes(String(order.delivery_type))) errors.push(`${label} delivery_type：仅支持 delivery/pickup`);
      if (String(order.payment_type) !== "pay_billing") errors.push(`${label} payment_type：本轮仅支持 pay_billing`);
      const quantity = toNumber(order.quantity);
      if (!Number.isInteger(quantity) || quantity <= 0) errors.push(`${label} quantity：必须为正整数`);
      order.quantity = quantity;
      for (const key of ["weight", "volume"]) {
        if (isBlank(order[key])) order[key] = "";
        else {
          const value = toNumber(order[key]);
          if (!Number.isFinite(value) || value < 0) errors.push(`${label} ${key}：必须为空或非负数`);
          order[key] = value;
        }
      }
      const freight = toNumber(order.freight);
      if (!Number.isFinite(freight) || freight < 0 || !hasAtMostTwoDecimals(freight)) errors.push(`${label} freight：必须为非负数且最多两位小数`);
      order.freight = freight;
      order.receiver_mobile = isBlank(order.receiver_mobile) ? "" : String(order.receiver_mobile).trim();
      order.package = isBlank(order.package) ? "" : String(order.package).trim();
      for (const key of ["schema_version", "batch_id", "source_record_id", "source_label", "destination_text", "delivery_type", "sender_name", "receiver_name", "goods_name", "payment_type"]) {
        order[key] = String(order[key] ?? "").trim();
      }
      orders.push(order);
    }
    if (batchIds.size > 1) errors.push("同一文件中的 batch_id 必须一致");
    if (!orders.length) errors.push("导入数据中没有订单记录");
    if (orders.length > MAX_TEST_ORDERS) errors.push(`串行批量初测最多允许 ${MAX_TEST_ORDERS} 条订单，当前为 ${orders.length} 条`);
    return { orders, errors: [...new Set(errors)] };
  }

  async function fillNextBatch() {
    if (state.busy || state.batchStarted || state.importErrors.length || state.cursor >= state.orders.length) return;
    if (!state.ui.confirm.checked) {
      setStatus("请先确认文件中全部是获准保存的虚构测试订单。", "error");
      return;
    }
    state.batchStarted = true;
    state.busy = true;
    renderSummary();
    const batch = state.orders.slice(state.cursor);
    let queueStopped = false;

    for (let index = 0; index < batch.length; index += 1) {
      const order = batch[index];
      setStatus(`正在处理 ${index + 1}/${batch.length}：${order.source_record_id}；上一条已结束，本条即将创建、填写并保存…`, "warning");
      const result = {
        source_record_id: order.source_record_id,
        source_label: order.source_label,
        state: "filling",
        internal_tab: "",
        started_at: new Date().toISOString(),
        completed_at: "",
        fields: [],
        error: "",
        save_channel: "",
        save_http_status: "",
        save_errno: "",
        save_message: "",
      };
      state.results.push(result);
      renderSummary();
      try {
        await openNewOrderTab();
        result.internal_tab = detectActiveInternalTab();
        result.fields = await fillOrder(order);
        const failures = result.fields.filter((field) => field.status !== "verified");
        if (failures.length) {
          result.state = "mapping_failed";
          result.error = failures.map((field) => `${field.key}: ${field.error}`).join("；");
        } else {
          result.state = "ready_to_save";
          renderSummary();
          const saveResult = await saveFilledOrder();
          Object.assign(result, saveResult);
        }
      } catch (error) {
        result.state = result.state === "ready_to_save" ? (state.saveRequestSeen ? "save_ambiguous" : "save_failed") : "mapping_failed";
        result.error = errorMessage(error);
      }
      result.completed_at = new Date().toISOString();
      state.cursor += 1;
      renderSummary();
      if (shouldStopSerialQueue(result.state)) {
        queueStopped = true;
        break;
      }
      if (result.state === "saved") await sleep(800);
    }

    state.busy = false;
    const savedCount = state.results.filter((item) => item.state === "saved").length;
    const failedCount = state.results.filter((item) => ["mapping_failed", "save_failed"].includes(item.state)).length;
    const pendingCount = state.orders.length - state.cursor;
    if (queueStopped) {
      const finalResult = state.results[state.results.length - 1];
      const reason = finalResult.state === "decision_required"
        ? "网站要求人工决定是否继续保存"
        : "保存结果不确定，必须先人工查询是否已生成订单";
      setStatus(`队列已停止：${reason}。已保存 ${savedCount} 条，普通失败 ${failedCount} 条，未处理 ${pendingCount} 条。`, "warning");
    } else if (failedCount) {
      setStatus(`串行批次执行完毕：保存成功 ${savedCount} 条，填写或保存失败 ${failedCount} 条。请导出报告并人工核对。`, "warning");
    } else {
      setStatus(`串行批次执行完毕：${savedCount}/${state.orders.length} 条均返回保存成功。请到订单列表人工核对。`);
    }
    renderSummary();
  }

  function shouldStopSerialQueue(resultState) {
    return resultState === "decision_required" || resultState === "save_ambiguous";
  }

  async function saveFilledOrder() {
    state.saveRequestSeen = false;
    const action = findUniqueSaveAction();
    if (!action) throw new Error("未找到唯一可见的“保存(F9)”按钮；未发出保存请求");
    const observed = armSingleSaveObservation();
    try {
      action.click();
    } catch (error) {
      settleObservedSave({
        state: "save_failed",
        error: `触发保存按钮失败：${errorMessage(error)}`,
        save_channel: "none",
        save_http_status: "",
        save_errno: "",
        save_message: "未发出保存请求",
      });
    }
    return observed;
  }

  function findUniqueSaveAction() {
    const candidates = [...document.querySelectorAll("button,a,[role='button'],.el-button,.ant-btn")]
      .filter((element) => !element.closest(`#${PANEL_ID}`) && isVisible(element) && SAVE_BUTTON_TEXT.test(normalizeText(element.textContent)));
    const unique = [...new Set(candidates.map((element) => element.closest("button,a,[role='button'],.el-button,.ant-btn") || element))];
    return unique.length === 1 ? unique[0] : null;
  }

  async function openNewOrderTab() {
    const action = findUniqueAction(["创建运单", "新建运单"]);
    if (!action) throw new Error("未找到唯一的“创建运单”按钮；未执行猜测点击");
    action.click();
    await waitFor(() => hasVisibleFormMarkers(), 15000, "等待开单表单出现超时");
    await sleep(500);
  }

  function findUniqueAction(texts) {
    const candidates = [...document.querySelectorAll("button,a,[role='button'],.el-button,.ant-btn")]
      .filter((element) => !element.closest(`#${PANEL_ID}`) && isVisible(element) && texts.includes(normalizeText(element.textContent)));
    const unique = [...new Set(candidates.map((element) => element.closest("button,a,[role='button'],.el-button,.ant-btn") || element))];
    return unique.length === 1 ? unique[0] : null;
  }

  function hasVisibleFormMarkers() {
    const text = normalizeText(document.body?.innerText || "");
    return ["发货人", "收货人", "货物", "运费"].filter((marker) => text.includes(marker)).length >= 3;
  }

  function detectActiveInternalTab() {
    const selectors = ["[role='tab'][aria-selected='true']", ".el-tabs__item.is-active", ".ant-tabs-tab-active", ".is-active[role='tab']"];
    for (const selector of selectors) {
      const element = [...document.querySelectorAll(selector)].find((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item));
      if (element) return normalizeText(element.textContent).slice(0, 80);
    }
    return `${location.pathname}${location.search}`;
  }

  async function fillOrder(order) {
    const results = [];
    for (const mapping of FIELD_MAPPINGS) {
      const expected = mapping.display ? mapping.display[order[mapping.key]] : order[mapping.key];
      const result = { key: mapping.key, label: mapping.labels[0], expected: displayValue(expected), status: "pending", error: "" };
      results.push(result);
      try {
        const located = locateField(mapping);
        if (!located) throw new Error(`未找到唯一控件（标签：${mapping.labels.join("/")}）`);
        result.status = "located";
        if (mapping.kind === "choice") {
          await chooseValue(located, expected, mapping);
        } else if (mapping.kind === "autocomplete") {
          await fillAutocomplete(located.control, expected);
        } else {
          await setControlValue(located.control, expected);
          if (mapping.suggestionOptional && !isBlank(expected)) await chooseVisibleExactOption(String(expected), 500, true);
        }
        result.status = "written";
        await sleep(180);
        if ((mapping.kind === "autocomplete" || mapping.kind === "choice") && located.control.getAttribute("data-is-select") !== "1") {
          throw new Error("下拉值未被网站确认（data-is-select 不等于 1）");
        }
        if (!verifyField(located, expected, mapping)) throw new Error(`回读不一致，期望 ${JSON.stringify(displayValue(expected))}`);
        result.status = "verified";
      } catch (error) {
        result.status = "failed";
        result.error = errorMessage(error);
        break;
      }
    }
    return results;
  }

  function locateField(mapping) {
    if (mapping.dataPath) {
      const pathLocated = locateDataPathField(mapping.dataPath);
      if (pathLocated) return pathLocated;
    }
    if (mapping.tableField) {
      const tableLocated = locateTableField(mapping.labels);
      if (tableLocated) return tableLocated;
    }
    const matches = [];
    const labelSelector = "label,.el-form-item__label,.ant-form-item-label,th,td,[aria-label],span,div";
    for (const label of document.querySelectorAll(labelSelector)) {
      if (label.closest(`#${PANEL_ID}`) || !isVisible(label)) continue;
      const text = normalizeText(label.getAttribute("aria-label") || label.textContent);
      if (!mapping.labels.includes(text)) continue;
      const located = controlNearLabel(label);
      if (located) matches.push(located);
    }
    const uniqueControls = [...new Map(matches.map((item) => [item.control, item])).values()];
    return uniqueControls.length === 1 ? uniqueControls[0] : null;
  }

  function locateDataPathField(dataPath) {
    const matches = [...document.querySelectorAll(`[data-path="${CSS.escape(dataPath)}"]`)]
      .filter((control) => !control.closest(`#${PANEL_ID}`) && isVisible(control));
    if (matches.length !== 1) return null;
    const control = matches[0];
    const scope = control.closest(".fn-field,.form-item,.el-form-item,.ant-form-item,td,section,form") || control.parentElement;
    return { control, scope, label: null };
  }

  function locateTableField(labels) {
    const matches = [];
    for (const header of document.querySelectorAll("th")) {
      if (header.closest(`#${PANEL_ID}`) || !isVisible(header) || !labels.includes(normalizeText(header.textContent))) continue;
      const table = header.closest("table");
      if (!table) continue;
      const headers = [...header.parentElement.children];
      const index = headers.indexOf(header);
      for (const row of table.querySelectorAll("tbody tr")) {
        if (!isVisible(row)) continue;
        const cell = row.children[index];
        const control = cell && visibleControls(cell)[0];
        if (control) {
          matches.push({ control, scope: cell, label: header });
          break;
        }
      }
    }
    const unique = [...new Map(matches.map((item) => [item.control, item])).values()];
    return unique.length === 1 ? unique[0] : null;
  }

  function controlNearLabel(label) {
    const targetId = label.getAttribute("for");
    if (targetId) {
      const linked = document.getElementById(targetId);
      if (linked && isVisible(linked)) return { control: linked, scope: linked.closest(".el-form-item,.ant-form-item") || linked.parentElement, label };
    }
    let scope = label;
    for (let depth = 0; scope && depth < 6; depth += 1, scope = scope.parentElement) {
      const controls = visibleControls(scope);
      if (controls.length === 1) return { control: controls[0], scope, label };
      if (controls.length > 1 && depth >= 2) break;
    }
    return null;
  }

  function visibleControls(scope) {
    return [...scope.querySelectorAll("input:not([type='hidden']),textarea,select,[contenteditable='true']")]
      .filter((control) => !control.closest(`#${PANEL_ID}`) && isVisible(control));
  }

  async function setControlValue(control, value, options = {}) {
    const text = isBlank(value) ? "" : String(value);
    control.focus();
    if (control.isContentEditable) {
      control.textContent = text;
    } else {
      const prototype = control instanceof HTMLInputElement ? HTMLInputElement.prototype
        : control instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
          : control instanceof HTMLSelectElement ? HTMLSelectElement.prototype : null;
      const setter = prototype && Object.getOwnPropertyDescriptor(prototype, "value")?.set;
      if (setter) setter.call(control, text);
      else control.value = text;
    }
    for (const type of ["input", "change"]) control.dispatchEvent(new Event(type, { bubbles: true }));
    if (options.blur !== false) control.blur();
  }

  async function fillAutocomplete(control, value) {
    const visibleBeforeInput = new Set(
      [...document.body.querySelectorAll("*")].filter((element) => isVisible(element)),
    );
    await setControlValue(control, value, { blur: false });
    if (isBlank(value)) return;
    const selection = await chooseNewExactOption(String(value), visibleBeforeInput, 4000);
    if (!selection) throw new Error(`未找到唯一的精确候选：${value}`);
    try {
      await waitFor(
        () => control.getAttribute("data-is-select") === "1"
          && normalizeText(control.value || control.title) === normalizeText(value),
        3000,
        `候选已点击，但网站未确认选择：${value}`,
      );
    } catch (error) {
      throw new Error(`${errorMessage(error)}；点击目标诊断：${selection.diagnostic}`);
    }
  }

  async function chooseNewExactOption(value, visibleBeforeInput, timeoutMs) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const exactNewElements = [...document.body.querySelectorAll("*")].filter((element) => {
        if (element.closest(`#${PANEL_ID}`) || visibleBeforeInput.has(element) || !isVisible(element)) return false;
        return normalizeText(element.textContent) === normalizeText(value);
      });
      const deepestMatches = exactNewElements.filter((element) => ![...element.children].some(
        (child) => isVisible(child) && normalizeText(child.textContent) === normalizeText(value),
      ));
      const clickTargets = [...new Set(deepestMatches.map((element) => (
        element.closest("[role='option'],li,button,a,[data-value],[data-id],[class*='option'],[class*='item']") || element
      )))];
      if (clickTargets.length === 1) {
        const target = clickTargets[0];
        const diagnostic = describeClickTarget(target);
        dispatchUserLikeClick(target);
        return { diagnostic };
      }
      if (clickTargets.length > 1) throw new Error(`精确候选“${value}”出现 ${clickTargets.length} 次，已停止避免误选`);
      await sleep(100);
    }
    const diagnostics = collectCandidateDiagnostics(value, visibleBeforeInput);
    if (diagnostics.length) {
      throw new Error(`未找到唯一的精确候选：${value}；候选诊断：${diagnostics.join(" || ")}`);
    }
    return false;
  }

  function dispatchUserLikeClick(target) {
    target.scrollIntoView({ block: "nearest", inline: "nearest" });
    const mouseOptions = { bubbles: true, cancelable: true, composed: true, view: window, button: 0, buttons: 1 };
    if (typeof PointerEvent === "function") {
      target.dispatchEvent(new PointerEvent("pointerover", { ...mouseOptions, pointerId: 1, pointerType: "mouse", isPrimary: true }));
      target.dispatchEvent(new PointerEvent("pointerdown", { ...mouseOptions, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    }
    target.dispatchEvent(new MouseEvent("mouseover", mouseOptions));
    target.dispatchEvent(new MouseEvent("mousedown", mouseOptions));
    target.dispatchEvent(new MouseEvent("mouseup", { ...mouseOptions, buttons: 0 }));
    if (typeof PointerEvent === "function") {
      target.dispatchEvent(new PointerEvent("pointerup", { ...mouseOptions, buttons: 0, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    }
    target.dispatchEvent(new MouseEvent("click", { ...mouseOptions, buttons: 0 }));
  }

  function describeClickTarget(target) {
    const parts = [];
    let current = target;
    for (let depth = 0; current && depth < 4; depth += 1, current = current.parentElement) {
      if (current === document.body || current === document.documentElement) break;
      const html = current.outerHTML.replace(/\s+/g, " ").slice(0, 420);
      parts.push(`L${depth}:${html}`);
    }
    return parts.join(" <- ");
  }

  function collectCandidateDiagnostics(value, visibleBeforeInput) {
    const target = normalizeText(value);
    const matches = [...document.body.querySelectorAll("*")].filter((element) => {
      if (element.closest(`#${PANEL_ID}`) || !isVisible(element)) return false;
      const text = normalizeText(element.textContent);
      return text && text.length <= 160 && text.includes(target);
    });
    const deepest = matches.filter((element) => ![...element.children].some((child) => {
      const childText = normalizeText(child.textContent);
      return isVisible(child) && childText && childText.includes(target);
    }));
    return deepest.slice(0, 8).map((element) => {
      const freshness = visibleBeforeInput.has(element) ? "existing" : "new";
      const text = normalizeText(element.textContent).slice(0, 120);
      const html = element.outerHTML.replace(/\s+/g, " ").slice(0, 360);
      return `[${freshness}] text=${JSON.stringify(text)} html=${html}`;
    });
  }

  async function chooseValue(located, display, mapping) {
    if (!display) throw new Error(`未定义枚举映射：${mapping.key}`);
    const localChoices = [...located.scope.querySelectorAll("label,button,[role='radio'],[role='option'],.el-radio,.el-checkbox,.el-select-dropdown__item")]
      .filter((element) => isVisible(element) && normalizeText(element.textContent) === String(display));
    const uniqueLocal = dedupeNestedChoices(localChoices);
    if (uniqueLocal.length === 1) {
      uniqueLocal[0].click();
      return;
    }
    const visibleBeforeOpen = new Set(
      [...document.body.querySelectorAll("*")].filter((element) => isVisible(element)),
    );
    located.control.click();
    const selection = await chooseNewExactOption(String(display), visibleBeforeOpen, 3000);
    if (!selection) throw new Error(`未找到唯一枚举选项：${display}`);
    try {
      await waitFor(
        () => located.control.getAttribute("data-is-select") === "1"
          && normalizeText(located.control.value || located.control.title) === normalizeText(display),
        2500,
        `枚举候选已点击，但网站未确认选择：${display}`,
      );
    } catch (error) {
      throw new Error(`${errorMessage(error)}；点击目标诊断：${selection.diagnostic}`);
    }
  }

  function dedupeNestedChoices(elements) {
    return elements.filter((element) => !elements.some((other) => other !== element && other.contains(element) && normalizeText(other.textContent) === normalizeText(element.textContent)));
  }

  async function chooseVisibleExactOption(value, timeoutMs, optional) {
    const selector = "[role='option'],.el-autocomplete-suggestion li,.el-select-dropdown__item,.ant-select-item-option,li";
    const find = () => {
      const matches = [...document.querySelectorAll(selector)]
        .filter((element) => !element.closest(`#${PANEL_ID}`) && isVisible(element) && normalizeText(element.textContent) === normalizeText(value));
      return [...new Set(matches)];
    };
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const matches = find();
      if (matches.length === 1) {
        matches[0].click();
        return true;
      }
      if (matches.length > 1) throw new Error(`候选文本“${value}”出现 ${matches.length} 次，已停止避免误选`);
      await sleep(100);
    }
    return optional ? false : false;
  }

  function verifyField(located, expected, mapping) {
    if (mapping.kind === "choice") {
      const display = String(expected);
      const checked = [...located.scope.querySelectorAll("input:checked,[aria-checked='true'],.is-checked,.is-active,.selected")]
        .some((element) => normalizeText(element.closest("label,[role='radio'],.el-radio,.el-form-item")?.textContent || element.textContent).includes(display));
      const controlValue = readControlValue(located.control);
      return checked || normalizeText(controlValue).includes(normalizeText(display));
    }
    const actual = readControlValue(located.control);
    if (mapping.kind === "number" && !isBlank(expected)) return Number(actual) === Number(expected);
    return normalizeText(actual) === normalizeText(isBlank(expected) ? "" : String(expected));
  }

  function readControlValue(control) {
    return control.isContentEditable ? control.textContent || "" : control.value ?? control.getAttribute("value") ?? "";
  }

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
    const saved = state.results.filter((item) => item.state === "saved").length;
    const failed = state.results.filter((item) => ["mapping_failed", "save_failed", "decision_required", "save_ambiguous"].includes(item.state)).length;
    const verifiedFields = state.results.flatMap((item) => item.fields).filter((item) => item.status === "verified").length;
    const fieldCount = state.results.flatMap((item) => item.fields).length;
    state.ui.stats.innerHTML = [
      ["导入", state.orders.length], ["已保存", saved], ["异常", failed], ["额外保存拦截", state.safetyBlocks],
    ].map(([label, value]) => `<div class="stat"><b>${value}</b>${label}</div>`).join("");
    const combined = state.orders.map((order) => {
      const result = state.results.find((item) => item.source_record_id === order.source_record_id);
      const status = result?.state || "pending";
      const display = { pending: "待处理", filling: "填写中", ready_to_save: "待保存", saved: "保存成功", mapping_failed: "填写失败", save_failed: "保存失败", decision_required: "需人工决定", save_ambiguous: "结果不确定" }[status] || status;
      const success = status === "saved";
      const abnormal = ["mapping_failed", "save_failed", "decision_required", "save_ambiguous"].includes(status);
      return `<tr class="${success ? "filled" : abnormal ? "failed" : ""}"><td>${escapeHtml(order.source_record_id)}</td><td>${escapeHtml(order.source_label)}</td><td>${display}</td><td>${escapeHtml(result?.internal_tab || "-")}</td></tr>`;
    }).join("");
    state.ui.rows.innerHTML = combined || `<tr><td colspan="4">尚未导入</td></tr>`;
    const problems = [
      ...state.importErrors,
      ...state.results.filter((item) => item.error).map((item) => `${item.source_record_id}：${item.error}`),
    ];
    state.ui.errors.textContent = problems.length ? problems.join("\n") : `无。已验证字段 ${verifiedFields}/${fieldCount || 0}`;
    state.ui.fill.disabled = state.busy || state.batchStarted || state.importErrors.length > 0 || !state.orders.length || state.cursor >= state.orders.length || !state.ui.confirm.checked;
    state.ui.confirm.disabled = state.busy || state.batchStarted;
    state.ui.file.disabled = state.busy || state.batchStarted;
    state.ui.export.disabled = state.results.length === 0;
  }

  function exportReportCsv() {
    const header = ["source_record_id", "source_label", "state", "internal_tab", "field", "expected", "field_status", "error", "save_channel", "save_http_status", "save_errno", "save_message", "started_at", "completed_at"];
    const lines = [header];
    for (const result of state.results) {
      if (!result.fields.length) {
        lines.push([result.source_record_id, result.source_label, result.state, result.internal_tab, "", "", "", result.error, result.save_channel, result.save_http_status, result.save_errno, result.save_message, result.started_at, result.completed_at]);
      } else {
        for (const field of result.fields) {
          lines.push([result.source_record_id, result.source_label, result.state, result.internal_tab, field.key, field.expected, field.status, field.error || result.error, result.save_channel, result.save_http_status, result.save_errno, result.save_message, result.started_at, result.completed_at]);
        }
      }
    }
    const csv = `\uFEFF${lines.map((row) => row.map(csvCell).join(",")).join("\r\n")}`;
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    link.download = `串行批量自动保存测试报告-${new Date().toISOString().replace(/[:.]/g, "-")}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function setStatus(message, type = "") {
    if (!state.ui) return;
    state.ui.status.className = `status${type ? ` ${type}` : ""}`;
    state.ui.status.textContent = message;
  }

  function waitFor(predicate, timeoutMs, timeoutMessage) {
    return new Promise((resolve, reject) => {
      const start = Date.now();
      const tick = () => {
        try { if (predicate()) return resolve(); } catch { /* keep waiting */ }
        if (Date.now() - start >= timeoutMs) return reject(new Error(timeoutMessage));
        setTimeout(tick, 120);
      };
      tick();
    });
  }

  function isVisible(element) {
    if (!(element instanceof Element) || !element.isConnected) return false;
    const style = getComputedStyle(element);
    return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0" && element.getClientRects().length > 0 && element.getAttribute("aria-hidden") !== "true";
  }
  function normalizeText(value) { return String(value ?? "").replace(/\s+/g, "").replace(/[：:]+$/, "").trim(); }
  function isBlank(value) { return value === "" || value === null || value === undefined; }
  function displayValue(value) { return isBlank(value) ? "" : String(value); }
  function toNumber(value) { return isBlank(value) ? Number.NaN : Number(value); }
  function hasAtMostTwoDecimals(value) { return Math.abs(value * 100 - Math.round(value * 100)) < 1e-8; }
  function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
  function errorMessage(error) { return error instanceof Error ? error.message : String(error); }
  function csvCell(value) { const text = String(value ?? ""); return `"${text.replace(/"/g, '""')}"`; }
  function escapeHtml(value) { return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char])); }

  window.__CMSerialSaveTest = Object.freeze({
    version: SCRIPT_VERSION,
    parseXlsxArrayBuffer,
    validateOrders,
    classifySaveResponse,
    shouldStopSerialQueue,
    maxTestOrders: MAX_TEST_ORDERS,
    fieldMappings: FIELD_MAPPINGS,
    state,
  });
})();
