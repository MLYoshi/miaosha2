# AGENTS.md — 秒杀系统微服务化项目（根级 Agent 工作指南）

> 本文件是 AI 编码助手在本仓库工作时的**最高层导航与规则**。它定义项目是什么、架构是什么、服务边界在哪里、哪些原则绝对不能破坏。细节设计在 `docs/` 与各服务目录下的文档中，本文件负责告诉你"应该去哪里看"。

## 1. 项目简介与架构

本项目是将一个 Spring Boot **单体秒杀系统**（`backend/`）逐步改造为 **Spring Cloud 微服务架构**的迁移工程。

核心业务链路（迁移全程必须保持不变）：

```
登录 → 商品列表/详情 → 提交秒杀 → Redis 预扣 → Kafka 异步落库 → 前端轮询拿单
```

技术栈：Java 17（强制）、Spring Boot 3.3.4、Spring Cloud 2023.0.3、MyBatis（XML mapper）、MySQL 8、Redis 7、Kafka 4.x（KRaft，无 ZooKeeper）、JWT、Testcontainers。

仓库布局：

```
code2/
├── AGENTS.md            # 本文件（根级规则）
├── pom.xml              # 根 POM：packaging=pom，统一版本管理，声明 6 个 modules
├── common/              # 跨服务共享契约/基础设施（空壳，待迁移填充）
├── gateway/             # 统一 API 入口        :8080
├── user-service/        # 用户/登录/JWT         :8081
├── goods-service/       # 商品/库存             :8082
├── miaosha-service/     # 秒杀受理（高并发入口）:8083
├── order-service/       # 订单落库（Kafka 消费） :8084
├── backend/             # 旧单体迁移基线（独立构建，禁止删除）
├── docs/                # 微服务化设计文档
└── docker-compose.yaml # 本地中间件 + 旧单体 backend 容器
```

注意：`backend/` 是独立构建单元（有自己的 parent POM），**不在** 根 POM 的 modules 里。微服务全部迁移完成前不要删除、不要重构 `backend/`。

## 2. 迁移阶段

迁移总原则：

- **旧单体 `backend/` 是事实基线**（业务行为、DB schema、Redis Lua、Kafka 语义的唯一事实来源）。
- 微服务**逐步**迁移，每一步都必须保持原有业务行为，不做顺手重构、不改 API 语义。


## 3. 服务边界（最终目标）

每个服务的职责与红线。**修改任何代码前先确认它属于哪个服务。**

### gateway（:8080）

职责：统一 API 入口、路由；后续统一鉴权、限流。

禁止：任何业务逻辑、数据库访问、秒杀库存操作、订单创建。
（技术上基于 Spring Cloud Gateway / WebFlux，**禁止**混入 `spring-boot-starter-web`，否则启动冲突。）

### user-service（:8081）

负责：用户、注册、登录、JWT 签发与校验、密码校验、用户信息。
主要数据表：`miaosha_user`（注意：**不用** `user` 表，其 id 是手机号非自增）。

禁止：直接访问 goods/order 数据；处理秒杀库存；创建订单。

### goods-service（:8082）

负责：商品、商品详情、商品库存、秒杀时间窗、库存预热。
主要数据表：`goods`。

### miaosha-service（:8083）

负责：秒杀请求受理（**高并发请求入口**）、Redis Lua 原子预扣、防重复、PROCESSING 状态、Kafka Producer、秒杀结果状态。

**它不负责最终订单落库**——落库由 order-service 消费 Kafka 完成。

### order-service（:8084）

负责：Kafka Consumer、订单创建、订单数据、订单幂等、最终订单状态、失败补偿流程。
主要数据表：`order_info`、`miaosha_order`（唯一键 `(user_id, goods_id)` 兜底防重）。

### common

只存放**真正跨服务共享**的内容：

- 通用 DTO / 通用 Result 响应壳
- 基础异常、基础工具
- 确实跨服务需要的共享常量

明确禁止放入：`GoodsEntity` / `OrderEntity` / `UserEntity`、Mapper、Service、Repository 等**业务实现**。

**common ≠ shared business layer**。common 只是 shared infrastructure / shared contracts。一旦某个类只有一个服务使用，优先放回对应 service。

## 4. 数据所有权

核心规则：

> **一个服务拥有自己的业务数据，其他服务不得直接访问其数据库表。**

**任何服务不得直接读写不属于自己的表**。

跨服务数据必须通过服务间通信完成：HTTP / RPC / Kafka。

## 5. Redis 边界

当前秒杀核心 Redis Key（沿用旧单体命名）：

