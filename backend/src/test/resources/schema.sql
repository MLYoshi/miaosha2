-- 测试库 DDL：按 docs/db-design.md 第 3 节生成（5 张业务表，不含遗留 user 表）
-- 由 Testcontainers MySQL 容器启动时加载（withInitScript）

CREATE TABLE IF NOT EXISTS goods (
  id          BIGINT(20) NOT NULL AUTO_INCREMENT,
  goods_name  VARCHAR(16)  DEFAULT NULL,
  goods_title VARCHAR(64)  DEFAULT NULL,
  goods_img   VARCHAR(64)  DEFAULT NULL,
  goods_detail LONGTEXT,
  goods_price DECIMAL(10,2) DEFAULT NULL,
  goods_stock INT(11)      DEFAULT NULL,
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
