-- ============================================================================
-- db/init/01-init.sql — 秒杀系统数据库初始化脚本（自包含）
--
-- 由 mysql:8.0 官方镜像的 /docker-entrypoint-initdb.d 机制在容器首次启动时
-- 自动执行（仅当 ./db/mysql_data 数据目录为空时执行一次）。
-- 修改本脚本后想重新初始化：docker compose down -v 并清空 db/mysql_data。
--
-- ⚠️ 时间字段一律使用 NOW() 等动态函数，严禁写死日期，
--    保证任何时刻初始化后秒杀窗口都覆盖当前时间（checkInWindow 不会误判）。
--    MySQL 容器通过 TZ=Asia/Shanghai 保证 NOW() 与 JVM 时区一致。
-- ============================================================================

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE DATABASE IF NOT EXISTS miaosha DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE miaosha;

-- ----------------------------------------------------------------------------
-- 1. 商品表（goods-service 所有）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS goods (
  id           BIGINT        NOT NULL COMMENT '商品ID',
  goods_name   VARCHAR(16)   DEFAULT NULL COMMENT '商品名称',
  goods_title  VARCHAR(64)   DEFAULT NULL COMMENT '商品标题',
  goods_img    VARCHAR(128)  DEFAULT NULL COMMENT '商品图片',
  goods_detail TEXT          COMMENT '商品详情',
  goods_price  DECIMAL(10,2) DEFAULT '0.00' COMMENT '原价',
  goods_stock  INT           DEFAULT '-1' COMMENT '总库存（-1 表示没有限制）',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ----------------------------------------------------------------------------
-- 2. 秒杀商品表（goods-service 所有：秒杀价/秒杀库存/时间窗）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS miaosha_goods (
  goods_id      BIGINT        NOT NULL COMMENT '商品ID（对应 goods.id）',
  stock_count   INT           DEFAULT NULL COMMENT '秒杀库存（条件扣减防超卖）',
  miaosha_price DECIMAL(10,2) DEFAULT '0.00' COMMENT '秒杀价',
  start_date    DATETIME      DEFAULT NULL COMMENT '秒杀开始时间',
  end_date      DATETIME      DEFAULT NULL COMMENT '秒杀结束时间',
  PRIMARY KEY (goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- ----------------------------------------------------------------------------
-- 3. 用户表（user-service 所有）：id 即登录手机号
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS miaosha_user (
  id              BIGINT       NOT NULL COMMENT '用户ID（即手机号）',
  nickname        VARCHAR(255) DEFAULT NULL,
  password        VARCHAR(32)  DEFAULT NULL COMMENT '双层 MD5(salt) 后的密码',
  salt            VARCHAR(10)  DEFAULT NULL,
  head            VARCHAR(128) DEFAULT NULL,
  register_date   DATETIME     DEFAULT NULL,
  last_login_date DATETIME     DEFAULT NULL,
  login_count     INT          DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ----------------------------------------------------------------------------
-- 4. 订单明细表（order-service 所有）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_info (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  user_id          BIGINT        DEFAULT NULL COMMENT '用户ID',
  goods_id         BIGINT        DEFAULT NULL COMMENT '商品ID',
  delivery_addr_id BIGINT        DEFAULT NULL COMMENT '收货地址ID',
  goods_name       VARCHAR(16)   DEFAULT NULL COMMENT '下单时商品名快照',
  goods_count      INT           DEFAULT '1' COMMENT '数量',
  goods_price      DECIMAL(10,2) DEFAULT '0.00' COMMENT '下单时秒杀价快照',
  order_channel    TINYINT       DEFAULT '0' COMMENT '订单渠道 1pc 2android 3ios',
  status           TINYINT       DEFAULT '0' COMMENT '状态 0新建未支付 1已支付 2已发货 3已收货 4已退款 5已完成',
  create_date      DATETIME      DEFAULT NULL COMMENT '创建时间',
  pay_date         DATETIME      DEFAULT NULL COMMENT '支付时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- ----------------------------------------------------------------------------
-- 5. 秒杀订单表（order-service 所有）：UNIQUE(user_id, goods_id) 防重复下单
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS miaosha_order (
  id       BIGINT NOT NULL AUTO_INCREMENT,
  user_id  BIGINT DEFAULT NULL COMMENT '用户ID',
  goods_id BIGINT DEFAULT NULL COMMENT '商品ID',
  order_id BIGINT DEFAULT NULL COMMENT '对应 order_info.id',
  PRIMARY KEY (id),
  UNIQUE KEY u_uid_gid (user_id, goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';

-- ============================================================================
-- 种子数据
-- ============================================================================

-- 商品（goods_name 与 order_info.goods_name 同为 VARCHAR(16)，注意长度）
INSERT INTO goods (id, goods_name, goods_title, goods_img, goods_detail, goods_price, goods_stock) VALUES
(1, 'iPhone 15',   'Apple iPhone 15 128G 黑色',        '/img/goods/iphone15.jpg',   'Apple iPhone 15，A16 仿生芯片，4800 万像素主摄。',        6999.00, 500),
(2, '华为Mate60',  'HUAWEI Mate 60 512G 雅丹黑',       '/img/goods/mate60.jpg',     'HUAWEI Mate 60，麒麟芯片，卫星通话。',                    6999.00, 300),
(3, '小米14',      'Xiaomi 14 16G+512G 黑色',          '/img/goods/mi14.jpg',       'Xiaomi 14，骁龙8 Gen3，徕卡光学镜头。',                   4999.00, 500),
(4, 'iPad Air5',   'Apple iPad Air 5 64G WLAN 星光色', '/img/goods/ipadair5.jpg',   'Apple iPad Air 5，M1 芯片，支持 Apple Pencil。',          4399.00, 200),
(5, 'AirPodsPro2', 'Apple AirPods Pro 2 USB-C',        '/img/goods/airpodspro2.jpg','Apple AirPods Pro 2，自适应通透模式，USB-C 充电。',       1899.00, 800);

-- 秒杀配置：时间窗全部动态生成（NOW() 派生），任何时刻初始化都在窗口内
INSERT INTO miaosha_goods (goods_id, miaosha_price, stock_count, start_date, end_date) VALUES
(1, 5499.00, 100, NOW(),                       DATE_ADD(NOW(), INTERVAL 2 HOUR)),
(2, 4999.00,  80, NOW() - INTERVAL 30 MINUTE,  DATE_ADD(NOW(), INTERVAL 90 MINUTE)),
(3, 3499.00, 200, NOW(),                       DATE_ADD(NOW(), INTERVAL 4 HOUR)),
(4, 3299.00,  50, NOW() - INTERVAL 15 MINUTE,  DATE_ADD(NOW(), INTERVAL 3 HOUR)),
(5, 1499.00, 150, NOW(),                       DATE_ADD(NOW(), INTERVAL 1 HOUR));

-- 测试用户：手机号即 id，密码均为 123456
-- 哈希由 user-service MD5Util 生成：inputPassToDbPass("123456", "1a2b3c")
--   = md5("1a2b3c"[0] + "1a2b3c"[2] + md5("1a2b3c4d"[0]+"1a2b3c4d"[2]+"123456"+"1a2b3c4d"[5]+"1a2b3c4d"[4]) + "1a2b3c"[5] + "1a2b3c"[4])
--   = 92d3650d2867235dfdcf956d803f7b9e
INSERT INTO miaosha_user (id, nickname, password, salt, head, register_date, last_login_date, login_count) VALUES
(13800000001, 'user13800000001', '92d3650d2867235dfdcf956d803f7b9e', '1a2b3c', NULL, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 1 DAY, 3),
(13800000002, 'user13800000002', '92d3650d2867235dfdcf956d803f7b9e', '1a2b3c', NULL, NOW() - INTERVAL 25 DAY, NOW() - INTERVAL 2 DAY, 1),
(13800000003, 'user13800000003', '92d3650d2867235dfdcf956d803f7b9e', '1a2b3c', NULL, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 3 DAY, 5),
(13800000004, 'user13800000004', '92d3650d2867235dfdcf956d803f7b9e', '1a2b3c', NULL, NOW() - INTERVAL 15 DAY, NOW() - INTERVAL 1 DAY, 2),
(13800000005, 'user13800000005', '92d3650d2867235dfdcf956d803f7b9e', '1a2b3c', NULL, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 4 DAY, 1);

-- 示例订单（历史订单，日期动态生成）
-- 注意：miaosha_order 有 UNIQUE(user_id, goods_id)，此处占用 13800000005 × 商品1，
--       演示重复下单拦截时请用其他账号或商品
INSERT INTO order_info (id, user_id, goods_id, delivery_addr_id, goods_name, goods_count, goods_price, order_channel, status, create_date, pay_date)
VALUES (1, 13800000005, 1, NULL, 'iPhone 15', 1, 5499.00, 1, 0, NOW() - INTERVAL 1 DAY, NULL);

INSERT INTO miaosha_order (id, user_id, goods_id, order_id)
VALUES (1, 13800000005, 1, 1);

-- ============================================================================
-- 初始化结果自检（容器日志可见）
-- ============================================================================
SELECT COUNT(*) AS goods_cnt          FROM goods;
SELECT COUNT(*) AS miaosha_goods_cnt  FROM miaosha_goods;
SELECT COUNT(*) AS user_cnt           FROM miaosha_user;
SELECT COUNT(*) AS order_cnt          FROM miaosha_order;
SELECT goods_id, start_date, end_date,
       (NOW() >= start_date AND NOW() < end_date) AS in_window
FROM miaosha_goods;
