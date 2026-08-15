import assert from "node:assert/strict";
import fs from "node:fs/promises";
import vm from "node:vm";

const scriptUrl = new URL("../油猴批量串行自动填写保存.user.js", import.meta.url);
const source = await fs.readFile(scriptUrl, "utf8");
function MockXhr() {}
MockXhr.prototype.open = function open() {};
MockXhr.prototype.send = function send() {};
const storage = new Map();
const localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key),
};
const context = {
  window: { fetch: undefined, Response, addEventListener() {} },
  document: { readyState: "loading", addEventListener() {} },
  XMLHttpRequest: MockXhr, TextDecoder, setTimeout, clearTimeout, Blob, Response,
  DecompressionStream, URL, console, localStorage,
  location: { origin: "https://t800.chemanman.com", pathname: "/Order", href: "https://t800.chemanman.com/Order", hostname: "t800.chemanman.com" },
};
vm.createContext(context);
vm.runInContext(source, context, { filename: scriptUrl.pathname });
const api = context.window.__CMBatchSerial;

// —— 内置站点表 ——
// 必须与 app/src/main/assets/destination_dictionary.json 的可选站点一致：unique_key 是两边的连接键。
const stations = Array.from(api.destinations);
assert.equal(stations.length, 2, "当前只发通海、玉溪两地");
assert.deepEqual(stations.map((item) => item.name).sort(), ["玉溪市", "通海县"].sort());
assert.deepEqual(
  Object.fromEntries(stations.map((item) => [item.name, item.unique_key])),
  { 通海县: "xzqh_id_38010", 玉溪市: "xzqh_id_37979" },
  "编码取自 Har/05-save-success.har 里真实保存请求的 arr_info.id",
);

// —— normalize ——
assert.equal(api.normalizeDestinationToken(" 玉 溪 · 通海 "), "玉溪通海");
assert.equal(api.normalizeDestinationToken("通海（县）"), "通海县");

// —— 查表：标准名与别名都能命中 ——
for (const [token, expected] of [
  ["通海县", "xzqh_id_38010"], ["通海", "xzqh_id_38010"], [" 通海 ", "xzqh_id_38010"],
  ["玉溪市", "xzqh_id_37979"], ["玉溪", "xzqh_id_37979"],
]) {
  assert.equal(api.findDestination(token)?.unique_key, expected, `「${token}」应命中 ${expected}`);
}
// 「玉溪」必须是行政区划玉溪市，不是系统里同名的自定义关键词站 xzqh_kw_玉溪。
assert.equal(api.findDestination("玉溪").name, "玉溪市");
// 发货地与不再发的站点都不在表里。
for (const token of ["昆明", "昆明市", "郑州", "开航", "玉溪通海"]) {
  assert.equal(api.findDestination(token), null, `「${token}」不该命中`);
}
assert.equal(api.findDestinationByKey("xzqh_id_38010").name, "通海县");
assert.equal(api.findDestinationByKey("xzqh_kw_玉溪"), null);

// —— 开跑前归一：编码优先，其次名字/别名，查不到保留原文 ——
const makeTask = (id, text, key = "") => ({
  id, state: "pending",
  order: { source_record_id: id, destination_text: text, destination_unique_key: key },
});
api.state.tasks = [
  makeTask("R-1", "随便写的", "xzqh_id_38010"),   // 有编码 → 按编码换成标准名
  makeTask("R-2", "通海"),                        // 别名 → 标准名 + 补编码
  makeTask("R-3", "玉溪"),                        // 「玉溪」→ 玉溪市
  makeTask("R-4", "通海县"),                      // 已经是标准名 → 不变
  makeTask("R-5", "郑州"),                        // 不在表里 → 原样保留，不猜
];
api.prepareDestinations();
const byId = Object.fromEntries(api.state.tasks.map((task) => [task.id, task.order]));
assert.deepEqual(
  Object.fromEntries(Object.entries(byId).map(([id, order]) => [id, [order.destination_text, order.destination_unique_key]])),
  {
    "R-1": ["通海县", "xzqh_id_38010"],
    "R-2": ["通海县", "xzqh_id_38010"],
    "R-3": ["玉溪市", "xzqh_id_37979"],
    "R-4": ["通海县", "xzqh_id_38010"],
    "R-5": ["郑州", ""],
  },
);
// 已完成的订单不该被改写。
api.state.tasks = [{ ...makeTask("R-6", "通海"), state: "saved" }];
api.prepareDestinations();
assert.equal(api.state.tasks[0].order.destination_text, "通海", "终态订单跳过归一");
api.state.tasks = [];

// —— XLSX v1.2 双向兼容 ——
const baseOrder = {
  __rowNumber: 2, __formulaFields: [], schema_version: "v1.2", batch_id: "B-1", source_record_id: "R-1",
  source_label: "照片 001", destination_text: "通海县", delivery_type: "delivery", sender_name: "甲",
  receiver_name: "乙", receiver_mobile: "13800000000", goods_name: "配件", package: "纸箱",
  quantity: 1, weight: 2, volume: 0.1, freight: 12, payment_type: "pay_arrival",
  destination_unique_key: "xzqh_id_38010", destination_display: "云南省玉溪市通海县",
};
assert.deepEqual(Array.from(api.validateOrders({ rows: [{ ...baseOrder }], errors: [] }).errors), [], "v1.2 带编码应通过");
assert.deepEqual(
  Array.from(api.validateOrders({ rows: [{ ...baseOrder, destination_unique_key: "", destination_display: "" }], errors: [] }).errors),
  [], "v1.2 编码留空允许（回退纯文本填写）",
);
assert(
  api.validateOrders({ rows: [{ ...baseOrder, destination_unique_key: "bad-key" }], errors: [] }).errors.some((error) => error.includes("destination_unique_key")),
  "非法编码要被拦下",
);
// v1.1 旧表（无新列）必须照常通过。
const legacyOrder = { ...baseOrder, schema_version: "v1.1" };
delete legacyOrder.destination_unique_key; delete legacyOrder.destination_display;
assert.deepEqual(Array.from(api.validateOrders({ rows: [legacyOrder], errors: [] }).errors), [], "v1.1 旧表兼容");
assert(api.validateOrders({ rows: [{ ...baseOrder, schema_version: "v2.0" }], errors: [] }).errors.some((error) => error.includes("schema_version")));

console.log("destination: ok");
