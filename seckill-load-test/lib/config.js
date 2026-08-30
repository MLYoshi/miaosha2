// 统一配置：全部可用 -e 环境变量覆盖，例如：
//   k6 run -e BASE_URL=http://localhost:8080 -e GOODS_ID=1 spike.js
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const GOODS_ID = Number(__ENV.GOODS_ID || 1);

// 压测账号池：13100000000 起连续号段，密码统一 123456。
// 密码以明文传输（后端自行完成双层 MD5，见 backend UserService）。
export const USER_BASE = Number(__ENV.USER_BASE || 13100000000);
export const USER_PASSWORD = __ENV.USER_PASSWORD || '123456';

// spike 账号池大小（须与 prepare.sh 后实际库存匹配考量：约 6.9 万请求 / 该账号数）
export const SPIKE_USERS = Number(__ENV.SPIKE_USERS || 10000);

// correctness 场景：并发用户数与库存（STOCK 须与 ./prepare.sh correctness 的默认值一致）
export const CORRECTNESS_USERS = Number(__ENV.CORRECTNESS_USERS || 1000);
export const CORRECTNESS_STOCK = Number(__ENV.CORRECTNESS_STOCK || 100);

// 超管账号（种子数据）：setup 内调 /admin/preheat 预热、teardown 内做全局审计
export const ADMIN_MOBILE = __ENV.ADMIN_MOBILE || '18912341234';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || '123456';

// 轮询参数（仅功能验证用途，轮询不做压力测试）
export const POLL_INTERVAL_MS = Number(__ENV.POLL_INTERVAL_MS || 500);
export const POLL_TIMEOUT_MS = Number(__ENV.POLL_TIMEOUT_MS || 60000);

// smoke 冒烟账号（独立于压测号段，避免与压测用户状态互相干扰）
export const SMOKE_MOBILE = __ENV.SMOKE_MOBILE || '13199999999';