```
miaosha:stock:{goodsId}
miaosha:user:{goodsId}:{userId}
miaosha:result:{goodsId}:{userId}
```

规则：

> **这些秒杀 Redis Key 由 `miaosha-service` 负责维护**（预扣、补偿、结果回写）。

goods-service、order-service、user-service **不得**绕过 miaosha-service 私自修改秒杀 Redis 状态。Redis 回写（markSuccess/compensate）尽力而为，不向调用方抛异常；补偿必须用 `requestId` 校验归属，且先查 result 已 SUCCESS 则跳过。

Redis 连接信息：密码 123456（见 docker-compose.yaml）。

## 6. Kafka 边界

当前核心消息 topic：`seckill-order`，死信 topic `seckill-order-dlt`。

```
miaosha-service  →  Producer
        ↓
     Kafka
        ↓
order-service    →  Consumer
```

消息设计规则：

- 消息体只带必要业务字段：`requestId`、`userId`、`goodsId` 等。**不要把整个实体对象塞进 Kafka**。
- 消息 key 保持 UUID 随机打散，**不要改回 goodsId**（会让热点打爆单分区）。
- 分区数/副本等是 `KafkaConfig` 常量（3 分区、副本 1、死信 topic 对齐），改动需同步。
- 消息发送失败必须有**同步降级路径**（直接落库），这是现有降级哲学的一部分。

## 7. 秒杀核心不变量（任何改造不能破坏）

以下是项目核心业务规则，微服务化改造中**绝对不能破坏**：

### 防超卖

数据库最终库存扣减必须保持条件更新：

```sql
UPDATE goods
SET stock_count = stock_count - 1
WHERE goods_id = ?
  AND stock_count > 0
```

影响行数为 0 → 抛「库存不足」。任何绕过此模式的扣减逻辑都会超卖。

### 防重复

最终数据库约束：`miaosha_order` 表 `UNIQUE(user_id, goods_id)`。
防重靠「直接 INSERT + 捕获唯一键冲突」，不要改成「先 SELECT 再判断」。

### Redis 预扣原子性

Redis Lua 脚本必须保持原子执行：

```
检查 → 防重复 → 扣库存 → 置 PROCESSING
```

### Kafka 降级

Kafka 发送失败时必须存在同步落库降级路径；Redis 不可用时受理直接同步落库。迟到的重复消息由 DB 唯一键拦下后跳过补偿。

### Consumer 语义

- 业务失败（`MiaoshaException` 类）：补偿 → ack，**不重试**。
- 意外异常：不 ack → 重试 3 次（1s/2s/4s）→ 死信 topic（DLT）。
- 消费者必须手动 ack：成功与业务失败路径都 ack，仅意外异常上抛。

### 其他单体基线规则

旧单体的完整硬性规则（表模型、错误码、时区陷阱等）见 `backend/AGENTS.md` §6，迁移对应领域前必读。

## 8. 禁止跨服务耦合

不要出现：

```
order-service  →  import goods-service 内部 Entity
order-service  →  直接引用 goods-service 的 Mapper
两个 service 共享一个业务 Service
```

正确方式：

```
Service A → HTTP / RPC / Kafka → Service B
```

即：服务之间只能通过 API 契约（DTO）和消息通信，不能引用彼此的内部实现。

## 9. 构建与测试

```bash
# ⚠️ Java 17 是强制版本。macOS 上 Maven 可能误用 Homebrew 新版 JDK，先：
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 微服务多模块（根目录执行）
mvn clean package -DskipTests    # 全量构建
mvn test                          # 测试
mvn -pl <module> spring-boot:run  # 单个服务本地启动

# 旧单体（独立构建）
cd backend && mvn -B package -DskipTests
```


## 10. 本地运行

中间件（根目录 `docker compose up -d mysql redis kafka`）：

- MySQL 8.0：localhost:3306，root/root，库 `miaosha`
- Redis 7：localhost:6379，密码 123456
- Kafka 4.x（KRaft）：宿主机 localhost:9092，容器间 kafka:9094

注意：

- `backend/` 是旧单体迁移基线，**微服务全部迁移完成前不要删除**。

- 种子数据脚本与时间窗陷阱见 `backend/AGENTS.md` §3（种子时间窗会过期，联调报「未开始/已结束」先重跑 `backend/sql/fix-seed-time-window.sql`）。

## 11. 不要过度设计

原则：

> **按迁移计划逐步增加基础设施，不一次性把 Spring Cloud 全家桶全部引入。**

只有当某个 Step 明确要求时才引入对应组件。服务发现（OpenFeign / 注册中心）目前在"明确不做"清单里，不要提前加。

