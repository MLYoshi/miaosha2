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
| gateway | 8080 | 只做路由。禁止业务逻辑/DB；WebFlux 技术栈，禁止加 `spring-boot-starter-web` |
| user-service | 8081 | 用户/登录/JWT。表 `miaosha_user`（不用 `user` 表） |
| goods-service | 8082 | 商品/库存/秒杀时间窗。表 `goods` |
| miaosha-service | 8083 | 秒杀受理、Redis Lua 预扣、Kafka Producer。不负责落库 |
| order-service | 8084 | Kafka Consumer、订单落库/幂等。表 `order_info`、`miaosha_order` |
| common | — | 只放共享 DTO/Result/工具。禁止放 Entity、Mapper、业务 Service |

## 核心规则

1. **`backend/` 是事实基线**：业务行为、DB schema、Redis Lua、Kafka 语义的唯一来源。迁移全程不要删除/重构它，每一步保持原有行为，不做顺手重构。
2. **数据所有权**：服务只能访问自己的表，跨服务只能通过 HTTP / Kafka 通信，禁止 import 其他服务的 Entity/Mapper。
3. **Redis**：秒杀 Key（`miaosha:stock|user|result:*`）只由 miaosha-service 维护，其他服务不得私自修改。
4. **Kafka**：topic `seckill-order`（DLT: `seckill-order-dlt`）。消息只带必要字段（`requestId`/`userId`/`goodsId`），key 用 UUID 随机打散。发送失败必须有同步落库降级。
5. **不要过度设计**：按迁移计划逐步引入组件，OpenFeign/注册中心目前明确不做。

## 秒杀不变量（绝对不能破坏）

- **防超卖**：库存扣减必须是条件更新 `UPDATE goods SET stock_count = stock_count - 1 WHERE goods_id = ? AND stock_count > 0`，影响行数 0 → 库存不足。
- **防重复**：靠 `miaosha_order` 的 `UNIQUE(user_id, goods_id)` + 直接 INSERT 捕获冲突，不要改成先 SELECT 再判断。
- **Redis 预扣**：Lua 脚本保持原子（检查 → 防重 → 扣库存 → 置 PROCESSING）。
- **Consumer**：手动 ack。业务失败 → 补偿后 ack 不重试；意外异常 → 不 ack，重试 3 次（1s/2s/4s）→ 死信 topic。

## 构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS 强制 Java 17

# 微服务（根目录）
mvn clean package -DskipTests
mvn -pl <module> spring-boot:run

# 旧单体（独立构建）
cd backend && mvn -B package -DskipTests
```

## 本地中间件

`docker compose up -d mysql redis kafka`：MySQL 3306（root/root，库 `miaosha`）、Redis 6379（密码 123456）、Kafka 9092。

联调报「未开始/已结束」→ 重跑 `backend/sql/fix-seed-time-window.sql`。更多细节见 `backend/AGENTS.md` 与 `docs/`。
