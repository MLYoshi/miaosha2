// 冒烟测试：环境与功能验证（1 VU × 1 次）。
// 全链路走一遍：管理员登录 → 商品列表 → 商品详情 → 库存预热 →
// 冒烟账号注册/登录 → 秒杀受理 → 轮询到达终态。
//
// 运行：k6 run smoke.js
// 若商品库存已被此前压测耗尽，先执行 ./reset.sh 或 ./prepare.sh spike。
import http from 'k6/http';
import { check } from 'k6';
import {
  authHeaders,
  doMiaosha,
  login,
  pollResultUntilTerminal,
  preheat,
  registerOrLogin,
  resultStatus,
} from './lib/api.js';
import { ADMIN_MOBILE, ADMIN_PASSWORD, BASE_URL, GOODS_ID, SMOKE_MOBILE } from './lib/config.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // 1. 管理员登录（登录/注册之外的所有接口都需要 Bearer JWT）
  const adminToken = login(ADMIN_MOBILE, ADMIN_PASSWORD);
  check(adminToken, { '管理员登录成功': (t) => !!t });

  // 2. 商品列表
  const listBody = http.get(`${BASE_URL}/goods/list`, authHeaders(adminToken)).json();
  check(listBody, {
    '商品列表 code==0 且非空': (b) => b && b.code === 0 && Array.isArray(b.data) && b.data.length > 0,
  });

  // 3. 商品详情
  const detailBody = http.get(`${BASE_URL}/goods/detail/${GOODS_ID}`, authHeaders(adminToken)).json();
  check(detailBody, { '商品详情 code==0': (b) => b && b.code === 0 });

  // 4. 库存预热（Redis miaosha:stock:{goodsId}）
  check(preheat(adminToken), { '库存预热成功': (v) => v === true });

  // 5. 冒烟账号（独立于压测号段）
  const smokeToken = registerOrLogin(SMOKE_MOBILE);
  check(smokeToken, { '冒烟账号就绪': (t) => !!t });

  // 6. 秒杀受理
  const acc = doMiaosha(smokeToken, GOODS_ID);
  check(acc, { '受理响应可解析': (a) => a !== null });

  if (acc && (acc.status === 'PROCESSING' || acc.status === 'SUCCESS')) {
    // 7. 轮询到达终态（功能验证，非轮询压测）
    const r = pollResultUntilTerminal(smokeToken, GOODS_ID);
    resultStatus.add(1, { status: r.status });
    check(r.status, {
      '轮询到达终态（SUCCESS/FAILED，非超时/异常）': (s) => s === 'SUCCESS' || s === 'FAILED',
    });
  } else if (acc && acc.status === 'STOCK_EMPTY') {
    console.log(
      `冒烟账号被拒：库存已空。请先执行 ./reset.sh（恢复种子库存 9）或 ./prepare.sh spike 后重跑。`
    );
  } else if (acc && acc.status === 'REPEAT') {
    console.log(
      `冒烟账号已抢过 goodsId=${GOODS_ID}（500212 REPEAT）。执行 ./prepare.sh 可清理历史订单后重跑。`
    );
  }
}
