-- user-service 测试库 DDL：从 backend/src/test/resources/schema.sql 摘取 miaosha_user 表
-- （user-service 数据所有权：仅 miaosha_user，见 AGENTS.md）
-- 由 Testcontainers MySQL 容器启动时加载（withInitScript）

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
