# Miaosha2 — 秒杀系统微服务化项目

基于 Spring Cloud 的秒杀系统，由单体架构（`backend/`）逐步改造为微服务架构。

## 技术栈

- Java 17 / Spring Boot 3.3.4 / Spring Cloud（Nacos 服务发现 + LoadBalancer）
- MySQL 8 / Redis 7 / Kafka 4.x（KRaft）
- Gateway：Spring Cloud Gateway（WebFlux）

## 核心链路

```
登录 → 商品 → 提交秒杀 → Redis 预扣 → Kafka 异步落库 → 轮询拿单
```

## 架构图

### 整体架构

```mermaid
flowchart TB
    subgraph Client["客户端"]
        WEB["前端 Vite + React<br/>localhost:5173"]
    end

    subgraph MW["中间件"]
        NACOS[("Nacos<br/>服务注册/发现")]
        MYSQL[("MySQL 8<br/>miaosha_user / goods<br/>order_info / miaosha_order")]
        REDIS[("Redis 7<br/>库存预扣 / 秒杀结果")]
        KAFKA{{"Kafka 4.x (KRaft)<br/>topic: seckill-order<br/>dlt: seckill-order-dlt"}}
    end

    subgraph SVC["微服务"]
        GW["gateway :8080<br/>路由 + JWT 鉴权<br/>下发 X-User-Id"]
        USER["user-service :8081<br/>注册/登录/JWT 签发"]
        GOODS["goods-service :8082<br/>商品/库存/时间窗"]
        MIAO["miaosha-service :8083<br/>秒杀受理 / Lua 预扣<br/>Kafka Producer"]
        ORDER["order-service :8084<br/>Kafka Consumer<br/>订单落库/幂等"]
    end

    WEB -->|"HTTP / Bearer Token"| GW
    GW -->|"/user/**"| USER
    GW -->|"/goods/**"| GOODS
    GW -->|"/miaosha/** · /admin/**"| MIAO

    MIAO -.->|"预扣 Lua 原子执行"| REDIS
    MIAO -->|"异步下单消息"| KAFKA
    MIAO -.->|"Kafka 不可用降级同步调用"| ORDER
    KAFKA -->|"手动 ack · 重试 1s/2s/4s"| ORDER
    ORDER -->|"条件更新扣库存<br/>/internal/**"| GOODS
    ORDER -->|"结果写回 Redis"| REDIS
    ORDER --> MYSQL
    GOODS --> MYSQL
    USER --> MYSQL
    MIAO -.->|"预热：库存/时间窗"| GOODS

    SVC -.->|"注册与发现"| NACOS
```

### 秒杀核心链路

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant G as gateway
    participant M as miaosha-service
    participant R as Redis
    participant K as Kafka
    participant O as order-service
    participant DB as MySQL

    C->>G: POST /miaosha/do_miaosha (Bearer token)
    G->>G: 剥离伪造 X-User-Id → 校验 JWT → 下发 X-User-Id
    G->>M: 转发请求
    M->>M: 参数校验 + 秒杀时间窗校验
    M->>R: 执行 miaosha_try.lua（校验 → 防重 → 扣库存 → 置 PROCESSING）
    alt 预扣失败（售罄/重复/未开始）
        R-->>M: 失败码
        M-->>C: 直接返回失败，不落库
    else 预扣成功
        R-->>M: SUCCESS
        M-->>C: 立即返回「排队中」
        M->>K: 发送 seckill-order 消息
        K->>O: 投递（Consumer 手动 ack）
        O->>DB: INSERT miaosha_order（UNIQUE 防重）
        O->>DB: UPDATE goods SET stock_count = stock_count - 1 WHERE stock_count > 0
        O->>R: 写入秒杀结果（订单号 / 失败原因）
        O-->>K: ack（业务失败补偿后 ack；意外异常重试 3 次 → 死信）
        C->>M: 轮询 /miaosha/result
        M->>R: 查询结果
        M-->>C: 成功（订单号）或失败原因
    end
