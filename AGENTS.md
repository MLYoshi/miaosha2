# AGENTS.md — 秒杀系统微服务化项目

## 项目

将单体秒杀系统（`backend/`）逐步改造为 Spring Cloud 微服务。技术栈：Java 17（强制）、Spring Boot 3.3.4、MySQL 8、Redis 7、Kafka 4.x。

核心链路（全程不能变）：

```
登录 → 商品 → 提交秒杀 → Redis 预扣 → Kafka 异步落库 → 轮询拿单
```

## 服务

| 服务 | 端口 | 职责 |
|---|---|---|
| gateway | 8080 | 路由 + JWT 校验（X-User-Id 下发）+ lb:// 服务名负载均衡。禁止业务逻辑/DB/Redis/Kafka；WebFlux 技术栈，禁止加 `spring-boot-starter-web` |
| user-service | 8081 | 用户/登录/注册，签发 JWT。表 `miaosha_user`（不用 `user` 表） |
| goods-service | 8082 | 商品/库存/秒杀时间窗。表 `goods` |
| miaosha-service | 8083 | 秒杀受理、Redis Lua 预扣、Kafka Producer。不负责落库 |
| order-service | 8084 | Kafka Consumer、订单落库/幂等。表 `order_info`、`miaosha_order` |
| common | — | 只放共享 DTO/Result/工具/JwtUtil。禁止放 Entity、Mapper、业务 Service |

## 核心规则

1. **`backend/` 是事实基线**：业务行为、DB schema、Redis Lua、Kafka 语义的唯一来源。迁移全程不要删除/重构它，每一步保持原有行为，不做顺手重构。
2. **数据所有权**：服务只能访问自己的表，跨服务只能通过 HTTP / Kafka 通信，禁止 import 其他服务的 Entity/Mapper。
3. **Redis**：秒杀 Key（`miaosha:stock|user|result:*`）分区归属——预扣 Key（`miaosha:stock:*`、`miaosha:user:*`）只由 miaosha-service 维护；结果 Key（`miaosha:result:*`）的回写与补偿（markSuccess/compensate/getResult，补偿 Lua 校验 requestId 归属后才回补库存）归 order-service，尽力而为、失败不得破坏订单事实；其他服务不得私自修改任何秒杀 Key。另有 goods-service 自有的扣减幂等 Key（`goods:deduct:req:{requestId}`，缓存扣减影响行数、TTL 60s，解决扣减响应丢失 × Kafka 重放重复扣减），尽力而为、Redis 故障时降级直接扣减；不属秒杀 Key，不受上述归属约束。
4. **Kafka**：topic `seckill-order`（DLT: `seckill-order-dlt`）。消息只带必要字段（`requestId`/`userId`/`goodsId`），key 用 UUID 随机打散。发送失败必须有同步落库降级。
5. **不要过度设计**：按迁移计划逐步引入组件，OpenFeign 目前明确不做；服务注册与发现已启用 Nacos（Step 6），路由与跨服务调用统一走服务名负载均衡，不再写死 localhost 端口。

## 秒杀不变量（绝对不能破坏）

- **防超卖**：库存扣减必须是条件更新 `UPDATE goods SET stock_count = stock_count - 1 WHERE goods_id = ? AND stock_count > 0`，影响行数 0 → 库存不足。
- **防重复**：靠 `miaosha_order` 的 `UNIQUE(user_id, goods_id)` + 直接 INSERT 捕获冲突，不要改成先 SELECT 再判断。
- **Redis 预扣**：Lua 脚本保持原子（检查 → 防重 → 扣库存 → 置 PROCESSING）。
- **Consumer**：手动 ack。业务失败 → 补偿后 ack 不重试；意外异常 → 不 ack，重试 3 次（1s/2s/4s）→ 死信 topic。

## 构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS 强制 Java 17（maven-enforcer 校验）

# 微服务（根目录多模块，-am 自动带上依赖的 common）
mvn clean package -DskipTests
mvn -pl <module> -am spring-boot:run

# 跑测试（Testcontainers 集成测试，必须先启动 Docker）
mvn -pl <module> test                       # 某模块全部测试
mvn -pl <module> test -Dtest=OrderKafkaConsumerTest   # 单个测试类
mvn -pl <module> test -Dtest=ClassX#methodY           # 单个测试方法

