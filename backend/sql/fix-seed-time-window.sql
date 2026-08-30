-- =============================================================================
-- 联调种子数据（修正版）：可直接在 MySQL 容器内执行
--
-- 背景：仓库里只有 docs/db-design.md 文档、没有可执行种子 SQL；且文档里
--       miaosha_goods 的时间窗是 2017 年历史时间（已过期），会导致商品详情
--       永远返回「已结束」、下单接口报「秒杀已结束 / 未开始」。
--
-- 本脚本做三件事：
--   1) 建库 + 建 5 张业务表（IF NOT EXISTS，幂等）；
--   2) 灌入种子数据，其中 miaosha_goods 的时间窗基于 NOW() 相对计算，
--      保证至少 1 个「进行中」、至少 1 个「未开始」、至少 1 个「已结束」，
--      且所有在窗口内的商品 stock_count > 0；
--   3) 末尾输出校验结果。
--
-- 幂等说明：表用 IF NOT EXISTS、数据用 INSERT IGNORE / ON DUPLICATE KEY UPDATE，
-- 可反复执行；每次执行都会把时间窗重新对齐到「当前时间前后」。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS miaosha
  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE miaosha;

-- 与后端 JDBC 的 serverTimezone=Asia/Shanghai 对齐：
-- MySQL 容器默认 UTC，这里把会话时区切到东八区，使 NOW() 与应用的
-- LocalDateTime.now(Clock.systemDefaultZone())（东八区）落在同一墙钟时间。
SET time_zone = '+08:00';

