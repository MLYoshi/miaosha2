// 后端 HTTP API 封装 + 业务指标。
//
// 关键契约（见 ../backend 技术规格）：
//   - 所有响应为 Result{code, msg, data}，code==0 成功；业务错误也是 HTTP 200，
//     因此 http_req_failed 只反映 HTTP 层/连接错误，业务码需单独分桶计数。
//   - 鉴权：Authorization: Bearer <token>（登录/注册放行）。
//   - 注册成功直接返回 token，免二次登录；手机号已注册返回 500503。
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  BASE_URL,
  GOODS_ID,
  POLL_INTERVAL_MS,
  POLL_TIMEOUT_MS,
  USER_PASSWORD,
} from './config.js';

// —— 业务指标 ————————————————————————————————————————————
// 受理结果分桶（tag: status）：PROCESSING / SUCCESS / REPEAT / STOCK_EMPTY /
// NOT_START / OVER / BIZ_xxx / HTTP_xxx
export const acceptStatus = new Counter('accept_status');
// 受理延迟按业务桶分组（ms）。首次受理成功走 Kafka send().join()，
// REPEAT 只走 Redis EXISTS，两条路径延迟差异大，不能混看。
export const acceptLatency = new Trend('accept_latency', true);
// 终态轮询结果分桶（tag: status）：SUCCESS / FAILED / NONE / POLL_TIMEOUT /
// SUCCESS_DIRECT（受理降级直接拿单）。仅功能验证场景使用。
export const resultStatus = new Counter('result_status');

function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}
export { authHeaders };

function safeJson(res) {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
}

/** 登录：成功返回 token，失败抛异常（用于 setup，fail-fast）。 */
export function login(mobile, password = USER_PASSWORD) {
  const res = http.post(
    `${BASE_URL}/user/login`,
    JSON.stringify({ mobile: String(mobile), password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const body = safeJson(res);
  if (res.status !== 200 || !body || body.code !== 0) {
    throw new Error(`login failed for ${mobile}: HTTP ${res.status} ${res.body || ''}`);
  }
  return body.data;
}

/** 注册优先（成功即返 token），已注册(500503)则回退登录。幂等，可反复执行。 */
export function registerOrLogin(mobile, password = USER_PASSWORD) {
  const res = http.post(
    `${BASE_URL}/user/register`,
    JSON.stringify({ mobile: String(mobile), password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const body = safeJson(res);
  if (res.status === 200 && body && body.code === 0) return body.data;
  if (res.status === 200 && body && body.code === 500503) return login(mobile, password);
  throw new Error(`register failed for ${mobile}: HTTP ${res.status} ${res.body || ''}`);
}

/** 库存预热（POST /admin/preheat）：从 DB 读库存写入 Redis。失败抛异常。 */
export function preheat(token, goodsId = GOODS_ID) {
  const res = http.post(
    `${BASE_URL}/admin/preheat?goodsId=${goodsId}`,
    null,
    authHeaders(token)
  );
  const body = safeJson(res);
  if (res.status !== 200 || !body || body.code !== 0) {
    throw new Error(`preheat failed: HTTP ${res.status} ${res.body || ''}`);
  }
  return true;
}

/**
 * 秒杀受理（POST /miaosha/do_miaosha）。
 * 返回 { status, orderId }：status 为受理两态（PROCESSING/SUCCESS）、
 * 业务拒绝桶（REPEAT/STOCK_EMPTY/NOT_START/OVER/BIZ_xxx）或 HTTP_xxx。
 */
export function doMiaosha(token, goodsId = GOODS_ID) {
  const res = http.post(
    `${BASE_URL}/miaosha/do_miaosha?goodsId=${goodsId}`,
    null,
    authHeaders(token)
  );
  if (res.status !== 200) {
    recordAccept(`HTTP_${res.status}`, res);
    return { status: `HTTP_${res.status}`, orderId: null };
  }
  const body = safeJson(res);
  if (!body) {
    recordAccept('BAD_JSON', res);
    return { status: 'BAD_JSON', orderId: null };
  }
  if (body.code === 0) {
    recordAccept(body.data.status, res); // PROCESSING | SUCCESS
    return body.data;
  }
  const map = {
    500212: 'REPEAT',
    500214: 'STOCK_EMPTY',
    500215: 'NOT_START',
    500216: 'OVER',
    500104: 'GOODS_NOT_EXIST',
  };
  const bucket = map[body.code] || `BIZ_${body.code}`;
  recordAccept(bucket, res);
  return { status: bucket, orderId: null };
}

/** 结果查询（GET /miaosha/result）：返回 {status, orderId} 四态；异常返回 null。 */
export function getResult(token, goodsId = GOODS_ID) {
  const res = http.get(
    `${BASE_URL}/miaosha/result?goodsId=${goodsId}`,
    authHeaders(token)
  );
  const body = safeJson(res);
  if (res.status !== 200 || !body || body.code !== 0) return null;
  return body.data; // PROCESSING | SUCCESS | FAILED | NONE
}

/**
 * 轮询直到终态（仅功能验证，不做轮询压测）。
 * 返回终态 {status, orderId}；超时返回 POLL_TIMEOUT；HTTP 异常返回 POLL_ERROR。
 */
export function pollResultUntilTerminal(
  token,
  goodsId = GOODS_ID,
  intervalMs = POLL_INTERVAL_MS,
  timeoutMs = POLL_TIMEOUT_MS
) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const r = getResult(token, goodsId);
    if (r === null) return { status: 'POLL_ERROR', orderId: null };
    if (r.status !== 'PROCESSING') return r;
    if (Date.now() >= deadline) return { status: 'POLL_TIMEOUT', orderId: null };
    sleep(intervalMs / 1000);
  }
}

function recordAccept(bucket, res) {
  acceptStatus.add(1, { status: bucket });
  acceptLatency.add(res.timings.duration, { status: bucket });
}
