# AGENTS.md — 秒杀系统后端（Agent 工作指南）

> 本文件是 AI 编码助手在本目录工作时的导航与约定。深度设计文档：
> - `docs/db-design.md` — 数据库模型、业务规则、Agent 踩坑清单（**唯一 schema 事实来源**）
> - `docs/mq-design.md` — Kafka 消息层设计、可靠性语义、Agent 踩坑清单（**唯一消息层事实来源**）
> - `sql/README.md` — 种子数据执行与校验

## 1. 项目概览

手机端电商**秒杀系统**后端。核心流程：

```
登录 → 商品列表/详情 → 提交秒杀 → Redis 预扣 → Kafka 异步落库 → 前端轮询拿单
```

**技术栈**：Java 17 + Spring Boot 3.3.4 + MyBatis（XML mapper）+ MySQL 8 + Redis 7 + Kafka（KRaft，无 ZooKeeper）+ JWT 鉴权 + Testcontainers 测试。

**术语**：代码中「秒杀」统一用 `Miaosha` 前缀（类名、mapper、Redis key 均如此），不要改成 `Seckill`（消息体 `SeckillOrderMessage` 除外，历史命名）。

## 2. 目录结构

```
backend/
├── src/main/java/com/example/seckill/
│   ├── cache/       # Redis 库存预扣：MiaoshareDisStore 接口 + Redis 实现 + Lua 脚本封装
│   ├── common/      # Result/CodeMsg/MiaoshaException/JWT/MD5 工具、全局异常处理
│   ├── config/      # WebConfig（拦截器/静态资源）、ClockConfig
│   ├── controller/  # UserController / GoodsController / MiaoshaController / AdminController
│   ├── dao/         # MyBatis Mapper 接口（XML 在 src/main/resources/mapper/）
│   ├── domain/      # 数据库实体（User=业务用户, Goods, MiaoshaOrder, OrderInfo）
│   ├── interceptor/ # JwtInterceptor（Bearer token）
│   ├── message/     # Kafka：KafkaConfig（topic 定义）/ 生产者 / 消费者 / 消息体
│   ├── service/     # 业务编排（见 §4 架构）
│   └── vo/          # 请求/响应 VO
├── src/main/resources/
│   ├── application.yaml
│   ├── mapper/      # MyBatis XML
│   └── scripts/     # Redis Lua：miaosha_try.lua（预扣）、miaosha_compensate.lua（补偿）
├── src/test/java/   # 集成测试（Testcontainers）+ 单元测试，基座在 support/AbstractIntegrationTest
├── sql/             # fix-seed-time-window.sql：自包含建表+种子+时间窗对齐脚本
├── docs/            # 设计文档（Agent 必读，见顶部）
└── Dockerfile       # 多阶段构建：maven:3.9-temurin-17 构建 → temurin-17-jre 运行
```

## 3. 构建与运行

```bash
# ⚠️ maven-enforcer 强制 JDK 17。macOS 上 Maven 可能误用 Homebrew JDK 26，先：
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

mvn -B package -DskipTests      # 编译打包
mvn test                        # 测试（需要 Docker，见 §5）
mvn spring-boot:run             # 本地运行（依赖 localhost 上的 MySQL/Redis/Kafka）

# 中间件一键起（docker-compose.yaml 在仓库根目录 code2/，不在 backend/）
docker compose up -d mysql redis kafka

# 种子数据（新库必做，否则秒杀窗口全是 2017 年过期时间，接口报「已结束」）
docker exec -i seckill-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
  < sql/fix-seed-time-window.sql
```

- 应用端口 8080；MySQL root/root（库 `miaosha`）；Redis 密码 123456；Kafka localhost:9092。
- 中间件连接通过环境变量覆盖：`MYSQL_HOST/PORT`、`REDIS_HOST/PORT`、`KAFKA_HOST/PORT`。
- **时区陷阱**：容器内必须 `TZ=Asia/Shanghai`（compose 已设置），否则时间窗校验 `checkInWindow` 误判。

测试账号：`18912341234`（超级账号）/ `13000000000`~`13000004999`（压测账号），密码均为 `123456`。密码传输为双层 MD5：前端 `MD5(明文+固定salt)` → 服务端再 `MD5(哈希+用户salt)`（见 `MD5Util`）。

