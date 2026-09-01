# user-service — AGENTS.md

用户服务（端口 8081）：注册 / 登录 / JWT 签发与校验、用户个人信息。整个秒杀链路的入口（登录 → 商品 → 秒杀）。

根目录的 `AGENTS.md` 是全项目规则的事实来源，本文件只补充 user-service 特有的内容，两者冲突时以根目录为准。

## 命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Java 17 强制（maven-enforcer 校验）

# 本服务独立跑（-am 会先构建依赖的 common）
mvn -pl user-service -am spring-boot:run

# 打包
mvn -pl user-service -am clean package -DskipTests

# 测试（Testcontainers，必须先启动 Docker；本服务只依赖 MySQL 容器，不需要 Redis/Kafka）
mvn -pl user-service test
mvn -pl user-service test -Dtest=AuthApiTest                 # 单个测试类
mvn -pl user-service test -Dtest=JwtUtilTest#testXxx         # 单个测试方法
```

健康检查：`GET http://localhost:8081/actuator/health`（仅暴露 health/info）。

连接配置可用环境变量覆盖：`MYSQL_HOST`、`MYSQL_PORT`（默认 localhost:3306，库 `miaosha`，root/root）。

## 架构

```
controller/UserController  →  service/UserService  →  dao/UserMapper（MyBatis 注解/接口）
        ↑ JwtInterceptor（config/WebConfig 注册）          ↓
   common/JwtUtil（共享）                            表 miaosha_user（数据所有权仅此一张表）
```

- **分层**：`controller`（HTTP）→ `service`（业务）→ `dao`（MyBatis）。`common/MD5Util`、`interceptor/JwtInterceptor`、`config/WebConfig` 是服务内横切组件；`domain/User` 是实体，`vo/LoginVo` 是入参校验对象（手机号正则 `^1[3-9]\d{9}$`、密码 6-32 位）。
- **异常**：业务校验失败直接抛 `common` 里的 `MiaoshaException(CodeMsg)`，由 `common/GlobalExceptionHandler` 统一转成 `Result`。本服务错误码：500501 用户不存在、500502 密码错误、500503 手机号已注册。
- **鉴权**：`JwtInterceptor` 拦截 `/**`，仅放行 `/user/login`、`/user/register`。它解析 `Authorization: Bearer <token>`，把 `userId` 放进 request attribute，Controller 通过 `request.getAttribute("userId")` 取（见 `profile`）。JWT 的生成/校验工具在共享模块 `common` 的 `JwtUtil`，其他服务（如 gateway 后面的各服务）也用同一套，改 token 格式必须全局对齐。

## 必须保持不变的行为

1. **手机号即用户 id**：`login`/`register` 都用 `Long.valueOf(mobile)` 作为 `miaosha_user.id` 查询/插入。这是沿用 backend 单体的约定，下游秒杀链路按此 id 关联用户，不要改成自增 id。
2. **双层 MD5 密码向量与 backend 互认**：`MD5Util.inputPassToDbPass(明文, salt)` = `md5(salt[0]+salt[2]+md5("1a2b3c4d"[0]+"1a2b3c4d"[2]+明文+"1a2b3c4d"[5]+"1a2b3c4d"[4]) + salt[5]+salt[4])`。依赖 `commons-codec`（与 backend 一致）和 `salt.charAt(5)`，因此 **salt 长度必须 ≥ 6**（`UserService.randomSalt()` 取 UUID 前 6 位）。改任何一层都会导致旧用户无法登录，禁止动。
3. **注册即登录**：`register` 成功后直接返回 JWT，不返回用户对象。
4. **profile 必须脱敏**：返回前 `setPassword(null)`、`setSalt(null)`。
5. **不依赖 Redis/Kafka**：本服务只连 MySQL，技术栈是 `spring-boot-starter-web`（Servlet）+ MyBatis + validation。

## 数据所有权

只读写 `miaosha_user` 表。不能 import 其他服务的 Entity/Mapper，跨服务只能走 HTTP/Kafka（本服务目前不主动调用其他服务）。

## 测试约定

- 基座：`test/.../support/AbstractUserIntegrationTest` —— Testcontainers 单例 MySQL 8 容器（static 块启动、全类共享），`RANDOM_PORT` 起**真实 HTTP** 服务，DDL 在 `test/resources/schema.sql`（仅 `miaosha_user`）。
- 隔离策略：`@BeforeEach` 里 `TRUNCATE TABLE miaosha_user`（服务端事务无法从测试侧回滚，与 backend 同策略），不要改成 `@Transactional` 回滚。
- Fixture：`insertUser(mobile)` 按种子约定插入用户（明文 `123456` + 固定 salt `1a2b3c`）。测试类常量里固化了各错误码（500501/500502/500503），新增错误码同步更新。
- 纯单元测试（`JwtUtilTest`、`MD5UtilTest`）不起容器。
