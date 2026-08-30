# 秒杀系统数据库设计说明

> 本文档描述秒杀系统的数据库设计（原 `miaosha.sql` 的人工可读版本），用于让 AI 编码助手/Agent 快速理解业务模型。
>
> 历史说明：本项目曾使用 `miaosha.sql` 维护 schema，现已统一改用本文档作为唯一业务数据源；如需重新生成 DDL，请按本文档第 3 节的表结构自行生成。
> 所有表都在 MySQL 5.7+ 的 `miaosha` 库中，字符集 `utf8mb4`，引擎 `InnoDB`。

## 1. 业务概览

这是一个**手机端电商秒杀系统**的核心库。核心业务流程：

```
用户登录 → 浏览商品列表 → 进入秒杀商品详情 → 提交秒杀 → 扣减库存 → 生成订单
```

关键业务规则：
- 一个用户对**同一秒杀商品只能成功一次**（`miaosha_order` 的 `(user_id, goods_id)` 唯一索引兜底）
- 秒杀有**开始/结束时间窗口**（`miaosha_goods.start_date` / `end_date`）
- 库存扣减发生在 `miaosha_goods.stock_count`（独立于 `goods.goods_stock`）
- 密码采用**双层 MD5 + salt** 加密

---

## 2. 表关系总览

```
┌──────────────┐         ┌──────────────────┐
│    user      │         │  miaosha_user    │   ← 实际登录账户
│ (测试/杂用)  │         │ (手机号即主键)   │
└──────────────┘         └────────┬─────────┘
                                  │ 1:N
                                  ▼
┌──────────────┐         ┌──────────────────┐         ┌──────────────────┐
│    goods     │ 1:1 ──▶ │  miaosha_goods   │  1:N  ▶ │  miaosha_order   │
│ (普通商品)   │         │ (秒杀配置+库存)  │         │ (秒杀成功记录)   │
└──────────────┘         └──────────────────┘         └────────┬─────────┘
                                                               │ N:1
                                                               ▼
                                                       ┌──────────────────┐
                                                       │   order_info     │
                                                       │ (正式订单详情)   │
                                                       └──────────────────┘
```

**关系说明**：
- `miaosha_goods.goods_id` → `goods.id`：秒杀活动挂载在普通商品上
- `miaosha_order.order_id` → `order_info.id`：秒杀成功后落到正式订单
- `miaosha_order.goods_id` → `goods.id`（注意：指向 `goods` 而非 `miaosha_goods`）
- `order_info.goods_id` → `goods.id`：订单中冗余记录商品 ID

---

## 3. 表结构详解

### 3.1 `goods` — 商品主表

| 字段 | 类型 | 注释 |
|---|---|---|
| `id` | bigint(20) PK AUTO_INCREMENT | 商品 ID |
| `goods_name` | varchar(16) | 商品简称（如 iphoneX） |
| `goods_title` | varchar(64) | 商品长标题（用于列表展示） |
| `goods_img` | varchar(64) | 商品图片路径 |
| `goods_detail` | longtext | 商品详情（富文本） |
| `goods_price` | decimal(10,2) | 商品原价 |
| `goods_stock` | int(11) | 普通库存，`-1` 表示无限 |

> 用途：商品池，所有可售商品的元信息。`goods_stock=-1` 表示不限量（仅作占位，实际扣减走 `miaosha_goods.stock_count`）。

### 3.2 `miaosha_goods` — 秒杀活动配置表

| 字段 | 类型 | 注释 |
|---|---|---|
| `id` | bigint(20) PK AUTO_INCREMENT | 秒杀活动 ID |
| `goods_id` | bigint(20) | 关联 `goods.id` |
| `miaosha_price` | decimal(10,2) | 秒杀价 |
| `stock_count` | int(11) | 秒杀独立库存（**真正扣减的库存**） |
| `start_date` | datetime | 秒杀开始时间 |
| `end_date` | datetime | 秒杀结束时间 |

> 用途：定义"哪些商品在什么时间段以什么价格卖多少件"。  
> 与 `goods` 是一对一关系（每件商品最多一个秒杀活动）。

### 3.3 `miaosha_order` — 秒杀成功记录（**关键防重表**）

| 字段 | 类型 | 注释 |
|---|---|---|
| `id` | bigint(20) PK AUTO_INCREMENT | 秒杀记录 ID |
| `user_id` | bigint(20) | 用户手机号（同 `miaosha_user.id`） |
| `order_id` | bigint(20) | 关联 `order_info.id` |
| `goods_id` | bigint(20) | 关联 `goods.id` |
| `UNIQUE KEY` | `u_uid_gid (user_id, goods_id)` | **同一用户对同一商品只能秒杀一次** |

> 用途：幂等性保证。落库时 `INSERT` 此表，依赖唯一键 `(user_id, goods_id)` 冲突阻止重复秒杀。  
> 这是整个系统**防止超卖/重复下单**的核心机制（配合 DB 条件扣库存）。

