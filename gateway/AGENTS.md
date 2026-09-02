# AGENTS.md — gateway 模块

## 定位

gateway 是秒杀微服务体系的统一入口（端口 8080），基于 Spring Cloud Gateway（WebFlux 响应式栈）。**只做路由转发 + JWT 鉴权（Bearer 校验 → 下发 X-User-Id），禁止任何业务逻辑、DB 访问、Redis/Kafka 依赖。**

## 硬性约束

- 技术栈为 WebFlux，**禁止引入 `spring-boot-starter-web`**（会与 Netty 冲突导致启动失败）。pom 依赖：`spring-cloud-starter-gateway` + `spring-cloud-starter-alibaba-nacos-discovery` + `spring-cloud-starter-loadbalancer` + `spring-boot-starter-actuator` + `common`（common 无 web 依赖，安全引入）；新增依赖前先确认不破坏响应式栈。
- 业务代码只有 `GatewayApplication` 启动类与 `filter/JwtGlobalFilter` 全局过滤器；路由行为优先改 `application.yml`，其余横切逻辑不要随意新增。
- gateway 承担的 JWT 职责仅限「鉴头翻译」：剥离伪造 `X-User-Id` → 校验 `Authorization: Bearer` → 下发 `X-User-Id`。不做业务判断/DB/Redis/Kafka；限流、熔断、TraceId 等（Step 7）目前不做。

## 路由表（`src/main/resources/application.yml`）

| 路径前缀 | 目标 | 说明 |
|---|---|---|
| `/user/**` | lb://user-service | user-service |
| `/goods/**` | lb://goods-service | goods-service |
| `/miaosha/**` | lb://miaosha-service | miaosha-service |
| `/admin/**` | lb://miaosha-service | miaosha-service 管理端 |

- **路由目标为服务名（`lb://服务名`），经 Nacos 服务发现 + Spring Cloud LoadBalancer 分发**，不再写死 localhost 端口；改任何服务的端口无需改这里。
- 新增路由直接在 `routes` 下加条目（`id` + `uri` + `Path` predicate），`uri` 用 `lb://<spring.application.name>`，与现有条目保持同样风格。

## JWT 全局过滤器（`filter/JwtGlobalFilter`）

`JwtGlobalFilter implements GlobalFilter, Ordered`（`order=-100`，早于路由转发）：

1. **无条件剥离请求中的 `X-User-Id` Header**（防外部伪造，即使白名单路径也剥离）。
2. **白名单放行**：`/user/login`、`/user/register`（与 user-service `WebConfig` 的 excludePathPatterns 对齐）。
3. **校验 Bearer Token**：读 `Authorization: Bearer <token>`，用 common `JwtUtil.parseUserId` 解析 userId。
4. **下发身份**：校验成功后写入 `X-User-Id: {userId}` 转发下游。
5. **失败兜底**：缺失/非法 token 返回 401 + Result 同构 JSON（`{"code":500401,"msg":"未登录或token无效","data":null}`，`CodeMsg.SESSION_ERROR`）。

下游业务服务（user/goods/miaosha）据此改读 `X-User-Id`（`UserContextInterceptor`），Controller 取值方式不变。

## 构建与运行

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Java 17 强制（父 pom maven-enforcer 校验）

# 从仓库根目录构建（-am 自动带上依赖的 common）
mvn -pl gateway -am clean package -DskipTests

# 运行（需先起 Nacos，否则注册失败；联调全链路时下游四服务需先就绪）
mvn -pl gateway spring-boot:run
```

无测试代码；gateway 依赖 Nacos（服务发现）与下游四服务注册的健康实例，单独启动需先 `docker compose up -d nacos`（MySQL/Redis/Kafka 对 gateway 本身无依赖）。

## 验证

```bash
# 走网关访问商品列表（应与直连 8082 的 /goods/list 行为一致）
curl http://localhost:8080/goods/list

# 登录接口（白名单放行）
curl -X POST http://localhost:8080/user/login ...

# JWT 三态：无 token → 401；有效 Bearer → 200 且下游读到 X-User-Id；伪造 X-User-Id → 被剥离
curl -i http://localhost:8080/goods/list
curl -i -H "Authorization: Bearer <token>" http://localhost:8080/goods/list
curl -i -H "X-User-Id: 999" http://localhost:8080/goods/list   # 无 Bearer 仍应 401

# 健康检查（仅 health,info）
curl http://localhost:8080/actuator/health
```

## 上下文

项目整体架构、服务职责划分、秒杀不变量见仓库根目录 `AGENTS.md`；修改 gateway 时遵守其中「gateway 只做路由/鉴权/过滤，禁止业务逻辑」的约束。
