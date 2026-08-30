# seckill-load-test（k6 压测 + 独立观测）

针对秒杀系统后端（`../backend`：Redis Lua 预扣 → Kafka 削峰 → 异步落库 → 轮询拿单）的压测套件。

## 职责边界（核心约定）

```
k6（压测）        = 产生压力 + 记录业务层指标
observe.sh（观测） = 并行采集 JVM / Tomcat / Kafka / Redis / MySQL / 容器资源指标（与 k6 解耦）
分析              = 两类数据按时间对齐后对照，定位瓶颈
```

- **k6 脚本内不出现任何组件监控命令**（docker/jstat/redis-cli 等），只输出业务指标。
- **observe.sh 不参与压测**，只做底层资源与组件的定时采样。
- 最终结论 = 业务指标（k6）+ 组件指标（observe.sh）结合分析。

## 前置条件

- 全栈已启动：仓库根目录 `docker compose up -d`（backend 暴露 `http://localhost:8080`）
- 种子数据已导入（首次克隆时）：见 `docker-compose.yaml` 头部注释
- k6 已安装（验证版本：v2.2.0）
- 容器名约定：`seckill-backend` / `seckill-mysql` / `seckill-redis` / `seckill-kafka`（docker-compose 固定）

## 场景（3 个核心，保持不变）

| 脚本 | 目的 | 负载形态 |
|---|---|---|
| `smoke.js` | 环境/功能验证：登录 → 列表 → 详情 → 预热 → 受理 → 轮询终态 | 1 VU × 1 次 |
| `spike.js` | 吞吐与延迟：阶梯洪峰 | `ramping-arrival-rate`（open model），50→100→200→300→500 RPS，每档 1 分钟（5s 爬升 + 55s 保持），总请求约 6.9 万 |
| `correctness.js` | 正确性：不超卖、不重复下单 | `per-vu-iterations`，1000 VU × 1 次瞬时开抢 stock=100，teardown 全局审计 |

## k6 业务指标

| 指标 | 说明 |
|---|---|
| `http_req_duration` | HTTP 层延迟，p95 见 summary，p99 需设置 `K6_SUMMARY_TREND_STATS`（见下） |
| `http_req_failed` | 仅 HTTP 层错误（连接失败/非 2xx）。业务失败是 HTTP 200 + 业务码，不在此列 |
| `iterations` / `dropped_iterations` | 实际完成请求数与丢弃数（open model 打爆时的信号） |
| `accept_status`（Counter，按 `status` 分桶） | 受理结果：`PROCESSING`/`SUCCESS`（受理成功）、`REPEAT`/`STOCK_EMPTY`/`NOT_START`/`OVER`（业务拒绝）、`BIZ_xxx`/`HTTP_xxx` |
| `accept_latency`（Trend，按 `status` 分桶） | 受理延迟。**首次受理成功走 Kafka 发送（慢），`REPEAT` 只走 Redis EXISTS（快），务必分桶解读** |
| `result_status`（Counter，按 `status` 分桶） | 轮询终态：`SUCCESS`/`FAILED`/`NONE`/`POLL_TIMEOUT`/`SUCCESS_DIRECT`（降级直接拿单） |
| `audit_*`（Counter） | correctness 全局审计：`SUCCESS==100`、`DUP_ORDER==0`、`STUCK==0` |

查看 p99 及自定义统计位：

```bash
K6_SUMMARY_TREND_STATS="avg,min,med,max,p(90),p(95),p(99)" k6 run spike.js
```

### correctness 审计口径

受理即被拒（`500214` 售罄）的用户**不会写 Redis result key**，其 `/miaosha/result` 返回 `NONE`。期望终态分布：

```
audit_success_users         == 100   （不超卖：恰好等于库存）
audit_none_users + audit_failed_users == 900
audit_stuck_processing      == 0     （消息全部消费/补偿完结）
audit_duplicate_order_ids   == 0     （不重复下单：orderId 全局唯一）
audit_mismatch              == 0
```

