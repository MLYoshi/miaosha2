# miaosha-service AGENTS.md — 秒杀受理服务（端口 8083）

> 全局规则见根目录 `AGENTS.md`（数据所有权、命名约定、不变量），本文件只补充本模块细节。

## 职责边界

只做三件事：**秒杀受理**（Redis Lua 预扣 → Kafka 发消息 → 立即返回受理态）、**库存预热**、**结果轮询（读 Redis 契约）**。

- **不落库、无 DB 依赖**：pom 里没有 MySQL/MyBatis。订单落库、结果 Key（`miaosha:result:*`）的 SUCCESS 回写、Consumer 错误处理全部归 order-service。
- Redis 预扣 Key（`miaosha:stock:*`、`miaosha:user:*`）只由本服务维护；结果 Key 本服务只**置 PROCESSING / FAILED**（在 Lua 内），绝不写 SUCCESS。
- 受理热路径**不查商品**（F9 固化）：库存 key 不存在（未预热）→ 直接返回 500214 库存不足，不做商品存在性前置校验。

## 构建与测试

```bash
# 在仓库根目录执行（多模块，-am 自动带上 common）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl miaosha-service -am spring-boot:run          # 启动（需 Redis/Kafka，见 docker-compose）
mvn -pl miaosha-service test                          # 全部测试（需 Docker：Testcontainers）
mvn -pl miaosha-service test -Dtest=MiaoshaAcceptServiceTest        # 单个测试类
mvn -pl miaosha-service test -Dtest=MiaoshaAcceptServiceTest#方法名  # 单个测试方法
```

## 架构：接缝（Port）+ 适配器

核心编排类 `MiaoshaAcceptService.execute(userId, goodsId)` 是唯一入口，不依赖任何中间件实现，三个接缝均可注入假适配器直测：

| 接缝 | 生产实现 | 测试假适配器 |
|---|---|---|
| `cache/MiaoshaRedisStore` | `RedisMiaoshaStore`（Lua） | `support/InMemoryMiaoshaRedisStore` |
| `message/OrderMessageSender` | `OrderMessageProducer`（Kafka） | `support/FakeOrderMessageSender` |
| `client/SyncOrderClient` | `HttpSyncOrderClient`（RestClient） | `support/FakeSyncOrderClient` |

跨服务 HTTP 客户端（均带 2s 连接 / 3s 读超时）：

- `client/GoodsClient` → goods-service `GET /goods/detail/{id}`，**仅预热链路使用**。goods-service JWT 全量拦截，故用 common `JwtUtil` 按请求签发服务令牌（固定身份 userId=0，避免 token 过期）。
- `client/HttpSyncOrderClient` → order-service `POST /internal/orders/sync`（降级同步下单）。业务码非 0 时还原为 common `CodeMsg` 异常，保持与单体一致的用户可见语义。
- 响应壳均为手写的 Result 同构 DTO（`GoodsDetailResponse` / `SyncOrderResponse`），**禁止 import 其他服务的 Entity/VO**。

地址配置：`goods.base-url`、`order.sync-base-url`（`application.yml`，可用 `GOODS_BASE_URL` / `ORDER_SYNC_BASE_URL` 环境变量覆盖）。

## 受理流程与降级哲学（多层兜底）

```
tryMiaosha(Lua 原子: 防重 → 查库存 → DECR → 置 PROCESSING)
├─ REPEAT → 抛 500212；STOCK_EMPTY → 抛 500214
├─ Redis 异常 → 降级 SyncOrderClient 同步下单 → 返回直接拿单
└─ OK → sender.send(SeckillOrderMessage) → 返回「受理中」
    └─ Kafka 发送失败 → 降级同步下单
        └─ 降级也失败 → store.compensate(回补库存) 后上抛
```

原则：**绝不产生「已扣库存 + 永久失败 + 无补偿」**。`compensate` 的 Lua 校验 user key 仍是本次 `requestId` 才回补（防误操作其他请求），且**不向调用方抛异常**（补偿失败不能破坏上抛语义）。

`requestId`（UUID 无横线）贯穿全链路：Lua 标记、Kafka 消息字段、补偿归属校验。

## Kafka 生产侧

- topic `seckill-order`（3 分区，`KafkaConfig` 自动声明；DLT `seckill-order-dlt` 本服务不发）。
- 消息体 `SeckillOrderMessage` 仅 `{requestId, userId, goodsId}`；`acks=all` + 幂等生产者 + 阻塞 `.join()` 等确认。
- **消息 key 用随机 UUID，不用 goodsId**（热点商品会打爆单分区）；顺序性由 order-service DB 条件扣库存 + 唯一键兜底。
- `spring.json.add.type.headers: false`：发纯 JSON，消费侧靠 `spring.json.value.default.type` 指定类型。

## Redis Key 与 Lua

`cache/RedisKeyBuilder` 生成三个 key；Lua 脚本在 `src/main/resources/scripts/`：

- `miaosha_try.lua`：返回 0=成功 / 1=库存不足（含未预热）/ 2=重复。顺序是**先查用户标记再查库存**。
- `miaosha_compensate.lua`：requestId 匹配才 INCR 库存、删标记、置 FAILED。

修改脚本语义时必须同步 `MiaoshaRedisStore.TryResult` 的返回码映射（0→OK, 2→REPEAT, 其余→STOCK_EMPTY）和两个测试假适配器。

## 测试基座

`support/AbstractIntegrationTest`：Testcontainers 单例 Redis 7 + Kafka（KRaft），真实 HTTP（RANDOM_PORT），**无 MySQL 容器**。每用例 Redis FLUSHALL；Kafka 隔离靠 goodsId 过滤 + requestId 去重（各用例用不同 goodsId）。`goods.base-url` / `order.sync-base-url` 指向死端口（`http://localhost:1`），预热在测试内经 `store.setStock` 直写。

测试分布：`MiaoshaAcceptServiceTest`（接缝直测）、`MiaoshaAcceptApiTest` / `MiaoshaConcurrencyTest` / `MiaoshaDegradeKafkaTest` / `MiaoshaRedisDownTest`（集成：API/并发/降级/Redis 挂）。

## 其他约定

- `Clock` 经 `config/ClockConfig` 注入（预热 TTL 计算用），测试可注入固定时钟，不要直接 `LocalDateTime.now()`。
- Controller 只做 HTTP 翻译；`MiaoshaException` 统一由 `common/GlobalExceptionHandler` 转 `Result.error`，Controller 不做二次转换。管理动作挂 `/admin/**`（`AdminController`）。
- 预热 TTL：活动结束 + 30 分钟 buffer，最小 1 小时，endDate 缺省 1 天。
- JVM 时区必须 `Asia/Shanghai`（时间窗校验），本地中间件 `docker compose up -d mysql redis kafka`。
