# AGENTS.md

本文件为 CodeBuddy（Agent）在本仓库工作时提供指导。前端专项指南见 `frontend/AGENTS.md`（Vite 代理、路由守卫、分层约定、错误码映射等），改前端代码前务必先读它。

## 项目概览

基于 Spring Cloud 的秒杀系统微服务（`miaosha-cloud`），核心链路：
**登录 → 商品 → 提交秒杀 → Redis Lua 预扣 → Kafka 异步落库 → 轮询拿单**

- Java 17 / Spring Boot 3.3.4 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba（Nacos）
- MySQL 8 / Redis 7 / Kafka 4.x（KRaft，无 ZooKeeper）
- 网关：Spring Cloud Gateway（WebFlux，响应式，勿混用 Servlet API）
- 前端：Vite + React 18 + TypeScript + Tailwind + shadcn/ui（`frontend/`）

## 常用命令

### 构建与启动（后端）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn clean package -DskipTests            # 全量构建（Maven 多模块，聚合 POM 在根目录）
mvn -pl gateway -am spring-boot:run      # 网关 :8080（唯一公网入口）
mvn -pl user-service -am spring-boot:run   # :8081
mvn -pl goods-service -am spring-boot:run  # :8082
mvn -pl miaosha-service -am spring-boot:run # :8083
mvn -pl order-service -am spring-boot:run   # :8084
```

`-am` 会连带构建依赖的 `common` 模块。先启动中间件再启动服务。

### 测试

各服务为独立 Testcontainers 集成测试，**运行测试前必须先启动 Docker**：

```bash
mvn -pl <module> test                        # 某模块全部测试
mvn -pl order-service test -Dtest=OrderServiceIntegrationTest          # 单个测试类
mvn -pl order-service test -Dtest=OrderServiceIntegrationTest#方法名   # 单个测试方法
```

测试基类：各模块 `src/test/java/.../support/Abstract*IntegrationTest.java`（用 Testcontainers 拉起 MySQL/Redis/Kafka，mock 外部服务调用）。

### 中间件与前端

```bash
docker compose up -d mysql redis kafka nacos   # 先起中间件

cd frontend
npm install
npm run dev      # 开发服务器 http://localhost:5173，/user /goods /miaosha /admin 代理到 :8080
npm run build    # tsc 类型检查 + 生产构建，改前端代码后必须跑通
```

### 配置覆盖

中间件连接均支持环境变量覆盖：`MYSQL_HOST/PORT`、`REDIS_HOST/PORT`（默认密码 123456）、`KAFKA_BOOTSTRAP_SERVERS`、`NACOS_SERVER_ADDR`、`REDIS_PASSWORD`、`GOODS_BASE_URL`、`ORDER_SYNC_BASE_URL`。默认值见各服务 `src/main/resources/application.yml`。

## 架构

### 服务划分与调用关系

| 服务 | 端口 | 职责 |
|---|---|---|
| gateway | 8080 | 统一入口：路由（`lb://` 服务名负载均衡）+ JWT 全局鉴权 |
| user-service | 8081 | 注册/登录，签发 JWT（表 `miaosha_user`） |
| goods-service | 8082 | 商品/库存/秒杀时间窗（表 `goods`） |
| miaosha-service | 8083 | 秒杀受理、Redis Lua 预扣、Kafka Producer |
| order-service | 8084 | Kafka Consumer、订单落库/幂等/结果查询（表 `order_info`、`miaosha_order`） |
| common | — | 共享 `Result`/`CodeMsg`/`JwtUtil`/`MiaoshaException` |

### 鉴权链路（关键约定）

```
前端(Bearer token) → gateway JwtGlobalFilter → 下游服务 UserContextInterceptor
```