# 旧单体（独立构建，与微服务互不影响）
cd backend && mvn -B package -DskipTests
```

## 本地中间件

`docker compose up -d mysql redis kafka nacos`：MySQL 3306（root/root，库 `miaosha`，容器名 `seckill-mysql`）、Redis 6379（密码 123456）、Kafka 9092（KRaft 单节点，auto-create 关闭）、Nacos 8848（HTTP，容器名 `seckill-nacos`，客户端 gRPC 9848，standalone 免鉴权）。

```bash
# 新库必做：建表 + 种子数据 + 时间窗对齐（否则秒杀窗口全是 2017 年，接口报「已结束」）
docker exec -i seckill-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
  < backend/sql/fix-seed-time-window.sql
```

- 中间件连接均可用环境变量覆盖：`MYSQL_HOST/PORT`、`REDIS_HOST/PORT`、`KAFKA_BOOTSTRAP_SERVERS`、`NACOS_SERVER_ADDR`、`GOODS_BASE_URL`、`ORDER_SYNC_BASE_URL`。
- **时区陷阱**：JVM 必须运行在 `Asia/Shanghai`，否则时间窗校验 `checkInWindow` 误判（容器内 compose 已设 `TZ`）。
- 联调报「未开始/已结束」→ 先重跑上述种子脚本，不要先怀疑代码。更多细节见 `backend/AGENTS.md` 与 `docs/`。

## 命名约定

代码中「秒杀」统一用 `Miaosha` 前缀（类名、mapper、Redis key），不要改成 `Seckill`。唯一例外：Kafka 消息体 `SeckillOrderMessage`（历史命名，新旧两侧各有一份消费/生产副本，字段仅 `requestId`/`userId`/`goodsId`）。

## 跨服务协作要点（读多个文件才能发现的）

- **HTTP 互调不用 OpenFeign**：order-service 通过 `client/GoodsClient`（接口）+ `client/HttpGoodsClient`（RestClient 实现）调 goods-service 的 `InternalGoodsController` 内部接口（商品快照/扣库存/回补库存），地址由 `goods.base-url` 配置。新增跨服务调用照此接缝风格。
- **Kafka 消息不带类型头**：miaosha-service producer 发纯 JSON，order-service consumer 靠 `spring.json.value.default.type` 指定消费侧消息类（见 order-service `application.yml`）。
- **gateway 路由（服务名 lb://）**：`/user/**`→lb://user-service、`/goods/**`→lb://goods-service、`/miaosha/**` 与 `/admin/**`→lb://miaosha-service，经 Nacos 服务发现 + Spring Cloud LoadBalancer 分发，不再写死 localhost 端口。
- **JWT 鉴权已上移 gateway**：`filter/JwtGlobalFilter` 无条件剥离外部伪造的 `X-User-Id`，校验 `Authorization: Bearer` 成功后下发 `X-User-Id: {userId}` 给下游；白名单 `/user/login`、`/user/register` 放行；失败返回 401 + Result 同构 JSON（`CodeMsg.SESSION_ERROR`）。
- **业务服务身份上下文**：user/goods/miaosha 的 `JwtInterceptor` 已换成 `UserContextInterceptor`（读 `X-User-Id` → `request.setAttribute("userId")`），Controller 取值方式不变；goods 排除 `/internal/**`，user 排除登录/注册，miaosha 全量拦截；order-service 无拦截器（仅内部端点）。
- **服务间调用用 @LoadBalanced RestClient**：miaosha→goods（`client/GoodsClient`，预热，携带 `X-User-Id: 0` 服务身份）、miaosha→order（`client/HttpSyncOrderClient`，同步降级）、order→goods（`client/HttpGoodsClient`，内部接口无鉴权头）；baseUrl 改为服务名 `http://goods-service` / `http://order-service`，超时（connect 2s / read 3s）、异常处理、业务码还原全部保留。服务直连端口 8081-8084 属内部网络，gateway（8080）是唯一公网入口。
- **测试基座**：各服务有独立 Testcontainers 基座（order-service 为 `support/AbstractOrderIntegrationTest`，真实 HTTP + 容器内 MySQL/Redis/Kafka，每用例清库）。跑不动通常是 Docker 未启动。
- **错误码体系**：统一加在 common 的 `CodeMsg`（如 500212 重复下单、500214 库存空、500215 未开始、500216 已结束）。
