// ==UserScript==
// @name         车满满单页面内部页签并行最终探针
// @namespace    codex.chemanman.internal-tab-probe
// @version      0.3.0
// @description  自动验证双内部页签完整字段、隐藏表单控件及重叠保存；经确认后真实保存两条虚构订单。
// @author       User
// @match        https://t800.chemanman.com/Order*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  "use strict";

  const SCRIPT_VERSION = "0.3.0";
  const PANEL_ID = "cm-internal-parallel-probe-host";
  const BLOCKED_REQUEST = /\/api\/Order\/Order\/coHandle(?:\/|\?|$)/i;
  const SAVE_BUTTON_TEXT = /^(保存(?:\(F9\))?|保存并打印|保存并关闭|提交运单|继续保存)$/i;
  const CREATE_TEXTS = ["创建运单", "新建运单"];
  const PROBE_PATHS = [
    "arr", "delivery_mode", "cor_name", "cee_name", "cee_mobile", "name_1",
    "pkg_1", "num_1", "weight_1", "volume_1", "co_freight_f", "pay_mode",
  ];
  const MARKER_A_ACTIVE = `并行探针A-${Date.now().toString(36)}`;
  const MARKER_A_HIDDEN = `${MARKER_A_ACTIVE}-隐藏写入`;
  const MARKER_B_ACTIVE = `并行探针B-${Date.now().toString(36)}`;
  const FIELD_MAPPINGS = [
    { key: "destination_text", dataPath: "arr", kind: "autocomplete" },
    { key: "delivery_type", dataPath: "delivery_mode", kind: "choice", display: { delivery: "送货", pickup: "自提" } },
    { key: "sender_name", dataPath: "cor_name", kind: "text" },
    { key: "receiver_name", dataPath: "cee_name", kind: "text" },
    { key: "receiver_mobile", dataPath: "cee_mobile", kind: "text" },
    { key: "goods_name", dataPath: "name_1", kind: "text" },
    { key: "package", dataPath: "pkg_1", kind: "text", suggestionOptional: true },
    { key: "quantity", dataPath: "num_1", kind: "number" },
    { key: "weight", dataPath: "weight_1", kind: "number" },
    { key: "volume", dataPath: "volume_1", kind: "number" },
    { key: "freight", dataPath: "co_freight_f", kind: "number" },
    { key: "payment_type", dataPath: "pay_mode", kind: "choice", display: { pay_billing: "现付" } },
  ];
  const TEST_ORDERS = {
    A: {
      source_record_id: `PAR-A-${Date.now().toString(36)}`,
      destination_text: "通海县", delivery_type: "delivery", sender_name: MARKER_A_HIDDEN,
      receiver_name: "并行测试收货甲", receiver_mobile: "13800000011", goods_name: "并行测试纸箱甲",
      package: "纸箱", quantity: 5, weight: 10.5, volume: 0.11, freight: 21, payment_type: "pay_billing",
    },
    B: {
      source_record_id: `PAR-B-${Date.now().toString(36)}`,
      destination_text: "通海县", delivery_type: "pickup", sender_name: MARKER_B_ACTIVE,
      receiver_name: "并行测试收货乙", receiver_mobile: "13800000012", goods_name: "并行测试配件乙",
      package: "木箱", quantity: 3, weight: 12.5, volume: 0.08, freight: 36, payment_type: "pay_billing",
    },
  };

  const state = {
    running: false,
    safetyBlocks: 0,
    report: null,
    confirmationAccepted: false,
    saveClickPermit: "",
    savePermits: [],
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
          const permit = takeSavePermit("fetch", String(url));
          if (!permit) {
            notifySafetyBlock("fetch", String(url));
            return Promise.reject(new Error("最终探针已阻止未授权保存请求 /coHandle"));
          }
          return originalFetch.call(this, input, init).then(async (response) => {
            let text = "";
            try { text = await response.clone().text(); } catch { /* classified below */ }
            settleSavePermit(permit, classifySaveResponse(response.status, text, "fetch"));
            return response;
          }, (error) => {
            settleSavePermit(permit, ambiguousSave(`保存请求发生网络错误：${errorMessage(error)}`, "fetch"));
            throw error;
          });
        }
        return originalFetch.call(this, input, init);
      };
    }

    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function guardedOpen(method, url, ...rest) {
      this.__cmParallelProbeUrl = String(url || "");
      return originalOpen.call(this, method, url, ...rest);
    };
    XMLHttpRequest.prototype.send = function guardedSend(body) {
      if (BLOCKED_REQUEST.test(this.__cmParallelProbeUrl || "")) {
        const permit = takeSavePermit("xhr", this.__cmParallelProbeUrl);
        if (!permit) {
          notifySafetyBlock("xhr", this.__cmParallelProbeUrl);
          throw new Error("最终探针已阻止未授权保存请求 /coHandle");
        }
        this.addEventListener("loadend", () => {
          let text = "";
          try { text = typeof this.responseText === "string" ? this.responseText : ""; } catch { /* classified below */ }
          if (!this.status) settleSavePermit(permit, ambiguousSave("保存请求已发出，但没有收到可确认响应", "xhr"));
          else settleSavePermit(permit, classifySaveResponse(this.status, text, "xhr"));
        }, { once: true });
      }
      return originalSend.call(this, body);
    };

    document.addEventListener("click", (event) => {
      const clickable = event.target?.closest?.("button,a,[role='button'],.el-button,.ant-btn");
      if (!clickable || clickable.closest(`#${PANEL_ID}`)) return;
      if (!SAVE_BUTTON_TEXT.test(normalizeText(clickable.textContent))) return;
      if (state.saveClickPermit) {
        state.saveClickPermit = "";
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      notifySafetyBlock("click", normalizeText(clickable.textContent));
    }, true);

    document.addEventListener("keydown", (event) => {
      if (event.key !== "F9" && event.code !== "F9") return;
      event.preventDefault();
      event.stopImmediatePropagation();
      notifySafetyBlock("keyboard", "F9");
    }, true);
  }

  function notifySafetyBlock(channel, detail) {
    state.safetyBlocks += 1;
    if (state.report) state.report.safety_blocks.push({ at: now(), channel, detail: redactUrl(detail) });
    if (state.ui) {
      setStatus(`已阻止保存操作（${channel}），累计 ${state.safetyBlocks} 次。`, "warning");
      renderSummary();
    }
  }

  function armSavePermit(label) {
    let requestSeenResolve;
    let resultResolve;
    const permit = {
      label,
      status: "armed",
      armed_at: now(),
      request_seen_at: "",
      channel: "",
      requestSeenPromise: new Promise((resolve) => { requestSeenResolve = resolve; }),
      resultPromise: new Promise((resolve) => { resultResolve = resolve; }),
      requestSeenResolve,
      resultResolve,
      timer: null,
    };
    permit.timer = setTimeout(() => {
      if (permit.status === "settled") return;
      const result = permit.status === "sent"
        ? ambiguousSave("保存请求已发出但等待响应超时；必须人工查询订单", permit.channel || "timeout")
        : { state: "save_failed", error: "点击保存后未检测到 /coHandle 请求", channel: "none", http_status: "", errno: "", message: "未发出请求" };
      settleSavePermit(permit, result);
    }, 20000);
    state.savePermits.push(permit);
    return permit;
  }

  function takeSavePermit(channel, url) {
    const permit = state.savePermits.find((item) => item.status === "armed");
    if (!permit || !state.confirmationAccepted) return null;
    permit.status = "sent";
    permit.channel = channel;
    permit.request_seen_at = now();
    permit.request_path = redactUrl(url);
    permit.requestSeenResolve({ channel, at: permit.request_seen_at });
    addEvent("save_request_seen", { label: permit.label, channel, request_path: permit.request_path });
    return permit;
  }

  function settleSavePermit(permit, result) {
    if (!permit || permit.status === "settled") return;
    clearTimeout(permit.timer);
    permit.status = "settled";
    permit.settled_at = now();
    permit.result = { ...result, label: permit.label, armed_at: permit.armed_at, request_seen_at: permit.request_seen_at, settled_at: permit.settled_at };
    permit.resultResolve(permit.result);
    addEvent("save_result", { label: permit.label, state: result.state, errno: result.errno ?? "", identity_fingerprint: result.identity_fingerprint || "" });
  }

  function classifySaveResponse(httpStatus, text, channel) {
    if (httpStatus < 200 || httpStatus >= 300) return { ...ambiguousSave(`保存接口返回 HTTP ${httpStatus}`, channel), http_status: httpStatus };
    let payload;
    try { payload = JSON.parse(text); } catch {
      return { ...ambiguousSave("保存接口响应无法解析", channel), http_status: httpStatus };
    }
    const errno = payload?.errno;
    const message = String(payload?.errmsg || "");
    const identity = extractOrderIdentity(payload?.res?.order_data);
    const base = { channel, http_status: httpStatus, errno: errno ?? "", message, ...identity };
    if (Number(errno) === 0) return { ...base, state: "saved", error: "" };
    if (Number(errno) === 320) return { ...base, state: "decision_required", error: message || "网站要求人工确认是否继续保存" };
    return { ...base, state: "save_failed", error: message || `网站拒绝保存（errno=${errno ?? "未知"}）` };
  }

  function ambiguousSave(message, channel) {
    return { state: "save_ambiguous", error: message, channel, http_status: "", errno: "", message: "结果不确定，禁止自动重试", identity_keys: [], identity_fingerprint: "" };
  }

  function extractOrderIdentity(orderData) {
    if (!orderData || typeof orderData !== "object") return { identity_keys: [], identity_fingerprint: "" };
    const preferred = ["od_basic_id", "od_id", "od_link_id", "order_num", "id", "order_id", "order_no", "co_id", "co_num", "oid"];
    const pairs = preferred.filter((key) => orderData[key] !== undefined && orderData[key] !== null && String(orderData[key]) !== "")
      .map((key) => [key, String(orderData[key])]);
    return { identity_keys: pairs.map(([key]) => key), identity_fingerprint: pairs.length ? stableHash(JSON.stringify(pairs)) : "" };
  }

  function initializeUi() {
    if (document.getElementById(PANEL_ID)) return;
    const host = document.createElement("div");
    host.id = PANEL_ID;
    const shadow = host.attachShadow({ mode: "open" });
    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        .panel { position:fixed; right:16px; bottom:16px; width:460px; max-height:78vh; z-index:2147483647;
          color:#172033; background:#fff; border:1px solid #9db4cc; border-radius:10px; box-shadow:0 12px 32px rgba(10,36,64,.28);
          font:13px/1.45 "Microsoft YaHei",Arial,sans-serif; overflow:hidden; }
        .head { display:flex; justify-content:space-between; padding:10px 12px; background:#17365d; color:#fff; }
        .body { padding:12px; overflow:auto; max-height:calc(78vh - 44px); }
        .safety { background:#fff2cc; border:1px solid #e6cf7a; color:#7f6000; border-radius:6px; padding:8px; }
        .row { display:flex; gap:8px; align-items:center; margin:10px 0; flex-wrap:wrap; }
        button { border:0; border-radius:6px; padding:8px 10px; cursor:pointer; font:inherit; }
        button.primary { background:#2f75b5; color:#fff; } button.secondary { background:#e7eef6; color:#17365d; }
        button:disabled { opacity:.45; cursor:not-allowed; }
        .status { padding:8px; border-radius:6px; background:#eef5fb; white-space:pre-wrap; }
        .status.error { background:#fde8e8; color:#991b1b; } .status.warning { background:#fff2cc; color:#7f6000; }
        .stats { display:grid; grid-template-columns:repeat(4,1fr); gap:6px; margin:10px 0; }
        .stat { background:#f4f7fa; border-radius:6px; padding:6px; text-align:center; }
        .stat b { display:block; font-size:15px; color:#17365d; }
        pre { max-height:190px; overflow:auto; background:#f6f8fa; border-radius:6px; padding:8px; white-space:pre-wrap; word-break:break-all; }
      </style>
      <section class="panel">
        <div class="head"><strong>内部页签并行探针</strong><small>v${SCRIPT_VERSION}</small></div>
        <div class="body">
          <div class="safety"><strong>最终探针会真实保存两条虚构订单。</strong>只放行探针明确触发的 A、B 两次保存，其他保存操作仍被拦截。</div>
          <label class="row"><input id="confirm" type="checkbox"> 我确认允许探针创建并真实保存两条虚构测试订单</label>
          <div class="row">
            <button id="run" class="primary">运行自动探针</button>
            <button id="export" class="secondary" disabled>重新下载报告</button>
          </div>
          <div id="status" class="status">请先停用其他车满满测试脚本，勾选确认后运行。</div>
          <div id="stats" class="stats"></div>
          <pre id="summary">尚未运行</pre>
        </div>
      </section>`;
    document.documentElement.appendChild(host);
    state.ui = {
      run: shadow.getElementById("run"),
      confirm: shadow.getElementById("confirm"),
      export: shadow.getElementById("export"),
      status: shadow.getElementById("status"),
      stats: shadow.getElementById("stats"),
      summary: shadow.getElementById("summary"),
    };
    state.ui.confirm.addEventListener("change", () => {
      state.confirmationAccepted = state.ui.confirm.checked;
      renderSummary();
    });
    state.ui.run.addEventListener("click", runProbe);
    state.ui.export.addEventListener("click", () => downloadReport(state.report));
    renderSummary();
  }

  async function runProbe() {
    if (state.running || state.report) return;
    if (!state.confirmationAccepted) {
      setStatus("请先勾选确认：允许探针真实保存两条虚构测试订单。", "error");
      return;
    }
    if (window.__CMMultiFillTest || window.__CMSerialSaveTest || window.__CMSingleSaveTest) {
      setStatus("检测到其他车满满测试脚本。请在油猴中停用其他脚本并刷新页面后再运行。", "error");
      return;
    }
    state.running = true;
    state.report = createReport();
    renderSummary();
    try {
      addEvent("probe_started");
      const baseline = captureSnapshot("before_create");
      state.report.baseline = { tab_count: baseline.tab_count, path_counts: baseline.path_counts };
      state.report.snapshots.push(baseline);

      setStatus("1/10：正在创建第一个内部订单页签…");
      const first = await createAndIdentifyTab("A", getActiveTab());
      const rootA = inferFormRoot(findUniqueVisiblePath("cor_name"));
      if (!rootA) throw new Error("第一个页签出现后，无法确定独立表单根容器");
      rootA.setAttribute("data-cm-parallel-probe-root", "A");
      const refsA = collectPathReferences(rootA);
      const senderA = uniqueReference(refsA, "cor_name");
      await writeControl(senderA, MARKER_A_ACTIVE);
      state.report.snapshots.push(captureSnapshot("first_tab_active", [rootA]));
      addEvent("first_marker_written", { readback: readControl(senderA) === MARKER_A_ACTIVE });

      setStatus("2/10：正在创建第二个内部订单页签…");
      const second = await createAndIdentifyTab("B", first.tab);
      await sleep(500);
      const rootB = inferFormRoot(findUniqueVisiblePath("cor_name"));
      if (!rootB) throw new Error("第二个页签出现后，无法确定独立表单根容器");
      rootB.setAttribute("data-cm-parallel-probe-root", "B");
      const refsB = collectPathReferences(rootB);
      const senderB = uniqueReference(refsB, "cor_name");
      await writeControl(senderB, MARKER_B_ACTIVE);

      const retention = evaluateRetention(rootA, rootB, refsA, refsB);
      state.report.retention_after_second_create = retention;
      state.report.snapshots.push(captureSnapshot("second_tab_active", [rootA, rootB]));

      setStatus("3/10：正在验证非活动表单直接写入隔离…", "warning");
      const hiddenWrite = await testHiddenWrite(senderA, senderB, rootA, rootB);
      state.report.hidden_write = hiddenWrite;
      state.report.snapshots.push(captureSnapshot("after_hidden_write", [rootA, rootB]));

      setStatus("4/10：正在自动切回第一个内部页签核对…");
      const switchA = await switchToTab(first.tab, rootA, "A");
      state.report.switch_back_to_first = {
        ...switchA,
        first_value_matches_hidden_marker: readControl(senderA) === MARKER_A_HIDDEN,
        second_reference_connected: Boolean(senderB?.isConnected),
        second_value_unchanged: readControl(senderB) === MARKER_B_ACTIVE,
      };
      state.report.snapshots.push(captureSnapshot("first_tab_reactivated", [rootA, rootB]));

      setStatus("5/10：正在自动切回第二个内部页签核对…");
      const switchB = await switchToTab(second.tab, rootB, "B");
      state.report.switch_back_to_second = {
        ...switchB,
        second_value_unchanged: readControl(senderB) === MARKER_B_ACTIVE,
        first_reference_connected: Boolean(senderA?.isConnected),
        first_value_matches_hidden_marker: readControl(senderA) === MARKER_A_HIDDEN,
      };
      state.report.snapshots.push(captureSnapshot("second_tab_reactivated", [rootA, rootB]));

      setStatus("6/10：正在同时写入两套表单的文本和数值字段…");
      const [directA, directB] = await Promise.all([
        fillDirectFields(rootA, TEST_ORDERS.A, "A"),
        fillDirectFields(rootB, TEST_ORDERS.B, "B"),
      ]);
      state.report.direct_field_fill = { A: directA, B: directB };

      setStatus("7/10：正在验证两个非活动表单的到站、送货方式和付款方式…", "warning");
      const hiddenCustomA = await attemptHiddenCustomFill(rootA, rootB, first.tab, second.tab, TEST_ORDERS.A, "A");
      const hiddenCustomB = await attemptHiddenCustomFill(rootB, rootA, second.tab, first.tab, TEST_ORDERS.B, "B");
      state.report.hidden_custom_fields = { A: hiddenCustomA, B: hiddenCustomB };

      setStatus("8/10：正在逐页签回读全部 12 个字段…");
      await switchToTab(first.tab, rootA, "A");
      const verifyA = verifyCompleteOrder(rootA, TEST_ORDERS.A);
      await switchToTab(second.tab, rootB, "B");
      const verifyB = verifyCompleteOrder(rootB, TEST_ORDERS.B);
      state.report.full_field_verification = { A: verifyA, B: verifyB };
      state.report.snapshots.push(captureSnapshot("full_fields_verified", [rootA, rootB]));
      state.report.structure_analysis = analyzeFeasibility(state.report, rootA, rootB);
      if (!verifyA.all_verified || !verifyB.all_verified) {
        throw new Error("两套表单未全部通过字段回读；为避免错误订单，最终探针未执行保存");
      }

      setStatus("9/10：正在触发 A、B 两条重叠保存并观察请求归属…", "warning");
      await switchToTab(first.tab, rootA, "A");
      const permitA = triggerObservedSave("A");
      await waitForPermitRequest(permitA, 5000);
      const aPendingBeforeSwitch = permitA.status !== "settled";
      await switchToTab(second.tab, rootB, "B", 40);
      const permitB = triggerObservedSave("B");
      await waitForPermitRequest(permitB, 5000);
      const aPendingWhenBRequestSeen = permitA.status !== "settled";
      const [saveA, saveB] = await Promise.all([permitA.resultPromise, permitB.resultPromise]);
      state.report.overlapped_save = {
        a_pending_before_switch: aPendingBeforeSwitch,
        a_pending_when_b_request_seen: aPendingWhenBRequestSeen,
        requests_overlapped: aPendingWhenBRequestSeen,
        A: saveA,
        B: saveB,
        identities_distinct: Boolean(saveA.identity_fingerprint && saveB.identity_fingerprint && saveA.identity_fingerprint !== saveB.identity_fingerprint),
      };
      state.report.save_attempts = summarizeSavePermits();

      setStatus("10/10：正在计算最终并行结论并生成报告…");
      state.report.analysis = analyzeFinalFeasibility(state.report);
      state.report.completed_at = now();
      addEvent("probe_completed", { verdict: state.report.analysis.verdict });
      setStatus(verdictMessage(state.report.analysis), state.report.analysis.verdict === "possible" ? "" : "warning");
      state.ui.export.disabled = false;
      downloadReport(state.report);
    } catch (error) {
      state.report.error = errorMessage(error);
      state.report.save_attempts = summarizeSavePermits();
      state.report.completed_at = now();
      state.report.analysis = { verdict: "inconclusive", reasons: [errorMessage(error)] };
      addEvent("probe_failed", { error: errorMessage(error) });
      setStatus(`探针未完成：${errorMessage(error)}。已保留当前数据，可下载报告。`, "error");
      state.ui.export.disabled = false;
      downloadReport(state.report);
    } finally {
      state.running = false;
      renderSummary();
    }
  }

  async function createAndIdentifyTab(label, previousTab) {
    const beforeTabs = new Set(listTabElements());
    const action = findUniqueAction(CREATE_TEXTS);
    if (!action) throw new Error(`创建页签 ${label}：未找到唯一“创建运单”按钮`);
    action.click();
    await waitFor(() => {
      const active = getActiveTab();
      const visibleSender = visiblePathElements("cor_name");
      const tabChanged = (active && !beforeTabs.has(active))
        || (previousTab && active && active !== previousTab)
        || listTabElements().some((tab) => !beforeTabs.has(tab));
      return tabChanged && visibleSender.length === 1;
    }, 15000, `创建页签 ${label}：等待新活动页签和表单超时`);
    await sleep(600);
    const tab = getActiveTab() || listTabElements().find((item) => !beforeTabs.has(item));
    if (!tab) throw new Error(`创建页签 ${label}：无法识别活动内部页签`);
    addEvent("tab_created", { label, tab: describeTab(tab) });
    return { label, tab };
  }

  function inferFormRoot(control) {
    if (!control) return null;
    let current = control;
    let best = null;
    for (let depth = 0; current && current !== document.body; depth += 1, current = current.parentElement) {
      const count = countProbePaths(current);
      if (!best || count > best.count) best = { element: current, count, depth };
      if (count >= Math.min(9, PROBE_PATHS.length) && isLikelyFormBoundary(current)) return current;
    }
    return best?.count >= 8 ? best.element : null;
  }

  function isLikelyFormBoundary(element) {
    const tag = element.tagName?.toLowerCase();
    const token = `${element.id || ""} ${element.className || ""}`.toLowerCase();
    return tag === "form" || /form|order|tab|pane|content|panel/.test(token);
  }

  function countProbePaths(root) {
    return PROBE_PATHS.filter((path) => root.querySelector(`[data-path="${CSS.escape(path)}"]`)).length;
  }

  function collectPathReferences(root) {
    const output = {};
    for (const path of PROBE_PATHS) output[path] = [...root.querySelectorAll(`[data-path="${CSS.escape(path)}"]`)];
    return output;
  }

  function uniqueReference(refs, path) {
    const matches = refs[path] || [];
    if (matches.length !== 1) throw new Error(`表单内 data-path=${path} 数量为 ${matches.length}，无法唯一绑定`);
    return matches[0];
  }

  function evaluateRetention(rootA, rootB, refsA, refsB) {
    const pathDetails = {};
    for (const path of PROBE_PATHS) {
      pathDetails[path] = {
        first_reference_count: refsA[path]?.length || 0,
        first_connected_count: (refsA[path] || []).filter((item) => item.isConnected).length,
        second_reference_count: refsB[path]?.length || 0,
        second_connected_count: (refsB[path] || []).filter((item) => item.isConnected).length,
        global_count: document.querySelectorAll(`[data-path="${CSS.escape(path)}"]`).length,
        visible_count: visiblePathElements(path).length,
      };
    }
    return {
      roots_are_distinct: rootA !== rootB,
      first_root_connected: rootA.isConnected,
      first_root_visible: isVisible(rootA),
      second_root_connected: rootB.isConnected,
      second_root_visible: isVisible(rootB),
      paths_with_two_connected_sets: PROBE_PATHS.filter((path) => pathDetails[path].first_connected_count && pathDetails[path].second_connected_count).length,
      path_details: pathDetails,
    };
  }

  async function testHiddenWrite(senderA, senderB, rootA, rootB) {
    const before = {
      first_connected: Boolean(senderA?.isConnected),
      first_sender_visible: isVisible(senderA),
      second_sender_visible: isVisible(senderB),
      first_value_matches_active_marker: readControl(senderA) === MARKER_A_ACTIVE,
      second_value_matches_active_marker: readControl(senderB) === MARKER_B_ACTIVE,
    };
    if (!senderA?.isConnected || senderA === senderB || rootA === rootB) {
      return { attempted: false, before, reason: "第一个表单未被独立保留，不能进行非活动表单写入" };
    }
    await writeControl(senderA, MARKER_A_HIDDEN, { focus: false, blur: false });
    await sleep(500);
    return {
      attempted: true,
      before,
      first_readback_matches: readControl(senderA) === MARKER_A_HIDDEN,
      second_remains_unchanged: readControl(senderB) === MARKER_B_ACTIVE,
      first_connected_after: senderA.isConnected,
      second_connected_after: senderB.isConnected,
    };
  }

  async function fillDirectFields(root, order, label) {
    const fields = [];
    for (const mapping of FIELD_MAPPINGS.filter((item) => item.kind === "text" || item.kind === "number")) {
      const result = { key: mapping.key, data_path: mapping.dataPath, status: "pending", error: "" };
      fields.push(result);
      try {
        const control = uniqueControlInRoot(root, mapping.dataPath);
        await writeControl(control, order[mapping.key], { focus: false, blur: false });
        if (mapping.key === "receiver_name") await sleep(350);
        const current = uniqueControlInRoot(root, mapping.dataPath);
        const matches = mapping.kind === "number"
          ? Number(readControl(current)) === Number(order[mapping.key])
          : normalizeText(readControl(current)) === normalizeText(order[mapping.key]);
        if (!matches) throw new Error("写入后回读不一致");
        result.status = "verified";
      } catch (error) {
        result.status = "failed";
        result.error = errorMessage(error);
      }
    }
    addEvent("direct_fields_filled", { label, verified: fields.filter((item) => item.status === "verified").length, total: fields.length });
    return { all_verified: fields.every((item) => item.status === "verified"), fields };
  }

  async function attemptHiddenCustomFill(targetRoot, otherRoot, targetTab, otherTab, order, label) {
    await switchToTab(otherTab, otherRoot, label === "A" ? "B" : "A");
    const hiddenAtStart = !isFormRootActive(targetRoot) && isFormRootActive(otherRoot);
    let hiddenResult;
    try {
      hiddenResult = await fillCustomFields(targetRoot, order, label, "hidden");
    } catch (error) {
      hiddenResult = { all_verified: false, error: errorMessage(error), fields: [] };
    }
    if (hiddenResult.all_verified) return { hidden_at_start: hiddenAtStart, hidden_success: true, fallback_used: false, hidden_result: hiddenResult };

    await switchToTab(targetTab, targetRoot, label);
    let fallbackResult;
    try {
      fallbackResult = await fillCustomFields(targetRoot, order, label, "active_fallback");
    } catch (error) {
      fallbackResult = { all_verified: false, error: errorMessage(error), fields: [] };
    }
    return {
      hidden_at_start: hiddenAtStart,
      hidden_success: false,
      fallback_used: true,
      hidden_result: hiddenResult,
      fallback_result: fallbackResult,
    };
  }

  async function fillCustomFields(root, order, label, mode) {
    const fields = [];
    for (const mapping of FIELD_MAPPINGS.filter((item) => item.kind === "autocomplete" || item.kind === "choice")) {
      const expected = mapping.display ? mapping.display[order[mapping.key]] : order[mapping.key];
      const result = { key: mapping.key, data_path: mapping.dataPath, status: "pending", error: "" };
      fields.push(result);
      try {
        const control = uniqueControlInRoot(root, mapping.dataPath);
        if (mapping.kind === "autocomplete") await fillScopedAutocomplete(control, String(expected), { focus: mode !== "hidden" });
        else await chooseScopedValue(control, String(expected));
        if (control.getAttribute("data-is-select") !== "1") throw new Error("data-is-select 未被网站确认为 1");
        if (!normalizeText(readControl(control) || control.title).includes(normalizeText(expected))) throw new Error("选择后回读不一致");
        result.status = "verified";
      } catch (error) {
        result.status = "failed";
        result.error = errorMessage(error);
        break;
      }
    }
    const allVerified = fields.length === 3 && fields.every((item) => item.status === "verified");
    addEvent("custom_fields_attempted", { label, mode, all_verified: allVerified });
    return { all_verified: allVerified, mode, fields };
  }

  async function fillScopedAutocomplete(control, value, options = {}) {
    const before = new Set([...document.body.querySelectorAll("*")].filter(isVisible));
    await writeControl(control, value, { focus: options.focus !== false, blur: false });
    const selection = await chooseNewExactOption(value, before, 4500);
    if (!selection) throw new Error(`未找到唯一候选：${value}`);
    await waitFor(
      () => control.getAttribute("data-is-select") === "1" && normalizeText(readControl(control) || control.title) === normalizeText(value),
      3500,
      `候选点击后网站未确认：${value}`,
    );
  }

  async function chooseScopedValue(control, value) {
    const before = new Set([...document.body.querySelectorAll("*")].filter(isVisible));
    control.click();
    const selection = await chooseNewExactOption(value, before, 3500);
    if (!selection) throw new Error(`未找到唯一选项：${value}`);
    await waitFor(
      () => control.getAttribute("data-is-select") === "1" && normalizeText(readControl(control) || control.title).includes(normalizeText(value)),
      3000,
      `选项点击后网站未确认：${value}`,
    );
  }

  async function chooseNewExactOption(value, visibleBeforeInput, timeoutMs) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const exactNew = [...document.body.querySelectorAll("*")].filter((element) => {
        if (element.closest(`#${PANEL_ID}`) || visibleBeforeInput.has(element) || !isVisible(element)) return false;
        return normalizeText(element.textContent) === normalizeText(value);
      });
      const deepest = exactNew.filter((element) => ![...element.children].some(
        (child) => isVisible(child) && normalizeText(child.textContent) === normalizeText(value),
      ));
      const targets = [...new Set(deepest.map((element) => (
        element.closest("[role='option'],li,button,a,[data-value],[data-id],[class*='option'],[class*='item']") || element
      )))];
      if (targets.length === 1) {
        dispatchUserLikeClick(targets[0]);
        return true;
      }
      if (targets.length > 1) throw new Error(`候选“${value}”出现 ${targets.length} 次`);
      await sleep(100);
    }
    return false;
  }

  function verifyCompleteOrder(root, order) {
    const fields = FIELD_MAPPINGS.map((mapping) => {
      const expected = mapping.display ? mapping.display[order[mapping.key]] : order[mapping.key];
      try {
        const control = uniqueControlInRoot(root, mapping.dataPath);
        const selected = mapping.kind !== "autocomplete" && mapping.kind !== "choice" || control.getAttribute("data-is-select") === "1";
        const valueMatches = mapping.kind === "number"
          ? Number(readControl(control)) === Number(expected)
          : normalizeText(readControl(control) || control.title).includes(normalizeText(expected));
        return { key: mapping.key, data_path: mapping.dataPath, verified: selected && valueMatches, selected_confirmed: selected, value_matches: valueMatches };
      } catch (error) {
        return { key: mapping.key, data_path: mapping.dataPath, verified: false, error: errorMessage(error) };
      }
    });
    return { all_verified: fields.every((item) => item.verified), verified_count: fields.filter((item) => item.verified).length, total: fields.length, fields };
  }

  function uniqueControlInRoot(root, dataPath) {
    const matches = [...root.querySelectorAll(`[data-path="${CSS.escape(dataPath)}"]`)].filter((item) => item.isConnected);
    if (matches.length !== 1) throw new Error(`表单内 data-path=${dataPath} 数量为 ${matches.length}`);
    return matches[0];
  }

  function triggerObservedSave(label) {
    const action = findUniqueSaveAction();
    if (!action) throw new Error(`页签 ${label}：未找到唯一可见保存按钮`);
    const permit = armSavePermit(label);
    state.saveClickPermit = label;
    try { action.click(); } catch (error) {
      state.saveClickPermit = "";
      settleSavePermit(permit, { state: "save_failed", error: `触发保存按钮失败：${errorMessage(error)}`, channel: "none", http_status: "", errno: "", message: "未发出请求", identity_keys: [], identity_fingerprint: "" });
    }
    return permit;
  }

  async function waitForPermitRequest(permit, timeoutMs) {
    const outcome = await Promise.race([
      permit.requestSeenPromise.then(() => "request"),
      permit.resultPromise.then(() => "result"),
      sleep(timeoutMs).then(() => "timeout"),
    ]);
    if (outcome !== "request") {
      if (permit.status !== "settled") settleSavePermit(permit, { state: "save_failed", error: `未在 ${timeoutMs}ms 内检测到保存请求`, channel: "none", http_status: "", errno: "", message: "未发出请求", identity_keys: [], identity_fingerprint: "" });
      throw new Error(`页签 ${permit.label}：未在 ${timeoutMs}ms 内检测到保存请求`);
    }
  }

  function findUniqueSaveAction() {
    const candidates = [...document.querySelectorAll("button,a,[role='button'],.el-button,.ant-btn")]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item) && SAVE_BUTTON_TEXT.test(normalizeText(item.textContent)));
    const unique = [...new Set(candidates.map((item) => item.closest("button,a,[role='button'],.el-button,.ant-btn") || item))];
    return unique.length === 1 ? unique[0] : null;
  }

  function summarizeSavePermits() {
    return state.savePermits.map((permit) => ({
      label: permit.label,
      status: permit.status,
      armed_at: permit.armed_at,
      request_seen_at: permit.request_seen_at,
      settled_at: permit.settled_at || "",
      channel: permit.channel,
      result: permit.result || null,
    }));
  }

  async function switchToTab(tab, expectedRoot, label, settleMs = 400) {
    if (!tab?.isConnected) return { attempted: false, success: false, reason: `页签 ${label} 元素已被销毁` };
    dispatchUserLikeClick(tab);
    try {
      const waiter = settleMs < 100 ? waitForFast : waitFor;
      await waiter(() => getActiveTab() === tab || isFormRootActive(expectedRoot), 5000, `切换到页签 ${label} 超时`);
      await sleep(settleMs);
      return { attempted: true, success: getActiveTab() === tab || isFormRootActive(expectedRoot), tab: describeTab(tab) };
    } catch (error) {
      return { attempted: true, success: false, reason: errorMessage(error), tab: describeTab(tab) };
    }
  }

  function analyzeFeasibility(report, rootA, rootB) {
    const retention = report.retention_after_second_create || {};
    const hidden = report.hidden_write || {};
    const firstSwitch = report.switch_back_to_first || {};
    const secondSwitch = report.switch_back_to_second || {};
    const reasons = [];
    const structuralPass = rootA !== rootB
      && retention.first_root_connected
      && retention.second_root_connected
      && retention.paths_with_two_connected_sets >= 8;
    const isolationPass = hidden.attempted && hidden.first_readback_matches && hidden.second_remains_unchanged;
    const persistencePass = firstSwitch.success && firstSwitch.first_value_matches_hidden_marker
      && firstSwitch.second_value_unchanged && secondSwitch.success && secondSwitch.second_value_unchanged;

    if (!structuralPass) reasons.push("两个内部页签没有同时保留足够多的独立表单控件集合");
    if (!isolationPass) reasons.push("非活动表单无法被独立写入，或写入影响了活动表单");
    if (!persistencePass) reasons.push("切换页签后探针值没有在两个表单中独立保持");
    if (structuralPass && isolationPass && persistencePass) {
      reasons.push("两个表单 DOM 独立保留，非活动表单写入隔离，切换后值保持");
      reasons.push("这只证明并行填写具备技术基础；独立保存仍需下一阶段专门验证");
      return { verdict: "possible", structural_pass: true, isolation_pass: true, persistence_pass: true, reasons };
    }
    const definitiveNo = !rootA.isConnected || rootA === rootB || retention.paths_with_two_connected_sets < 4;
    return {
      verdict: definitiveNo ? "not_possible_via_dom" : "inconclusive",
      structural_pass: structuralPass,
      isolation_pass: isolationPass,
      persistence_pass: persistencePass,
      reasons,
    };
  }

  function analyzeFinalFeasibility(report) {
    const structurePass = report.structure_analysis?.verdict === "possible";
    const hiddenCustomPass = Boolean(report.hidden_custom_fields?.A?.hidden_success && report.hidden_custom_fields?.B?.hidden_success);
    const fullFieldsPass = Boolean(report.full_field_verification?.A?.all_verified && report.full_field_verification?.B?.all_verified);
    const saveA = report.overlapped_save?.A;
    const saveB = report.overlapped_save?.B;
    const dualSavePass = saveA?.state === "saved" && saveB?.state === "saved";
    const overlapPass = Boolean(report.overlapped_save?.requests_overlapped);
    const identityPass = Boolean(report.overlapped_save?.identities_distinct);
    const reasons = [];
    if (structurePass) reasons.push("两个新增内部页签拥有独立且持续连接的表单 DOM"); else reasons.push("双表单结构隔离未通过");
    if (hiddenCustomPass) reasons.push("两个非活动表单均完成到站、送货方式和付款方式选择"); else reasons.push("至少一个非活动表单的自定义控件需要切换为活动页签后才能完成");
    if (fullFieldsPass) reasons.push("A、B 两套表单全部 12 个字段回读通过"); else reasons.push("至少一套表单未通过全部字段回读");
    if (dualSavePass) reasons.push("A、B 两次保存均返回成功"); else reasons.push("两次保存未全部得到明确成功结果");
    if (overlapPass) reasons.push("B 保存请求发出时 A 保存仍未结束，确认请求发生重叠"); else reasons.push("未观察到两次保存请求在时间上重叠");
    if (identityPass) reasons.push("两次成功响应包含不同的脱敏订单身份指纹"); else reasons.push("无法证明两次响应对应不同订单身份");

    let verdict = "not_ready";
    if (structurePass && hiddenCustomPass && fullFieldsPass && dualSavePass && overlapPass && identityPass) verdict = "parallel_full_pass";
    else if (structurePass && fullFieldsPass && dualSavePass && identityPass) verdict = "parallel_fill_and_dual_save_pass_overlap_unproven";
    else if (structurePass && fullFieldsPass) verdict = "parallel_fill_pass_save_not_ready";
    else if (structurePass) verdict = "parallel_structure_only";
    return { verdict, structure_pass: structurePass, hidden_custom_pass: hiddenCustomPass, full_fields_pass: fullFieldsPass, dual_save_pass: dualSavePass, overlap_pass: overlapPass, identity_pass: identityPass, reasons };
  }

  function captureSnapshot(stage, roots = []) {
    const pathCounts = {};
    for (const path of PROBE_PATHS) {
      const all = [...document.querySelectorAll(`[data-path="${CSS.escape(path)}"]`)];
      pathCounts[path] = { total: all.length, connected: all.filter((item) => item.isConnected).length, visible: all.filter(isVisible).length };
    }
    return {
      stage,
      at: now(),
      active_tab: describeTab(getActiveTab()),
      tab_count: listTabElements().length,
      path_counts: pathCounts,
      roots: roots.map(describeRoot),
    };
  }

  function describeRoot(root) {
    if (!root) return null;
    return {
      probe_label: root.getAttribute("data-cm-parallel-probe-root") || "",
      tag: root.tagName?.toLowerCase() || "",
      id: safeToken(root.id),
      classes: safeClassList(root),
      connected: root.isConnected,
      visible: isVisible(root),
      probe_path_count: countProbePaths(root),
      save_action_count: [...root.querySelectorAll("button,a,[role='button'],.el-button,.ant-btn")]
        .filter((item) => SAVE_BUTTON_TEXT.test(normalizeText(item.textContent))).length,
    };
  }

  function listTabElements() {
    const selectors = ["[role='tab']", ".el-tabs__item", ".ant-tabs-tab"];
    return [...new Set(selectors.flatMap((selector) => [...document.querySelectorAll(selector)]))]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && item.isConnected);
  }

  function getActiveTab() {
    const selectors = ["[role='tab'][aria-selected='true']", ".el-tabs__item.is-active", ".ant-tabs-tab-active", ".is-active[role='tab']"];
    for (const selector of selectors) {
      const match = [...document.querySelectorAll(selector)].find((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item));
      if (match) return match;
    }
    return null;
  }

  function describeTab(tab) {
    if (!tab) return null;
    return {
      tag: tab.tagName?.toLowerCase() || "",
      id: safeToken(tab.id),
      classes: safeClassList(tab),
      text: normalizeText(tab.textContent).slice(0, 80),
      aria_selected: tab.getAttribute("aria-selected"),
      aria_controls: safeToken(tab.getAttribute("aria-controls")),
      connected: tab.isConnected,
      visible: isVisible(tab),
    };
  }

  function findUniqueAction(texts) {
    const candidates = [...document.querySelectorAll("button,a,[role='button'],.el-button,.ant-btn")]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item) && texts.includes(normalizeText(item.textContent)));
    const unique = [...new Set(candidates.map((item) => item.closest("button,a,[role='button'],.el-button,.ant-btn") || item))];
    return unique.length === 1 ? unique[0] : null;
  }

  function findUniqueVisiblePath(path) {
    const matches = visiblePathElements(path);
    return matches.length === 1 ? matches[0] : null;
  }

  function isFormRootActive(root) {
    if (!root?.isConnected) return false;
    const visibleSenders = visiblePathElements("cor_name");
    return visibleSenders.length === 1 && root.contains(visibleSenders[0]);
  }

  function visiblePathElements(path) {
    return [...document.querySelectorAll(`[data-path="${CSS.escape(path)}"]`)]
      .filter((item) => !item.closest(`#${PANEL_ID}`) && isVisible(item));
  }

  async function writeControl(control, value, options = {}) {
    if (!control?.isConnected) throw new Error("目标控件已从 DOM 移除");
    if (options.focus !== false) control.focus();
    const prototype = control instanceof HTMLInputElement ? HTMLInputElement.prototype
      : control instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : null;
    const setter = prototype && Object.getOwnPropertyDescriptor(prototype, "value")?.set;
    if (setter) setter.call(control, value); else control.value = value;
    for (const type of ["input", "change"]) control.dispatchEvent(new Event(type, { bubbles: true }));
    if (options.blur !== false) control.blur();
    await sleep(180);
  }

  function readControl(control) {
    if (!control) return "";
    return String(control.value ?? control.getAttribute?.("value") ?? "");
  }

  function dispatchUserLikeClick(target) {
    target.scrollIntoView({ block: "nearest", inline: "nearest" });
    const options = { bubbles: true, cancelable: true, composed: true, view: window, button: 0, buttons: 1 };
    if (typeof PointerEvent === "function") target.dispatchEvent(new PointerEvent("pointerdown", { ...options, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    target.dispatchEvent(new MouseEvent("mousedown", options));
    target.dispatchEvent(new MouseEvent("mouseup", { ...options, buttons: 0 }));
    if (typeof PointerEvent === "function") target.dispatchEvent(new PointerEvent("pointerup", { ...options, buttons: 0, pointerId: 1, pointerType: "mouse", isPrimary: true }));
    target.dispatchEvent(new MouseEvent("click", { ...options, buttons: 0 }));
  }

  function createReport() {
    return {
      report_type: "chemanman_internal_tab_parallel_probe",
      script_version: SCRIPT_VERSION,
      started_at: now(),
      completed_at: "",
      location: `${location.origin}${location.pathname}`,
      privacy: "结构化脱敏报告；只包含虚构测试字段状态和订单身份哈希，不采集 Cookie、Token 或请求体",
      baseline: null,
      test_orders: { A: { source_record_id: TEST_ORDERS.A.source_record_id, scenario: "送货+现付" }, B: { source_record_id: TEST_ORDERS.B.source_record_id, scenario: "自提+现付" } },
      markers: { first_active: MARKER_A_ACTIVE, first_hidden: MARKER_A_HIDDEN, second_active: MARKER_B_ACTIVE },
      events: [],
      snapshots: [],
      safety_blocks: [],
      retention_after_second_create: null,
      hidden_write: null,
      switch_back_to_first: null,
      switch_back_to_second: null,
      direct_field_fill: null,
      hidden_custom_fields: null,
      full_field_verification: null,
      structure_analysis: null,
      overlapped_save: null,
      save_attempts: [],
      analysis: null,
      error: "",
    };
  }

  function addEvent(type, detail = {}) {
    if (state.report) state.report.events.push({ at: now(), type, ...detail });
  }

  function downloadReport(report) {
    if (!report) return;
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `车满满内部页签并行探针-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function verdictMessage(analysis) {
    if (analysis.verdict === "parallel_full_pass") return "最终探针通过：双表单完整字段、隐藏自定义控件、重叠保存和不同订单身份全部验证成功。报告已自动下载，请仍到订单列表人工核对两条测试单。";
    if (analysis.verdict === "parallel_fill_and_dual_save_pass_overlap_unproven") return "双表单填写和两次独立保存成功，但没有观察到请求重叠；报告已自动下载。";
    if (analysis.verdict === "parallel_fill_pass_save_not_ready") return "并行填写通过，但并行保存未完全通过。报告已自动下载，禁止自动重试不确定订单。";
    return "最终探针未达到完整并行门禁。报告已自动下载，请提供 JSON 继续分析。";
  }

  function renderSummary() {
    if (!state.ui) return;
    const analysis = state.report?.analysis;
    const retention = state.report?.retention_after_second_create;
    state.ui.stats.innerHTML = [
      ["页签快照", state.report?.snapshots.length || 0],
      ["双控件集合", retention?.paths_with_two_connected_sets ?? "-"],
      ["保存拦截", state.safetyBlocks],
      ["结论", analysis?.verdict || "待运行"],
    ].map(([label, value]) => `<div class="stat"><b>${escapeHtml(value)}</b>${label}</div>`).join("");
    state.ui.summary.textContent = state.report ? JSON.stringify({
      analysis: state.report.analysis,
      full_field_verification: state.report.full_field_verification && {
        A: state.report.full_field_verification.A?.verified_count,
        B: state.report.full_field_verification.B?.verified_count,
      },
      overlapped_save: state.report.overlapped_save && {
        requests_overlapped: state.report.overlapped_save.requests_overlapped,
        A: state.report.overlapped_save.A?.state,
        B: state.report.overlapped_save.B?.state,
        identities_distinct: state.report.overlapped_save.identities_distinct,
      },
      retention: state.report.retention_after_second_create && {
        roots_are_distinct: state.report.retention_after_second_create.roots_are_distinct,
        first_root_connected: state.report.retention_after_second_create.first_root_connected,
        paths_with_two_connected_sets: state.report.retention_after_second_create.paths_with_two_connected_sets,
      },
      hidden_write: state.report.hidden_write,
      error: state.report.error,
    }, null, 2) : "尚未运行";
    state.ui.run.disabled = state.running || Boolean(state.report) || !state.confirmationAccepted;
    state.ui.confirm.disabled = state.running || Boolean(state.report);
  }

  function setStatus(message, type = "") {
    state.ui.status.className = `status${type ? ` ${type}` : ""}`;
    state.ui.status.textContent = message;
  }

  function waitFor(predicate, timeoutMs, message) {
    return new Promise((resolve, reject) => {
      const start = Date.now();
      const tick = () => {
        try { if (predicate()) return resolve(); } catch { /* retry */ }
        if (Date.now() - start >= timeoutMs) return reject(new Error(message));
        setTimeout(tick, 120);
      };
      tick();
    });
  }

  function waitForFast(predicate, timeoutMs, message) {
    return new Promise((resolve, reject) => {
      const start = Date.now();
      const tick = () => {
        try { if (predicate()) return resolve(); } catch { /* retry */ }
        if (Date.now() - start >= timeoutMs) return reject(new Error(message));
        setTimeout(tick, 10);
      };
      tick();
    });
  }

  function isVisible(element) {
    if (!(element instanceof Element) || !element.isConnected) return false;
    const style = getComputedStyle(element);
    return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0"
      && element.getClientRects().length > 0 && element.getAttribute("aria-hidden") !== "true";
  }

  function safeToken(value) { return String(value || "").replace(/[^\w\-:.]/g, "").slice(0, 120); }
  function safeClassList(element) { return [...(element?.classList || [])].map(safeToken).filter(Boolean).slice(0, 12); }
  function redactUrl(value) { try { return new URL(String(value), location.href).pathname; } catch { return String(value).slice(0, 120); } }
  function normalizeText(value) { return String(value ?? "").replace(/\s+/g, "").replace(/[：:]+$/, "").trim(); }
  function errorMessage(error) { return error instanceof Error ? error.message : String(error); }
  function stableHash(value) {
    let hash = 2166136261;
    for (const char of String(value)) {
      hash ^= char.charCodeAt(0);
      hash = Math.imul(hash, 16777619);
    }
    return `fnv1a-${(hash >>> 0).toString(16).padStart(8, "0")}`;
  }
  function escapeHtml(value) { return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char])); }
  function now() { return new Date().toISOString(); }
  function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

  window.__CMInternalParallelProbe = Object.freeze({
    version: SCRIPT_VERSION,
    probePaths: [...PROBE_PATHS],
    analyzeFeasibility,
    analyzeFinalFeasibility,
    classifySaveResponse,
    extractOrderIdentity,
    state,
  });
})();