-- ---------------------------------------------------------------------------
-- 1. 建表（结构见 docs/db-design.md 第 3 节；不含遗留 user 表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS goods (
  id           BIGINT(20) NOT NULL AUTO_INCREMENT,
  goods_name   VARCHAR(16)  DEFAULT NULL,
  goods_title  VARCHAR(64)  DEFAULT NULL,
  goods_img    VARCHAR(64)  DEFAULT NULL,
  goods_detail LONGTEXT,
  goods_price  DECIMAL(10,2) DEFAULT NULL,
  goods_stock  INT(11)      DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS miaosha_goods (
  id            BIGINT(20) NOT NULL AUTO_INCREMENT,
  goods_id      BIGINT(20) DEFAULT NULL,
  miaosha_price DECIMAL(10,2) DEFAULT NULL,
  stock_count   INT(11)    DEFAULT NULL,
  start_date    DATETIME   DEFAULT NULL,
  end_date      DATETIME   DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS miaosha_order (
  id       BIGINT(20) NOT NULL AUTO_INCREMENT,
  user_id  BIGINT(20) DEFAULT NULL,
  order_id BIGINT(20) DEFAULT NULL,
  goods_id BIGINT(20) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_uid_gid (user_id, goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS miaosha_user (
  id              BIGINT(20) NOT NULL,
  nickname        VARCHAR(255) DEFAULT NULL,
  password        VARCHAR(32)  DEFAULT NULL,
  salt            VARCHAR(10)  DEFAULT NULL,
  head            VARCHAR(128) DEFAULT NULL,
  register_date   DATETIME     DEFAULT NULL,
  last_login_date DATETIME     DEFAULT NULL,
  login_count     INT(11)      DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_info (
  id               BIGINT(20) NOT NULL AUTO_INCREMENT,
  user_id          BIGINT(20) DEFAULT NULL,
  goods_id         BIGINT(20) DEFAULT NULL,
  delivery_addr_id BIGINT(20) DEFAULT NULL,
  goods_name       VARCHAR(16) DEFAULT NULL,
  goods_count      INT(11)     DEFAULT NULL,
  goods_price      DECIMAL(10,2) DEFAULT NULL,
  order_channel    TINYINT(4)  DEFAULT NULL,
  status           TINYINT(4)  DEFAULT NULL,
  create_date      DATETIME    DEFAULT NULL,
  pay_date         DATETIME    DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 2. 商品（4 个，INSERT IGNORE 幂等）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO goods
  (id, goods_name, goods_title, goods_img, goods_detail, goods_price, goods_stock)
VALUES
  (1, 'iphoneX', 'Apple iPhone X (A1865) 64GB 银色 移动联通电信4G手机', '/img/iphonex.png', 'Apple iPhone X (A1865) 64GB 银色 移动联通电信4G手机', 8765.00, 10000),
  (2, '华为Meta9', '华为 Mate 9 4GB+32GB版 月光银 移动联通电信4G手机 双卡双待', '/img/meta10.png', '华为 Mate 9 4GB+32GB版 月光银 移动联通电信4G手机 双卡双待', 3212.00, -1),
  (3, 'iphone8', 'Apple iPhone 8 (A1865) 64GB 银色 移动联通电信4G手机', '/img/iphone8.png', 'Apple iPhone 8 (A1865) 64GB 银色 移动联通电信4G手机', 5589.00, 10000),
  (4, '小米6', '小米6 4GB+32GB版 月光银 移动联通电信4G手机 双卡双待', '/img/mi6.png', '小米6 4GB+32GB版 月光银 移动联通电信4G手机 双卡双待', 3212.00, 10000);

-- ---------------------------------------------------------------------------
-- 3. 秒杀活动（4 个）—— 时间窗修正的核心
--    用 ON DUPLICATE KEY UPDATE 保证：无论库中已有 2017/2026 旧数据还是空表，
--    执行后时间窗都会对齐到「当前时间前后」。
--    - 商品 1、2：进行中
--    - 商品 3：未开始（倒计时态）
--    - 商品 4：已结束（禁用态）
-- ---------------------------------------------------------------------------
INSERT INTO miaosha_goods
  (id, goods_id, miaosha_price, stock_count, start_date, end_date)
VALUES
  (1, 1, 0.01, 9, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 DAY),
  (2, 2, 0.01, 9, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 2 DAY),
  (3, 3, 0.01, 9, NOW() + INTERVAL 1 HOUR, NOW() + INTERVAL 1 DAY),
  (4, 4, 0.01, 9, NOW() - INTERVAL 2 DAY,  NOW() - INTERVAL 1 HOUR)
ON DUPLICATE KEY UPDATE
  miaosha_price = VALUES(miaosha_price),
  stock_count   = VALUES(stock_count),
  start_date    = VALUES(start_date),
  end_date      = VALUES(end_date);

-- ---------------------------------------------------------------------------
-- 4. 测试用户（登录用，密码明文均为 123456，INSERT IGNORE 幂等）
--    超级账号 18912341234（salt 1a2b3c4d）+ 少量压测账号 13000000000~0009
--    完整 5000 压测账号见 docs/db-design.md 4.3 节
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO miaosha_user
  (id, nickname, password, salt, head, register_date, last_login_date, login_count)
VALUES
  (18912341234, 'Joshua', 'b7797cce01b4b131b433b6acf4add449', '1a2b3c4d', NULL, NOW(), NULL, 0),
  (13000000000, 'user0',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000001, 'user1',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000002, 'user2',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000003, 'user3',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000004, 'user4',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000005, 'user5',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000006, 'user6',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000007, 'user7',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000008, 'user8',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0),
  (13000000009, 'user9',  'b7797cce01b4b131b433b6acf4add449', '1a2b3c',   NULL, NOW(), NULL, 0);

-- ---------------------------------------------------------------------------
-- 5. 校验：应看到 2 个进行中、1 个未开始、1 个已结束，且 stock_count 均 > 0
--    miaosha_status 取值与后端 MiaoshaStatus 一致：0=未开始 1=进行中 2=已结束
-- ---------------------------------------------------------------------------
SELECT mg.goods_id,
       g.goods_name,
       mg.stock_count,
       mg.start_date,
       mg.end_date,
       CASE
           WHEN NOW() < mg.start_date THEN 0
           WHEN NOW() >= mg.end_date  THEN 2
           ELSE 1
       END AS miaosha_status
FROM miaosha_goods mg
JOIN goods g ON g.id = mg.goods_id
ORDER BY mg.goods_id;
