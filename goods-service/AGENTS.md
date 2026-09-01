# goods-service — 商品服务（端口 8082）

> 全局规则（迁移基线、数据所有权、Kafka/Redis 分区、命名约定等）见仓库根目录 `AGENTS.md`，本文件只写 goods-service 模块内需要读多个文件才能发现的要点。

## 职责

商品/秒杀商品信息、秒杀时间窗口规则、库存条件扣减与回补（DB 层）。表：`goods`（JOIN `miaosha_goods`）。不持有 Redis 预扣 Key，不消费 Kafka。

## 架构要点

- **对外与内部接口隔离**：
  - `GoodsController`（`/goods/**`）：商品列表、详情（带秒杀窗口状态）。受 JWT 拦截。
  - `InternalGoodsController`（`/internal/goods/**`）：仅供 order-service 服务间调用（RestClient，见 order-service `client/HttpGoodsClient`），不走 JWT（`WebConfig` 放行 `/internal/**`）。提供商品快照、条件扣减库存、回补库存（Saga 补偿）。
  - `AdminController`（`/admin/**`）：管理端写操作接缝（当前仅库存预热）。gateway 把 `/admin/**` 路由到 miaosha-service 而非本服务，此处接口仅限服务内/直连调用。
- **时间窗口唯一规则源**：`MiaoshaWindowService` 是全系统秒杀窗口边界的唯一定义处（详情页 `resolveStatus` 与下单 `checkInWindow` 两个薄接口共用）。边界语义：null startDate=立即开始、null endDate=永不过期、起止边界均含端点。改窗口判断只改这一处。
- **时钟可注入**：所有 `LocalDateTime.now()` 必须通过注入的 `Clock` bean（`ClockConfig`）获取，禁止直接 `now()`，否则测试无法控制时间。
- **防超卖在 SQL 层**：`GoodsMapper.reduceStock` 是条件更新 `stock_count > 0`（与基线 `backend` 的 `MiaoshaGoodsMapper.reduceStock` 逐字对齐），影响行数 0 = 库存不足。禁止改成先查再改。`restoreStock` 无条件 +1，幂等性由调用方（order-service 编排）保证，本服务不判重。
- **不引入 Redis**：`StockPreheatService` 只做 DB 读取校验；Redis `setStock` 预热与预扣 Key 归 miaosha-service，不要把 Redis 依赖加进本模块。
- **JWT**：`JwtInterceptor` 校验 `Authorization: Bearer` 并把 userId 放入 request attribute。goods-service 没有登录接口，除 `/internal/**` 外全量拦截。

## 构建与运行

```bash
# 在仓库根目录执行（需 Java 17）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl goods-service -am clean package -DskipTests
mvn -pl goods-service -am spring-boot:run
```

依赖根目录 docker compose 的 MySQL（`miaosha` 库）；连接可用 `MYSQL_HOST/PORT` 覆盖。联调报「未开始/已结束」先跑 `backend/sql/fix-seed-time-window.sql` 对齐时间窗（见根 AGENTS.md）。

## 约定

- 「秒杀」统一 `Miaosha` 前缀；VO（`GoodsVo`/`GoodsDetailVo`）含秒杀字段，是跨服务快照载体——字段变更需同步 order-service 侧消费逻辑。
- 新增内部服务间接口照 `InternalGoodsController` 风格挂 `/internal/**`，并在 `WebConfig` 保持放行；调用方按 order-service `GoodsClient`/`HttpGoodsClient` 的接口+实现接缝风格接入，不用 OpenFeign。
- 错误码统一加在 common 的 `CodeMsg`，不在本模块内定义。
