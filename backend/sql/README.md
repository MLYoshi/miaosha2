# 秒杀联调种子数据（时间窗修正版）

种子数据的秒杀时间窗原本是 2017 年历史时间，已全部过期，会导致商品详情页
永远显示「已结束」、下单接口报「秒杀已结束 / 未开始」。本目录的
`fix-seed-time-window.sql` 是一段**自包含、可反复执行**的 SQL：建库建表 +
灌入种子数据，并把秒杀时间窗对齐到「当前时间前后」，使联调可以直接跑通。

## 执行方式

启动 MySQL 容器后（在 `backend/` 目录下），用 `docker exec` 喂给容器即可：

```bash
docker compose up -d mysql
docker exec -i seckill-mysql mysql -uroot -proot --default-character-set=utf8mb4 < sql/fix-seed-time-window.sql
```

> **务必带 `--default-character-set=utf8mb4`**：MySQL 容器的 `mysql` 客户端默认
> 字符集是 `latin1`，不加此参数会把 UTF-8 中文（商品名/标题）按 latin1 解释成
> 乱码写进库。

> 说明：脚本内部会 `CREATE DATABASE IF NOT EXISTS` + `USE miaosha`，并建表
> （`IF NOT EXISTS`）、灌数据（`INSERT IGNORE` / `ON DUPLICATE KEY UPDATE`），
> 所以新环境克隆后照此一条命令即可跑通，无需单独建表或导 `miaosha.sql`。

## 执行后校验

脚本末尾会输出 4 行校验结果，期望如下：

| goods_id | 商品 | 窗口状态 | 对应 miaoshaStatus |
|---|---|---|---|
| 1 | iphoneX | 进行中 | 1 |
| 2 | 华为Meta9 | 进行中 | 1 |
| 3 | iphone8 | 未开始（倒计时） | 0 |
| 4 | 小米6 | 已结束（禁用） | 2 |

接口验证：

```bash
# 详情返回 miaoshaStatus=1（进行中）
curl 'http://localhost:8080/goods/detail/1'
```

- 商品 1、2 的详情 `miaoshaStatus` 应为 `1`，下单不再报未开始 / 已结束。
- 商品 3 用于验证「未开始」倒计时态（`miaoshaStatus=0`，`remainSeconds>0`）。
- 商品 4 用于验证「已结束」禁用态（`miaoshaStatus=2`）。

## 登录账号

| 账号 | 密码 | 说明 |
|---|---|---|
| `18912341234` | `123456` | 超级账号（Joshua） |
| `13000000000` ~ `13000000009` | `123456` | 压测账号（可用来测下单） |

完整 5000 个压测账号（`13000000000` ~ `13000004999`）见 `docs/db-design.md` 4.3 节。

## 说明

- 脚本把会话时区设为 `+08:00`，与后端 JDBC 的 `serverTimezone=Asia/Shanghai`
  及应用的系统默认时区对齐，避免 MySQL 容器（默认 UTC）与后端墙钟时间相差 8 小时。
- 时间窗基于 `NOW()` 相对计算，可随时重复执行；每次执行都会把窗口重新对齐到
  「当前时间前后」（如隔几天窗口又过期，重跑一遍即可）。
- 所有商品的 `stock_count` 为 9（种子值），保证在窗口内的商品库存 > 0。
- 不预置历史订单：`miaosha_order` / `order_info` 为空表，方便用任意账号走通
  「登录 → 详情 → 秒杀下单 → 轮询结果」全链路。