### 3.4 `miaosha_user` — 秒杀业务用户表

| 字段 | 类型 | 注释 |
|---|---|---|
| `id` | bigint(20) PK | **用户手机号**（不是自增，是业务主键） |
| `nickname` | varchar(255) | 昵称 |
| `password` | varchar(32) | MD5(MD5(pass明文+固定salt) + salt)，32 位 |
| `salt` | varchar(10) | 每个用户独立 salt |
| `head` | varchar(128) | 头像，云存储 ID |
| `register_date` | datetime | 注册时间 |
| `last_login_date` | datetime | 上次登录时间 |
| `login_count` | int(11) | 累计登录次数 |

> **密码加密规则**（双盐 MD5）：
> 1. `first = MD5(明文密码 + 固定 salt)`
> 2. `final = MD5(first + 用户个人 salt)`
> 3. 库中存 `final`
> 
> 固定 salt 在 `MD5Util` 中硬编码（前端加一次固定 salt，后端再加用户 salt）。

### 3.5 `order_info` — 正式订单表

| 字段 | 类型 | 注释 |
|---|---|---|
| `id` | bigint(20) PK AUTO_INCREMENT | 订单 ID |
| `user_id` | bigint(20) | 下单用户 |
| `goods_id` | bigint(20) | 商品 ID |
| `delivery_addr_id` | bigint(20) | 收货地址 ID（当前未启用） |
| `goods_name` | varchar(16) | **冗余**：商品名称（避免联表） |
| `goods_count` | int(11) | 商品数量（秒杀固定为 1） |
| `goods_price` | decimal(10,2) | **冗余**：下单时的价格 |
| `order_channel` | tinyint(4) | 渠道：`1` PC，`2` Android，`3` iOS |
| `status` | tinyint(4) | 订单状态（见下表） |
| `create_date` | datetime | 下单时间 |
| `pay_date` | datetime | 支付时间 |

**订单状态枚举**：

| 值 | 含义 |
|---|---|
| 0 | 新建未支付 |
| 1 | 已支付 |
| 2 | 已发货 |
| 3 | 已收货 |
| 4 | 已退款 |
| 5 | 已完成 |

### 3.6 `user` — 通用用户表（**非业务核心**）

| 字段 | 类型 | 注释 |
|---|---|---|
| `id` | int(11) PK AUTO_INCREMENT | 用户 ID |
| `name` | varchar(10) | 用户名 |

> 用途：项目里早期遗留/示例表，**实际登录走 `miaosha_user`**。Agent 写代码时**不要用这张表**。

---

## 4. 初始数据（种子数据）

### 4.1 商品（4 个）

| id | 名称 | 原价 | 普通库存 |
|---|---|---|---|
| 1 | iphoneX | 8765.00 | 10000 |
| 2 | 华为Meta9 | 3212.00 | -1（无限） |
| 3 | iphone8 | 5589.00 | 10000 |
| 4 | 小米6 | 3212.00 | 10000 |

### 4.2 秒杀活动（4 个，每个库存 9，秒杀价 0.01 元）

| id | 关联商品 | 秒杀价 | 库存 | 时间窗口 |
|---|---|---|---|---|
| 1 | iphoneX (goods=1) | 0.01 | 9 | 2017-12-04 21:51:23 ~ 2017-12-31 21:51:27 |
| 2 | 华为Meta9 (goods=2) | 0.01 | 9 | 2017-12-04 21:40:14 ~ 2017-12-31 14:00:24 |
| 3 | iphone8 (goods=3) | 0.01 | 9 | 同上 |
| 4 | 小米6 (goods=4) | 0.01 | 9 | 同上 |

> **注意**：秒杀时间窗是 2017 年的历史时间，**已过期**。Agent 在做时间校验逻辑时，需要知道这些是测试种子数据，业务上要把 `start_date` / `end_date` 改到现在/未来才能复现秒杀。

### 4.3 测试用户

- **5000 个压测用户**：`13000000000` ~ `13000004999`，昵称 `user0` ~ `user4999`，统一密码哈希 `b7797cce01b4b131b433b6acf4add449`，salt `1a2b3c`，注册时间 `2017-11-30 09:01:59`，未登录过
- **1 个超级账号**：`18912341234` Joshua，密码哈希同上，salt `1a2b3c4d`

> 这 5000 个用户原本是给 JMeter 压测准备的（ID 连续可枚举，方便脚本化），所以手机号是按数字递增而非真实号段。

### 4.4 已生成的订单

库里有 4 条 `miaosha_order` + 4 条 `order_info`，全部由 `18912341234`（Joshua）下单，对应 4 件商品各一次，状态为"未支付"。

---

## 5. 核心业务流程（给 Agent 看）

