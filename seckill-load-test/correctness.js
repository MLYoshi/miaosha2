// 正确性测试：1000 VU × 1 次迭代，瞬时开抢 stock=100（prepare.sh correctness 已设置）。
//
// 验证目标（对应系统 Invariant 1/2：不重复下单、不超卖）：
//   - 最终 SUCCESS 用户数 == 库存数（100），不超卖
//   - SUCCESS 用户的 orderId 全局唯一，无重复下单
//   - 无残留 PROCESSING（消息全部消费完结或补偿完毕）
//
// 审计方式：VU 内轮询仅做功能验证；全局正确性审计在 teardown 单线程完成——
// 逐个查询 1000 个用户的 /miaosha/result，汇总分类并对 orderId 全局去重。
//
// 注意：受理即被拒（500214 售罄）的用户不会写 Redis result key，
// 其 /miaosha/result 返回 NONE（DB 兜底无记录）。因此期望终态分布为：
//   SUCCESS == 100，NONE + FAILED == 900（FAILED 来自消费侧补偿路径，正常应为 0）。
//
// 运行：
//   ./prepare.sh correctness && k6 run correctness.js && ./reset.sh
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import {
  doMiaosha,
  getResult,
  login,
  pollResultUntilTerminal,
  preheat,
  registerOrLogin,
  resultStatus,
} from './lib/api.js';
import {
  ADMIN_MOBILE,
  ADMIN_PASSWORD,
  CORRECTNESS_STOCK,
  CORRECTNESS_USERS,
  USER_BASE,
} from './lib/config.js';

// —— teardown 全局审计指标 ——————————————————————————
const auditSuccess = new Counter('audit_success_users'); // 期望 == CORRECTNESS_STOCK
const auditFailed = new Counter('audit_failed_users'); // 消费侧补偿（正常 ~0）
const auditNone = new Counter('audit_none_users'); // 受理即拒无 result 记录
const auditStuck = new Counter('audit_stuck_processing'); // 长时间未到终态，期望 0
const auditDupOrders = new Counter('audit_duplicate_order_ids'); // orderId 重复，期望 0
const auditMismatch = new Counter('audit_mismatch'); // 成功数 != 库存数，期望 0

export const options = {
  scenarios: {
    rush: {
      executor: 'per-vu-iterations',
      vus: CORRECTNESS_USERS,
      iterations: 1,
      maxDuration: '10m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 仅 HTTP 层
    audit_success_users: [`count==${CORRECTNESS_STOCK}`],
    audit_stuck_processing: ['count==0'],
    audit_duplicate_order_ids: ['count==0'],
    audit_mismatch: ['count==0'],
  },
};

export function setup() {
  const adminToken = login(ADMIN_MOBILE, ADMIN_PASSWORD);
  // 库存已由 ./prepare.sh correctness 写入 DB，这里负责写入 Redis
  preheat(adminToken);

  const tokens = new Array(CORRECTNESS_USERS);
  for (let i = 0; i < CORRECTNESS_USERS; i++) {
    tokens[i] = registerOrLogin(USER_BASE + i);
    if ((i + 1) % 200 === 0) console.log(`setup: ${i + 1}/${CORRECTNESS_USERS} 账号就绪`);
  }
  return { tokens, adminToken };
}

export default function (data) {
  // per-vu-iterations：VU 编号 1..N 与账号池一一对应，每个用户恰好抢一次
  const token = data.tokens[exec.vu.idInTest - 1];
  const acc = doMiaosha(token);

  if (acc && acc.status === 'PROCESSING') {
    // 轮询功能验证：排队中的用户等待终态（不做轮询压测）
    const r = pollResultUntilTerminal(token);
    resultStatus.add(1, { status: r.status });
  } else if (acc && acc.status === 'SUCCESS') {
    // 降级同步落库直接拿单（Redis/Kafka 异常时的路径，正常主链路少见）
    resultStatus.add(1, { status: 'SUCCESS_DIRECT' });
  }
}

// 全局正确性审计：单线程逐用户查终态（消费者最坏重试 1s/2s/4s，这里再给最多 10×1s 缓冲）
export function teardown(data) {
  let success = 0;
  let failed = 0;
  let none = 0;
  let stuck = 0;
  let dup = 0;
  const orderIds = new Set();

  for (let i = 0; i < CORRECTNESS_USERS; i++) {
    const token = data.tokens[i];
    let r = null;
    for (let attempt = 0; attempt < 10; attempt++) {
      r = getResult(token);
      if (r && r.status !== 'PROCESSING') break;
      sleep(1);
    }
    if (!r) {
      none++;
      continue;
    }
    if (r.status === 'PROCESSING') {
      stuck++;
      continue;
    }
    if (r.status === 'SUCCESS') {
      success++;
      if (orderIds.has(r.orderId)) dup++;
      else orderIds.add(r.orderId);
    } else if (r.status === 'FAILED') {
      failed++;
    } else {
      none++; // NONE：受理即被拒（500214 售罄），无 result 记录
    }
  }

  const mismatch = success !== CORRECTNESS_STOCK ? 1 : 0;
  auditSuccess.add(success);
  auditFailed.add(failed);
  auditNone.add(none);
  auditStuck.add(stuck);
  auditDupOrders.add(dup);
  auditMismatch.add(mismatch);

  console.log(
    `[audit] SUCCESS=${success} (期望 ${CORRECTNESS_STOCK}) FAILED=${failed} ` +
      `NONE=${none} STUCK=${stuck} DUP_ORDER=${dup}`
  );
  console.log(
    `[audit] NONE+FAILED 应约等于 ${CORRECTNESS_USERS - CORRECTNESS_STOCK} ` +
      `(NONE=受理即被拒无 result 记录, FAILED=消费侧补偿)`
  );
}
