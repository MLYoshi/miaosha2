// 吞吐/延迟压测：阶梯洪峰 spike（open model，RPS 驱动）。
//
// 阶梯：50 → 100 → 200 → 300 → 500 RPS，每档 1 分钟（5s 爬升 + 55s 保持），总约 5 分钟，
// 总请求量约 6.9 万。账号池默认 10000 个（SPIKE_USERS 可调）：
//   - 账号池会被请求量耗尽，约 1/7 的请求为首次受理（PROCESSING/SUCCESS，
//     走 Redis Lua + Kafka 发送路径），其余为 REPEAT（仅 Redis EXISTS 快速返回）。
//   - 两条路径延迟差异大，accept_latency 已按业务桶分组，请分桶解读。
//   - 若需全量首次受理的纯净吞吐，提高账号池：SPIKE_USERS=70000（setup 注册耗时同比增加）。
//
// 运行：
//   ./prepare.sh spike && k6 run spike.js && ./reset.sh
import exec from 'k6/execution';
import { doMiaosha, login, preheat, registerOrLogin } from './lib/api.js';
import { ADMIN_MOBILE, ADMIN_PASSWORD, SPIKE_USERS, USER_BASE } from './lib/config.js';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        // 50 RPS
        { target: 50, duration: '5s' },
        { target: 50, duration: '55s' },
        // 100 RPS
        { target: 100, duration: '5s' },
        { target: 100, duration: '55s' },
        // 200 RPS
        { target: 200, duration: '5s' },
        { target: 200, duration: '55s' },
        // 300 RPS
        { target: 300, duration: '5s' },
        { target: 300, duration: '55s' },
        // 500 RPS
        { target: 500, duration: '5s' },
        { target: 500, duration: '55s' },
      ],
    },
  },
  thresholds: {
    // 占位阈值：smoke/首轮跑完后按实际数据校准（见 README「阈值校准」）
    http_req_failed: ['rate<0.01'], // 仅 HTTP 层（连接错误/非 2xx）；业务错误是 HTTP 200
    http_req_duration: ['p(95)<300'],
  },
};

// setup：超管登录 → 预热（HTTP 方式）→ 幂等注册账号池（首次约 1~2 分钟，localhost 串行注册）
export function setup() {
  const adminToken = login(ADMIN_MOBILE, ADMIN_PASSWORD);
  preheat(adminToken);

  const tokens = new Array(SPIKE_USERS);
  for (let i = 0; i < SPIKE_USERS; i++) {
    tokens[i] = registerOrLogin(USER_BASE + i);
    if ((i + 1) % 1000 === 0) console.log(`setup: ${i + 1}/${SPIKE_USERS} 账号就绪`);
  }
  console.log(`setup 完成：${SPIKE_USERS} 个账号已预热（Redis）/注册`);
  return { tokens };
}

// VU 内单调递增的请求序号；跨 VU 用 offset 错开起点。
// 7919 与默认号段长度 10000 互质（10000 = 2^4 * 5^4），VU 数 ≤ 号段长度时各 VU 起点互不重叠。
let seq = 0;

export default function (data) {
  const offset = ((exec.vu.idInTest - 1) * 7919) % data.tokens.length;
  const token = data.tokens[(offset + seq) % data.tokens.length];
  seq++;
  doMiaosha(token);
}