### 5.1 登录
1. 客户端传 `mobile` + `password`
2. 客户端先做一次 `MD5(password + 固定salt)`（前端盐，在 `MD5Util` / `LoginController` 里）
3. 服务端 `SELECT * FROM miaosha_user WHERE id = ?` 拿到 `salt`
4. 服务端做 `MD5(前端哈希 + 用户salt)`，与库中 `password` 比对
5. 登录成功写 session/Cookie，更新 `last_login_date` 和 `login_count`

### 5.2 秒杀下单（异步主链路）

主链路已从「同步落库返回订单」改为「Redis 预扣 → Kafka → 消费者落库 → 前端轮询拿单」的削峰模型，DB 写入被移出请求路径：

```
1. 受理（MiaoshaAcceptService）：Redis Lua 原子预扣
   - 重复检查（user 标记存在 → 拒绝）→ 库存检查（stock ≤ 0 → 拒绝）→ 扣减并标记 PROCESSING
2. 发 Kafka `seckill-order`：消息体 {userId, goodsId, requestId}，key 随机打散
3. 立即返回受理中（PROCESSING），响应不含订单详情
4. 消费者落库（OrderFulfillmentService → MiaoshaService.createOrder，见下方 DB 事务）
   - 成功 → 回写 result = SUCCESS:{orderId}
   - 业务失败 → 补偿：回补库存、清标记、result = FAILED
5. 前端轮询 GET /miaosha/result 拿单（PROCESSING / SUCCESS:{orderId} / FAILED / 无记录 四态）
```

**数据库落库事务（`MiaoshaService.createOrder`，异步消费与降级直连共用）**——DB 层最终防线：

```
1. 校验商品存在 + 时间窗口（now ∈ [start_date, end_date]）
2. 查重：SELECT miaosha_order WHERE (user_id, goods_id) → 命中抛「重复下单」
3.【关键】条件扣库存：UPDATE miaosha_goods SET stock_count = stock_count - 1
   WHERE goods_id = ? AND stock_count > 0    （影响行数 = 0 → 抛「库存不足」）
4. INSERT order_info（拿到自增 order_id）
5. INSERT miaosha_order（直接带 order_id，UNIQUE (user_id, goods_id) 防重复兜底）
```

> 降级路径：Redis 不可用或 Kafka 发送失败时，受理方直接调用 `MiaoshaService.createOrder` 同步落库，用户仍拿到订单。  
> 消息层可靠性（发送失败降级 / 业务失败补偿 / 意外异常重试+死信 / 幂等兜底）详见 `docs/mq-design.md`。

### 5.3 商品列表 / 详情
- 列表：`goods` JOIN `miaosha_goods`（LEFT JOIN，因为非所有商品都有秒杀）
- 详情：根据 `goodsId` 同时取两表，组装 VO

---

## 6. Agent 注意事项（写代码时容易踩的坑）

1. **不要用 `user` 表**，登录一律走 `miaosha_user`。
2. **`miaosha_user.id` 是手机号字符串/数字**，不是自增主键。
3. **秒杀时间种子数据是 2017 年**，新部署后必须 UPDATE `miaosha_goods` 的 `start_date` / `end_date` 到当前可执行时间，否则接口会因时间校验失败返回错误。
4. **`miaosha_order.order_id` 指向 `order_info.id`**：落库顺序是先 `INSERT order_info`（拿自增 id）再 `INSERT miaosha_order`（直接带 `order_id`），两步在同一事务内完成（`MiaoshaService.createOrder`）。
5. **库存扣减必须带 `WHERE stock_count > 0`**（乐观锁/条件更新），否则在并发下会超卖。
6. **`miaosha_order` 唯一键 `(user_id, goods_id)` 是去重最后一道防线**——并发下也靠它兜底。事务里如果先 `SELECT` 再判断再插入会有并发漏洞，**直接 `INSERT` 捕获唯一键异常**更安全。
7. **`order_info.goods_name` 和 `goods_price` 是冗余字段**，下单时要从 `goods` / `miaosha_goods` 取当时的值快照保存，不要存实时联表查询的值（商品改价后老订单不应变化）。
8. **密码字段长度 32 是固定的**（MD5 hex），`salt` 是变长 varchar(10)。
9. **没有外键约束**（仅靠应用层和唯一键保证一致性），所以改 `goods.id` 时要同步关注 `miaosha_goods.goods_id`、`miaosha_order.goods_id`、`order_info.goods_id`。

---

## 7. 字段映射速查（Java 实体类名约定）

| 表 | 推荐实体名（参考项目） |
|---|---|
| `goods` | `Goods` / `GoodsVO` |
| `miaosha_goods` | `MiaoshaGoods` / `GoodsVo`（含 goods 冗余字段用于详情页） |
| `miaosha_order` | `MiaoshaOrder` |
| `miaosha_user` | `MiaoshaUser` |
| `order_info` | `OrderInfo` |
| `user` | 不用 |

`miaosha_user` 的密码字段在 VO/DTO 中通常叫 `password`（DTO 接收明文/前端哈希），DO 中叫 `password`（存的是双层哈希），不要混淆。
