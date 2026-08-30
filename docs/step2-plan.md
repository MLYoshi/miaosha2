# Step 2 迁移计划：迁移 User Domain → user-service

> 状态：已确认，执行中
> 前置：Step 1 已完成（Maven 多模块骨架：gateway / user-service / goods-service / miaosha-service / order-service / common）
> 基线：旧单体 `backend/` 是事实基线，本次迁移**零修改** `backend/`

## 1. 目标

将旧单体 `backend/` 中的**用户与登录相关能力**完整迁移到 `user-service`（:8081），使其可独立运行；`gateway`（:8080）将 `/user/**` 路由到 user-service。

```
Frontend
   ↓
Gateway :8080
   ↓  /user/**
user-service :8081
   ↓
MySQL: miaosha_user
```

本步骤不改造其他业务服务，不处理服务间 RPC。

## 2. 核心特性

- **迁移能力**：UserController、UserService、UserMapper（+XML）、User domain、LoginVo、JWT 代码、密码校验代码、用户异常与错误码
- **保留 API**（路径与响应格式完全不变）：
  - `POST /user/login` → `{"code":0,"msg":"success","data":"<token>"}`
  - `POST /user/register` → 注册成功直接返回 token
  - `GET /user/profile` → 隐藏 password/salt；用户不存在返回 500501
- **JWT 兼容**：secret、24h 过期、claims（subject=userId）、`Authorization: Bearer <token>` 全部沿用旧单体实现，不重新设计
- **密码兼容**：双层 MD5（前端 `MD5(明文 + 固定salt)` → 服务端 `MD5(前端哈希 + 用户6位salt)`），不修改算法
- **common 模块边界**：只复用真正公共基础设施；仅服务于 user-domain 的类放 user-service
- **Gateway**：仅加静态路由，暂不做 JWT 全局鉴权/限流/服务发现/负载均衡

## 3. 技术栈

