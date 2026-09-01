# AGENTS.md — order-service（模块级 Agent 工作指南）

> 端口 8084。职责：Kafka Consumer、订单落库/幂等、秒杀结果回写与补偿。
> 表 `order_info`、`miaosha_order`。全局约定见仓库根 `AGENTS.md`（命名、构建、中间件、错误码），本文件只写模块内必读。

## 模块结构

```
src/main/java/com/example/order/
├── cache/      # OrderResultStore 接口 + Redis 实现 + RedisKeyBuilder + RedisScriptConfig
├── client/     # GoodsClient 接口 + HttpGoodsClient（RestClient，调 goods-service 内部接口）
├── common/     # GlobalExceptionHandler
├── config/     # ClockConfig（注入 Clock，测试可换时钟）
├── controller/ # InternalOrderController（/internal/orders/sync，供 miaosha-service 降级同步下单）
├── dao/        # MiaoshaOrderMapper / OrderInfoMapper（XML 在 resources/mapper/）
├── domain/     # MiaoshaOrder / OrderInfo 实体
├── message/    # KafkaConsumerConfig（重试+死信）/ OrderMessageConsumer / SeckillOrderMessage（消费侧副本）
├── service/    # OrderService（落库核心）/ OrderFulfillmentService（消费编排）/ MiaoshaWindowService
└── vo/         # GoodsSnapshotVo（goods-service 商品快照）
src/main/resources/scripts/miaosha_compensate.lua   # 补偿 Lua，与 miaosha-service 双端必须一致
```

## 核心链路（改代码前先理解）

```
消费路径:
  OrderMessageConsumer（@KafkaListener seckill-order，手动 ack）
    → OrderFulfillmentService.fulfill
        ① 幂等快跳: result 已 SUCCESS → 直接返回（不碰 DB）
        ② OrderService.createOrder（落库 Saga）→ markSuccess
        ③ MiaoshaException（业务失败）→ 迟到重复检查 → compensate → 正常返回 → ack（不重试）
        ④ 意外异常 → 上抛不 ack → 容器重试 1s/2s/4s ×3 → DLT seckill-order-dlt

降级路径（Kafka 发送失败时 miaosha-service 走这里）:
  miaosha-service → POST /internal/orders/sync → 同一个 OrderService.createOrder
```

**OrderService.createOrder 编排（Saga）**：取商品快照（GoodsClient）→ 时间窗校验 → 幂等预检（SELECT miaosha_order）→ **远程扣库存**（goods-service 条件更新，影响行数 0 → 库存不足）→ 本地事务 `insertOrderTx`（先 INSERT order_info 拿自增 id，再 INSERT miaosha_order，唯一键 `(user_id, goods_id)` 兜底）。扣库存成功但建单失败 → `restoreStockQuietly` 回补（失败只记日志，不做重试风暴）。

## 接缝设计（新增代码照此风格）

- **不依赖 Redis/Kafka 可测**：`OrderFulfillmentService` 只依赖 `OrderResultStore` 接口；service 层不直接 import Redis/Kafka 具体类。
- **跨服务 HTTP 走接口**：`GoodsClient`（接口）+ `HttpGoodsClient`（RestClient 实现，connect 2s / read 3s 限时，业务码经 `resolveCodeMsg` 还原为 common `CodeMsg`）。不用 OpenFeign。
- **Clock 注入**：时间取 `LocalDateTime.now(clock)`，不直接 `LocalDateTime.now()`。

## 硬性规则

1. **Redis 回写尽力而为**：`markSuccess/compensate/getResult` 吞异常不向上抛——Redis 不可用时订单仍必须能落库，幂等由 DB 唯一键兜底（见 `RedisOrderResultStore`）。
2. **补偿 Lua 校验 requestId 归属**（`miaosha_compensate.lua`）：user 标记 == requestId 才回补库存、标 FAILED。此脚本与 miaosha-service 是双端副本，改动必须两端同步。
3. **Redis Key 规则双端一致**：`RedisKeyBuilder` 复制自 miaosha-service，改 key 格式必须两端同步。本服务只允许写 result / 回补 stock / 删 user 标记，预扣 key 归 miaosha-service。
4. **事务必须经 `self` 代理调用**：`OrderService` 注入 `@Lazy OrderService self`，`insertOrderTx` 直接 this 调用会使 `@Transactional` 失效。
5. **手动 ack 语义**：正常返回与业务失败都 ack；仅意外异常上抛不 ack（`OrderMessageConsumer`）。不要改成自动提交。
6. **死信模板专用**：DLT 发布必须用 `dltKafkaTemplate`（JsonSerializer 关类型头），不能用 Boot 自动配置的 String 模板，否则毒消息永远进不了死信。
7. **topic 声明归生产侧**：本服务不创建 NewTopic（miaosha-service 负责）。
8. **消息反序列化靠 `spring.json.value.default.type`**：producer 发纯 JSON 无类型头，改消息体字段需同步 miaosha-service 的 producer 副本。
9. **快照语义**：`order_info.goods_name/goods_price` 是下单时快照，不要联表取实时值。
10. **内部接口返回 HTTP 200 + Result.error(code)**：业务失败经 `GlobalExceptionHandler` 统一处理，调用方按 code 还原语义。

## 测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # maven-enforcer 强制 JDK 17
mvn -pl order-service -am test                     # 全部测试（需 Docker）
mvn -pl order-service test -Dtest=OrderKafkaConsumerTest          # 单个类
mvn -pl order-service test -Dtest=ClassX#methodY                  # 单个方法
```

- **基座**：`support/AbstractOrderIntegrationTest` — Testcontainers 单例容器（MySQL 8 + Redis 7 + Kafka），goods-service 不启动，`GoodsClient` 用 `@MockBean` 打桩（内存 CAS 库存 + 调用计数，断言「扣减不重复 / 补偿恰好一次」）。每用例前 TRUNCATE + Redis FLUSHALL。
- 测试生产者与真实 producer 契约一致：纯 JSON、无类型头、key 用 UUID。
- 集成测试跑不动先检查 Docker 是否启动。

## 本地运行

```bash
mvn -pl order-service -am spring-boot:run    # 依赖 localhost 的 MySQL/Redis/Kafka，另需 goods-service 在 8082
```
中间件：仓库根 `docker compose up -d mysql redis kafka`；新库先跑 `backend/sql/fix-seed-time-window.sql`。连接可用 `MYSQL_HOST/PORT`、`REDIS_HOST/PORT`、`KAFKA_BOOTSTRAP_SERVERS`、`GOODS_BASE_URL` 覆盖。