- `JwtGlobalFilter`（gateway）：无条件剥离外部传入的 `X-User-Id`（防伪造）→ 白名单 `/user/login`、`/user/register` 放行 → 校验 Bearer token → 通过后下发 `X-User-Id` 头给下游。
- 每个业务服务的 `UserContextInterceptor` 读取 `X-User-Id` 装入 `UserContext`（ThreadLocal）供业务使用。
- **服务间调用不透传用户身份**；`/internal/**` 端点（如 goods-service 的 `InternalGoodsController`、order-service 的 `InternalOrderController`）是服务间同步调用的专用接口，不经过网关，勿对外暴露。
- 路由前缀与网关配置一一对应：`/user/**`→user、`/goods/**`→goods、`/miaosha/**` 与 `/admin/**`→miaosha-service。

### 秒杀核心流程（改秒杀逻辑前必读）

```
miaosha-service                          order-service
├─ MiaoshaAcceptService                  ├─ OrderMessageConsumer（手动 ack）
│   ├─ 时间窗/参数校验                    │   └─ OrderFulfillmentService
│   ├─ Redis Lua 预扣（miaosha_try.lua）  │       ├─ INSERT miaosha_order（UNIQUE 防重）
│   └─ 成功 → Kafka topic                │       ├─ 扣 MySQL 库存（条件更新）
│      `seckill-order`（纯 JSON 消息）    │       └─ 写 Redis 结果
├─ Kafka 挂了 → 降级同步调用              └─ 失败 → 业务补偿后 ack
│   order-service（SyncOrderClient）         意外异常重试 3 次(1s/2s/4s)→死信
└─ MiaoshaResultService 轮询查 Redis 结果    `seckill-order-dlt`
```

关键设计（改动时必须保持）：

- **防超卖**：库存条件更新 `UPDATE goods SET stock_count = stock_count - 1 WHERE goods_id = ? AND stock_count > 0`（goods-service 扣减，含幂等）。
- **防重复下单**：`miaosha_order` 的 `UNIQUE(user_id, goods_id)`，直接 INSERT 捕获冲突，不先查后插。
- **Redis 预扣**：Lua 脚本原子执行（检查 → 防重 → 扣库存 → 置 PROCESSING），脚本在 `miaosha-service/src/main/resources/scripts/`，Redis key 统一由各模块 `RedisKeyBuilder` 构建。
- **降级接缝**：miaosha-service 通过接口 `OrderMessageSender`/`SyncOrderClient` 解耦 Kafka 与 HTTP，便于测试替身（见 `FakeOrderMessageSender` 等测试支持类）。
- **预热**：`MiaoshaPreheatService`（miaosha）与 `StockPreheatService`（goods）经 HTTP 服务名调用同步库存/时间窗到 Redis，管理端触发。
- **时间窗**：`MiaoshaWindowService`（goods/order 各有一份）校验秒杀开始/结束时间，种子数据按 `Asia/Shanghai` +08:00 生成。

### 前端分层（详见 frontend/AGENTS.md）

`src/types/api.ts`（类型契约，1:1 对齐后端 VO，后端改字段先改这里）→ `src/lib/`（axios 拦截器、token、错误码映射，与 `common/CodeMsg` 同码）→ `src/api/`（按领域封装）→ `src/pages/`。页面不直接碰 axios；shadcn/ui 组件用 CLI 追加，不手写。

## 注意事项

- **JVM 时区必须为 `Asia/Shanghai`**，否则时间窗校验 `checkInWindow` 会误判「未开始/已结束」。联调报时间窗错误先怀疑数据/时区，不要先怀疑代码。
- **数据库初始化走 `db/init/01-init.sql`**：docker compose 首次启动 MySQL 时经 `/docker-entrypoint-initdb.d` 自动执行（仅 `./db/mysql_data` 为空时执行一次）。改了 SQL 想重新初始化需 `docker compose down -v` 并清空 `db/mysql_data`。时间窗由 `NOW()` 动态生成，勿写死日期。
- Kafka 消息为纯 JSON（无类型头），生产/消费两侧的 `SeckillOrderMessage` 字段需保持一致（common 化是方向但当前是两份定义）。
- 网关是 WebFlux 响应式栈，与下游 Servlet 栈不同，新增 gateway 代码勿使用 Servlet/ThreadLocal API。