```

## 界面截图

| 秒杀会场（商品列表） | 商品详情 | 管理端（预热/重置） |
|---|---|---|
| ![秒杀会场](docs/screenshots/goods-list.png) | ![商品详情](docs/screenshots/goods-detail.png) | ![管理端](docs/screenshots/admin-dashboard.png) |

## 服务划分

| 服务 | 端口 | 职责 |
|---|---|---|
| gateway | 8080 | 统一入口：路由 + JWT 校验（`X-User-Id` 下发）+ `lb://` 服务名负载均衡 |
| user-service | 8081 | 用户/登录/注册，签发 JWT（表 `miaosha_user`） |
| goods-service | 8082 | 商品/库存/秒杀时间窗（表 `goods`） |
| miaosha-service | 8083 | 秒杀受理、Redis Lua 预扣、Kafka Producer |
| order-service | 8084 | Kafka Consumer、订单落库/幂等（表 `order_info`、`miaosha_order`） |
| common | — | 共享 DTO/Result/JwtUtil 等公共组件 |

## 关键设计

- **防超卖**：库存条件更新 `UPDATE goods SET stock_count = stock_count - 1 WHERE goods_id = ? AND stock_count > 0`
- **防重复**：`miaosha_order` 的 `UNIQUE(user_id, goods_id)` + 直接 INSERT 捕获冲突
- **Redis 预扣**：Lua 脚本原子执行（检查 → 防重 → 扣库存 → 置 PROCESSING）
- **Kafka**：topic `seckill-order`（死信 `seckill-order-dlt`），Consumer 手动 ack，业务失败补偿后 ack，意外异常重试 3 次（1s/2s/4s）后进死信
- **服务发现**：Nacos 注册与发现，路由与跨服务调用统一走服务名负载均衡

## 快速开始

### 1. 环境要求

- JDK 17
- Docker & Docker Compose

### 2. 启动中间件

```bash
docker compose up -d mysql redis kafka nacos
```

### 3. 初始化数据库

无需手动执行：MySQL 首次启动时会自动执行 `db/init/01-init.sql`（建库 + 建表 + 种子数据），秒杀时间窗由 `NOW()` 动态生成，任何时刻初始化都在窗口内。

> 注意：`/docker-entrypoint-initdb.d` 仅在 `./db/mysql_data` 数据目录为空时执行一次。修改 `01-init.sql` 后需重新初始化：
>
> ```bash
> docker compose down -v
> rm -rf ./db/mysql_data
> docker compose up -d
> ```

内置测试账号：手机号 `13800000001` ~ `13800000005`，密码均为 `123456`。

### 4. 构建与启动微服务

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

mvn clean package -DskipTests
mvn -pl gateway -am spring-boot:run      # 8080 唯一公网入口
mvn -pl user-service -am spring-boot:run # 8081
mvn -pl goods-service -am spring-boot:run
mvn -pl miaosha-service -am spring-boot:run
mvn -pl order-service -am spring-boot:run
```

### 5. 访问

所有外部请求经 gateway（`http://localhost:8080`），路由：

- `/user/**` → user-service（登录 `/user/login`、注册 `/user/register` 免鉴权）
- `/goods/**` → goods-service
- `/miaosha/**`、`/admin/**` → miaosha-service

## 测试

各服务有独立 Testcontainers 集成测试（需先启动 Docker）：

```bash
mvn -pl <module> test                        # 某模块全部测试
mvn -pl <module> test -Dtest=ClassX#methodY  # 单个测试方法
```

## 配置覆盖

中间件连接均支持环境变量覆盖：`MYSQL_HOST/PORT`、`REDIS_HOST/PORT`、`KAFKA_BOOTSTRAP_SERVERS`、`NACOS_SERVER_ADDR`、`GOODS_BASE_URL`、`ORDER_SYNC_BASE_URL`。

> JVM 必须运行在 `Asia/Shanghai` 时区，否则时间窗校验 `checkInWindow` 会误判。

## 目录结构

```
├── gateway/          # 网关服务 (8080)
├── user-service/     # 用户服务 (8081)
├── goods-service/    # 商品服务 (8082)
├── miaosha-service/  # 秒杀服务 (8083)
├── order-service/    # 订单服务 (8084)
├── common/           # 公共组件
├── db/
│   └── init/         # MySQL 初始化脚本（01-init.sql，docker compose 首次启动自动执行）
├── docs/
│   └── screenshots/  # README 界面截图
├── frontend/         # 前端（Vite + React）
└── docker-compose.yaml
```
