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

// 单据示例：垫付款 250、运费 30、总运费 280 —— freight 拿到的是总运费。
const base = {
  __rowNumber: 2, __formulaFields: [], schema_version: "v1.3", batch_id: "B-1", source_record_id: "R-1",
  source_label: "照片 001", destination_text: "杭州", delivery_type: "delivery", sender_name: "甲",
  receiver_name: "乙", receiver_mobile: "13800000000", goods_name: "配件", package: "纸箱",
  quantity: 1, weight: 2, volume: 0.1, freight: 280, payment_type: "pay_billing",
};
const check = (row) => api.validateOrders({ rows: [{ ...base, ...row }], errors: [] });

// 两列都空 = 没有垫付款，与升级前行为一致。
assert.deepEqual(Array.from(check({}).errors), [], "无垫付款的 v1.3 行应通过");
assert.deepEqual(Array.from(check({ cashreturn: "", discount: "" }).errors), [], "空字符串等同于没有垫付款");

// 现返、欠返各自单独非空都合法，且被归一成数字。
for (const key of ["cashreturn", "discount"]) {
  const result = check({ [key]: 250 });
  assert.deepEqual(Array.from(result.errors), [], `${key} 单列非空应通过`);
  assert.equal(result.orders[0][key], 250);
  assert.equal(result.orders[0][key === "cashreturn" ? "discount" : "cashreturn"], "");
}

// 二选一：不允许同时填。
assert(check({ cashreturn: 100, discount: 150 }).errors.some((error) => error.includes("现返与欠返只能填一个")));

// 金额本身必须是大于 0、最多两位小数的数。
assert(check({ cashreturn: -1 }).errors.some((error) => error.includes("cashreturn")));
assert(check({ cashreturn: 0 }).errors.some((error) => error.includes("cashreturn")), "0 应由 App 归一为空，表格里出现 0 视为非法");
assert(check({ cashreturn: 1.234 }).errors.some((error) => error.includes("cashreturn")));
assert(check({ discount: "抹零" }).errors.some((error) => error.includes("discount")));

// 总运费必须装得下垫付款。
assert(check({ freight: 30, cashreturn: 250 }).errors.some((error) => error.includes("不能小于垫付款")));
assert.deepEqual(Array.from(check({ freight: 250, cashreturn: 250 }).errors), [], "总运费等于垫付款是允许的（运费为 0）");

// 旧版本表格不认识这两列，带值就是数据错配。
for (const schema of ["v1.1", "v1.2"]) {
  assert(
    check({ schema_version: schema, cashreturn: 250 }).errors.some((error) => error.includes("不支持垫付款列")),
    `${schema} 带垫付款应报错`,
  );
  assert.deepEqual(Array.from(check({ schema_version: schema }).errors), [], `${schema} 不带垫付款应照常通过`);
}

// 两列是可选表头，不能进必备表头闭集，否则 v1.2 旧表会直接导入失败。
assert(!api.requiredHeaders.includes("cashreturn"));
assert(!api.requiredHeaders.includes("discount"));
assert(api.optionalHeaders.includes("cashreturn"));
assert(api.optionalHeaders.includes("discount"));

// 条件字段独立于 FIELD_MAPPINGS：表单根判定与致命性检查依赖后者是「必然存在」的闭集。
assert.equal(api.fieldMappings.length, 12);
assert(!api.fieldMappings.some((item) => item.key === "cashreturn" || item.key === "discount"));
assert.deepEqual(Array.from(api.conditionalFields, (item) => item.dataPath), ["cashreturn", "discount"]);
assert(api.conditionalFields.every((item) => item.kind === "number"));

console.log("advance payment (cashreturn/discount) schema: ok");
