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
  location: { origin: "https://t800.chemanman.com", pathname: "/Order", href: "https://t800.chemanman.com/Order" },
};
vm.createContext(context);
vm.runInContext(source, context, { filename: scriptUrl.pathname });
const api = context.window.__CMBatchSerial;

assert.equal(api.version, "1.5.0");
assert.equal(api.concurrency, 1, "兼容升级不得改变串行保存");
const base = {
  __rowNumber: 2, __formulaFields: [], schema_version: "v1.1", batch_id: "B-1", source_record_id: "R-1",
  source_label: "照片 001", destination_text: "杭州", delivery_type: "delivery", sender_name: "甲",
  receiver_name: "乙", receiver_mobile: "13800000000", goods_name: "配件", package: "纸箱",
  quantity: 1, weight: 2, volume: 0.1, freight: 12, payment_type: "pay_billing",
};

for (const payment of ["pay_billing", "pay_arrival", "pay_receipt"]) {
  const result = api.validateOrders({ rows: [{ ...base, payment_type: payment }], errors: [] });
  assert.deepEqual(Array.from(result.errors), [], `v1.1 应接受 ${payment}`);
}
assert(api.validateOrders({ rows: [{ ...base, schema_version: "v1.0", payment_type: "pay_arrival" }], errors: [] }).errors.some((error) => error.includes("v1.0")));
assert.equal(api.validateOrders({ rows: [{ ...base, schema_version: "v1.0", payment_type: "pay_billing" }], errors: [] }).errors.length, 0);
assert(api.validateOrders({ rows: [{ ...base, payment_type: "collect" }], errors: [] }).errors.some((error) => error.includes("pay_arrival")));
const paymentMap = api.fieldMappings.find((item) => item.key === "payment_type").display;
assert.equal(paymentMap.pay_arrival, "到付");
assert.equal(paymentMap.pay_receipt, "回付");

console.log("payment schema compatibility: ok");