## 4. 核心架构（秒杀异步主链路）

```
请求路径（快，无 DB 写）:
  POST /miaosha/do_miaosha
    → MiaoshaAcceptService: Redis Lua 原子预扣（防重复 → 查库存 → 扣减+标 PROCESSING）
    → 发 Kafka `seckill-order`（key=UUID 随机打散，不要用 goodsId）
    → 立即返回 PROCESSING

消费路径:
  OrderMessageConsumer → OrderFulfillmentService → MiaoshaService.createOrder（DB 事务）
    → 成功: Redis result = SUCCESS:{orderId}
    → 业务失败(MiaoshaException): Lua 补偿回补库存/清标记 → result = FAILED → ack，不重试
    → 意外异常: 不 ack → 重试 3 次(1s/2s/4s) → 死信 `seckill-order-dlt`

前端: GET /miaosha/result 轮询四态：PROCESSING / SUCCESS:{orderId} / FAILED / 无记录
```

**降级哲学**（改动时必须保持）：
- Redis 不可用 → 受理直接调 `MiaoshaService.createOrder` 同步落库。
- Kafka 发送失败 → 同上降级同步落库；迟到的重复消息由 DB 唯一键拦下后跳过补偿。

**DB 落库事务五步**（`MiaoshaService.createOrder`）：校验时间窗 → 查重 → **条件扣库存 `WHERE stock_count > 0`** → INSERT order_info（拿自增 id）→ INSERT miaosha_order（唯一键 `(user_id, goods_id)` 兜底防重）。

## 5. 测试

- **基座**：`src/test/java/com/example/seckill/support/AbstractIntegrationTest.java` — Testcontainers 单例容器（MySQL 8 + Redis 7 + Kafka），真实 HTTP（RANDOM_PORT），每个用例前 TRUNCATE 全表 + Redis FLUSHALL。
- 集成测试**需要 Docker 运行**；跑不动通常是 Docker 未启动。
- 单元测试走接缝假实现：`cache/InMemoryMiaoshaRedisStore`、`message/FakeOrderMessageSender`。
- `service` 层设计为不依赖 HTTP/Redis/Kafka 实现可直测，新增 service 请保持此接缝风格。

## 6. Agent 硬性规则（改代码前必读）

1. **不用 `user` 表**，登录/用户一律走 `miaosha_user`（其 `id` 是手机号，非自增）。
2. **库存扣减必须条件更新**：`UPDATE ... SET stock_count = stock_count - 1 WHERE goods_id=? AND stock_count > 0`，影响行数 0 → 抛「库存不足」。任何绕过此模式的新扣减逻辑都会超卖。
3. **防重靠「直接 INSERT + 捕获唯一键」**：`miaosha_order` 唯一键 `(user_id, goods_id)` 是最终防线，不要改成「先 SELECT 再判断」。
4. **业务失败抛 `MiaoshaException`**（带 `CodeMsg`），不要包成 `RuntimeException` —— 消费者据此区分「补偿不重试」与「重试+死信」。
5. **消息 key 保持 UUID 随机**，改回 goodsId 会让热点打爆单分区。
6. **回写 Redis（markSuccess/compensate）尽力而为**，不向调用方抛异常；补偿必须用 `requestId` 校验归属，且先查 result 已 SUCCESS 则跳过。
7. **消费者必须手动 ack**：成功与业务失败路径都 ack，仅意外异常上抛。
8. **分区数/副本是 `KafkaConfig` 常量**（3 分区、副本 1、死信 topic 对齐），改动需同步。
9. **`order_info.goods_name/goods_price` 是下单时快照冗余**，不要联表取实时值。
10. **`miaosha_order.order_id` → `order_info.id`**：先插 order_info 拿自增 id，再插 miaosha_order，同一事务。
11. **种子时间窗会过期**：联调报「未开始/已结束」先重跑 `sql/fix-seed-time-window.sql`，不要先怀疑代码。
12. **错误码体系**：见 `CodeMsg` 与 `AbstractIntegrationTest` 顶部常量（如 500212 重复下单、500214 库存空、500215 未开始、500216 已结束），新增错误码加在 `CodeMsg`。
13. **写操作用 POST**（如 `/miaosha/do_miaosha`、`/admin/preheat`），读操作 GET；统一响应壳 `Result{code, msg, data}`，code=0 为成功。