阈值不通过时 k6 退出码非 0，可直接进 CI。

## 观测采集（observe.sh，独立于 k6）

```bash
# 生成可执行权限（首次）
chmod +x observe.sh summarize.sh

# 压测期间并行采集（示例：spike 全程 10 分钟）
./prepare.sh spike
./observe.sh --label spike-baseline --interval 5 --duration 600 &
k6 run spike.js
wait
./summarize.sh results/spike-baseline
```

输出 `results/<label>/*.tsv`（ts 为 epoch 秒，可用于差分算速率）：

| 文件 | 覆盖组件 | 关键字段 |
|---|---|---|
| `host.tsv` | 宿主机 | CPU%（ps 汇总，需按核数折算）、free/active 内存 |
| `docker-stats.tsv` | Docker 各容器 | CPU%、MemUsage、Mem% |
| `jvm.tsv` | JVM（backend） | 进程线程数（Tomcat 压力近似）、堆 used（需容器有 jcmd） |
| `kafka-consumer-lag.tsv` | Kafka | 消费组 `seckill` 各分区 current/log-end offset、lag |
| `kafka-topic-end-offset.tsv` | Kafka | 各分区 log-end-offset（差分 = 生产吞吐 msg/s） |
| `redis.tsv` | Redis | instantaneous ops/s、used_memory、connected_clients、cpu |
| `mysql-status.tsv` | MySQL | Threads_connected / Threads_running、Queries（差分=QPS）、Slow_queries |

采集说明：

- **Tomcat**：当前无 actuator 端点暴露（`management.endpoints.web.exposure.include: health,info`），以 JVM 进程线程数为近似压力信号；如需精确活跃线程，可在 backend 开启 `management.endpoints.web.exposure.include: health,info,metrics` 或 JMX，再把对应采样加入 `observe.sh`。
- **JVM GC**：容器未开 GC 日志。若需 GC 次数/暂停，在 `docker-compose.yaml` backend 加 JVM 参数 `-XX:+PrintGCDetails -Xlog:gc`（挂载日志目录），再扩展 `observe.sh` 解析。当前版本不预设，先跑 baseline。
- **Kafka 生产/消费吞吐**：`kafka-topic-end-offset.tsv` 相邻两次采样差分即速率；lag 文件直接看积压。
- 组件不存在或命令失败时对应采样静默跳过，不中断采集。

## 完整测试流程（baseline → 调优 → 复测 → 对比）

> 原则：**不预设 JVM/Kafka/Redis/MySQL 调优参数**。先建立 baseline，用真实数据决定优化对象，改动一处、复测一次、对比一次。

```bash
cd seckill-load-test

# ── 第 0 步：冒烟（环境就绪确认）──
k6 run smoke.js

# ── 第 1 步：baseline（优化前）──
./prepare.sh spike
./observe.sh --label spike-baseline --interval 5 --duration 600 &
k6 run spike.js
wait
./summarize.sh results/spike-baseline     # 组件侧基线

./prepare.sh correctness
./observe.sh --label correctness-baseline --interval 5 --duration 120 &
k6 run correctness.js
wait
./summarize.sh results/correctness-baseline
./reset.sh

# ── 第 2 步：分析瓶颈 ──
# 对照 k6 业务指标与 observe 组件指标：
#   accept_latency 高          → 查 backend 线程/CPU、Kafka 生产延迟（send().join()）
#   kafka lag 持续增长         → 消费者吞吐不足：DB 慢查询 / 事务锁 / 消费并发
#   mysql QPS/Threads_running 高 → 落库事务是瓶颈
#   redis ops 高但延迟低       → 预扣路径健康，问题在别处

# ── 第 3 步：修改对应组件配置（一次只改一处）──
# 例：调 backend JVM 参数 / Tomcat 线程池 / 消费者并发 / MySQL 连接池 / Redis 配置
#     （具体参数由 baseline 数据决定，本仓库不预设）

# ── 第 4 步：复测（同一套 k6 场景、同样的 prepare）──
./prepare.sh spike
./observe.sh --label spike-after-X --interval 5 --duration 600 &
k6 run spike.js
wait
./summarize.sh results/spike-after-X

# ── 第 5 步：对比 ──
# k6 侧：http_req_duration p95/p99、accept_latency 分桶、http_req_failed
# 组件侧：results/spike-baseline vs results/spike-after-X
# 结论成立标准：业务指标改善 且 组件侧瓶颈移动/消除；否则回滚该改动
```