- Java 17（强制，macOS 需 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`）
- Spring Boot 3.3.4、Spring Cloud 2023.0.3（仅 gateway starter）
- MyBatis spring-boot-starter 3.0.3（XML mapper）、MySQL 8（mysql-connector-j）
- jjwt 0.12.6、commons-codec（与 backend 版本一致，保证 token / 密码向量互认）
- 测试：spring-boot-starter-test + Testcontainers（仅 MySQL，无需 Redis/Kafka）

## 4. 实现方案

1. **common 模块**：迁入 `Result`、`CodeMsg`、`MiaoshaException`、`JwtUtil`（Handoff §9 明确允许的公共基础设施；JwtUtil 后续 gateway 鉴权也会复用）。common POM 增加 jjwt-api/impl/jackson 0.12.6 依赖。`GlobalExceptionHandler`、`MD5Util` 本步骤仅 user-service 使用，放 user-service，待后续服务需要时再上收 common。
2. **user-service**：按 `com.example.user` 包结构迁移 Controller/Service/dao/domain/vo，XML mapper namespace 与 type-aliases 同步改为新包名；`application.yml` 独立配置 datasource（localhost:3306/miaosha、root/root，与 backend 一致）、mybatis（mapper-locations `classpath*:mapper/**/*.xml`、map-underscore-to-camel-case）；引入 web/validation/mybatis/mysql/commons-codec/common 依赖。JwtInterceptor + WebConfig 保持拦截 `/**`、放行 `/user/login`、`/user/register` 的既有行为。
3. **gateway**：`application.yml` 增加静态路由 `Path=/user/**` → `http://localhost:8081`，不引入服务发现/负载均衡，gateway 不混入任何业务代码。
4. **测试**：单测复刻 backend 的 JwtUtilTest/MD5UtilTest 语义（含与旧单体 secret/claims 一致性断言）；集成测试用 Testcontainers MySQL 加载 `miaosha_user` DDL（从 backend test schema.sql 摘取），复刻 AuthApiTest 的核心场景（F1-F5 对应分支）。
5. **回归**：根目录 `mvn clean package -DskipTests` 全量构建 + `cd backend && mvn -B package -DskipTests` 验证旧单体不受破坏。

## 5. 架构

```
                 ┌──────────────────┐
                 │  Gateway  :8080  │
                 │  Path=/user/**   │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐        ┌────────────────────────────┐
                 │ user-service     │ -.复用.→ │ common                     │
                 │ :8081            │        │ Result / CodeMsg /         │
                 │ Controller       │        │ MiaoshaException / JwtUtil │
                 │ Service          │        └────────────────────────────┘
                 │ JwtInterceptor   │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │ MySQL            │
                 │ miaosha_user     │
                 └──────────────────┘
```

## 6. 迁移文件映射（backend → 新位置）

| backend | 去向 |
| --- | --- |
| common/Result、CodeMsg、MiaoshaException、JwtUtil | common（`com.example.common`，逻辑零改动） |
| common/MD5Util、GlobalExceptionHandler | user-service（仅 user-domain 使用） |
| controller/UserController、service/UserService、dao/UserMapper、domain/User、vo/LoginVo | user-service（`com.example.user.*`） |
| interceptor/JwtInterceptor、config/WebConfig | user-service |
| resources/mapper/UserMapper.xml | user-service（namespace 改为 `com.example.user.dao.UserMapper`） |

不迁入 user-service：Goods、Miaosha、Order、Redis 秒杀库存、Kafka 秒杀消息。

## 7. 任务清单

| # | 任务 | 说明 |
| --- | --- | --- |
| 1 | 填充 common 模块 | 迁入 Result/CodeMsg/MiaoshaException/JwtUtil（逻辑零改动）；common/pom.xml 增加 jjwt 0.12.6 三件套 |
| 2 | 迁移 user-service | Controller/Service/dao/domain/vo/MD5Util/JwtInterceptor/WebConfig/GlobalExceptionHandler；补全 pom 与 application.yml |
| 3 | Gateway 路由 | `/user/**` → `http://localhost:8081` 静态路由 |
| 4 | 编写测试 | JwtUtilTest / MD5UtilTest 单测 + Testcontainers MySQL 集成测试 |
| 5 | 构建与审查 | 全量构建 + backend 独立构建均通过；code-reviewer 审查迁移兼容性 |
| 6 | 本地联调 | 启动 user-service 与 gateway，验证 8081 直连与 8080 经网关结果一致 |

## 8. 测试验收标准

### User Service 单元/集成测试
- 正确账号 + 正确密码 → 登录成功（code=0）
- 错误密码 → 失败（500502 PASSWORD_ERROR）
- 不存在用户 → 失败（500501 MOBILE_NOT_EXIST）
- 注册成功 → 直接返回 token；重复注册 → 500503
- JWT 正常生成；claims 与旧单体一致（subject=userId、24h）
- profile 脱敏（password/salt 为 null）；无 token/假 token → 401

### HTTP 直连测试
```
POST http://localhost:8081/user/login   → 成功
```

### Gateway 测试
```
POST http://localhost:8080/user/login   → 与直连 8081 结果一致
```

## 9. 风险控制

- 纯迁移零逻辑改动，查询均为单表主键查询，无性能风险
- 爆炸半径控制：只新增 user-service/gateway/common 文件，backend 零改动
- MyBatis namespace/type-aliases 改包名时逐项核对，避免 mapper 加载失败
- jjwt 版本与 backend 完全一致（0.12.6），确保 token 互认

## 10. 明确禁止（本步骤）

```
❌ 修改数据库 schema          ❌ 修改登录 API
❌ 修改密码算法               ❌ 修改 JWT 契约
❌ 引入 Nacos / Feign        ❌ 引入分布式事务
❌ 修改 Goods / Miaosha / Order
❌ 修改 Redis 秒杀逻辑 / Kafka
❌ 删除或重构 backend/
```

## 11. 下一步（Step 3，本次不处理）

```
backend → goods-service
```
迁移：GoodsController、GoodsService、GoodsMapper、Goods、商品详情、秒杀时间窗、库存查询、Admin 预热库存。
