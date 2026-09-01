-- order-service 测试库 DDL：从 backend/src/test/resources/schema.sql 复制订单相关两表
-- 字段与 UNIQUE(user_id, goods_id) 唯一键必须与事实基线完全一致
-- 由 Testcontainers MySQL 容器启动时加载（withInitScript）

CREATE TABLE IF NOT EXISTS miaosha_order (
  id       BIGINT(20) NOT NULL AUTO_INCREMENT,
  user_id  BIGINT(20) DEFAULT NULL,
  order_id BIGINT(20) DEFAULT NULL,
  goods_id BIGINT(20) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_uid_gid (user_id, goods_id)
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