快速单跑（不采集组件指标）：

```bash
./prepare.sh spike && k6 run spike.js && ./reset.sh
./prepare.sh correctness && k6 run correctness.js && ./reset.sh
```

## 配置（`-e` 环境变量，均有默认值）

| 变量 | 默认 | 说明 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 后端地址 |
| `GOODS_ID` | `1` | 压测商品（`prepare.sh` 同名变量须一致） |
| `SPIKE_USERS` | `10000` | spike 账号池大小 |
| `CORRECTNESS_USERS` | `1000` | correctness 并发用户数 |
| `CORRECTNESS_STOCK` | `100` | correctness 期望库存（须与 `./prepare.sh correctness` 一致） |
| `POLL_INTERVAL_MS` / `POLL_TIMEOUT_MS` | `500` / `60000` | 轮询间隔/超时（仅功能验证） |
| `ADMIN_MOBILE` / `ADMIN_PASSWORD` | `18912341234` / `123456` | 超管账号（预热与审计） |

示例：`k6 run -e BASE_URL=http://10.0.0.5:8080 -e SPIKE_USERS=20000 spike.js`

## 注意事项

1. **backend 容器时区必须与 SQL 一致**：`docker-compose.yaml` 中 backend 已配置 `TZ: Asia/Shanghai`（JVM `Clock.systemDefaultZone()` 与种子/准备脚本的 `+08:00` 时间窗对齐）。若容器时区为 UTC，`checkInWindow` 会把所有落库判定为「秒杀未开始」→ 受理全部补偿失败、订单落库为 0（压测会表现为 spike 通过但 DB 零订单）。修改 compose 后需 `docker compose up -d --force-recreate backend` 重建容器。
2. **Kafka 积压污染**：上一轮压测残留的未消费消息会在下一轮继续落库/补偿，污染库存（`prepare.sh`/`reset.sh` 已内置「reset 消费组 offset + 重启 backend」清理）。spike 与 correctness 之间、以及每轮结束后务必执行对应脚本。
3. **数据准备/恢复**：`prepare.sh` 会**删除 goodsId 对应的历史订单**并清空 `miaosha:*` Redis key；`reset.sh` 恢复种子状态（时间窗对齐、库存 9）并删除压测账号订单。压测账号（131 段）本身保留，注册幂等。
4. **spike 的 REPEAT 占比**：6.9 万请求对 1 万账号，约 1/7 为首次受理，其余为 `REPEAT`。若需全量首次受理的纯净吞吐，`SPIKE_USERS=70000 ./prepare.sh spike && k6 run -e SPIKE_USERS=70000 spike.js`（setup 注册耗时同比增加）。
5. **阈值校准**：当前 `http_req_failed < 1%`、`p(95) < 300ms` 为占位值。跑完 baseline 后依据 `accept_latency` 各桶实际分布回填到脚本 `thresholds`，并以此作为优化前后对比的判定线。
6. **setup 耗时**：spike 首次运行 setup 需串行注册 1 万账号（localhost 约 1~2 分钟）；重复运行时一半注册一半登录，耗时接近。
7. **业务码速查**：`0` 成功 / `500212` 重复 / `500214` 售罄 / `500215` 未开始 / `500216` 已结束 / `500503` 手机号已注册（setup 预期内，自动回退登录）。
