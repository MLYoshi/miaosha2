package com.example.user.support;

import com.example.user.common.MD5Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * user-service 集成测试基座：Testcontainers 单例 MySQL 8，真实 HTTP（RANDOM_PORT）。
 *
 * <p>仅起 MySQL（user-service 不依赖 Redis/Kafka）；DDL 见 test resources/schema.sql（仅 miaosha_user）。
 *
 * <p>隔离策略：每个用例前 TRUNCATE miaosha_user（服务端事务无法从测试侧回滚，与 backend 同策略）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractUserIntegrationTest {

  protected static final String PLAIN_PASSWORD = "123456";
  protected static final String USER_SALT = "1a2b3c";

  protected static final int CODE_SUCCESS = 0;
  protected static final int CODE_MOBILE_NOT_EXIST = 500501;
  protected static final int CODE_PASSWORD_ERROR = 500502;
  protected static final int CODE_MOBILE_ALREADY_EXIST = 500503;

  // 单例容器模式：static 块显式启动，全测试类共享，JVM 退出时由 Testcontainers shutdown hook 回收
  @ServiceConnection
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
          .withDatabaseName("miaosha")
          .withInitScript("schema.sql");

  static {
    MYSQL.start();
  }

  @Autowired protected TestRestTemplate rest;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper objectMapper;

  @BeforeEach
  void resetState() {
    jdbc.execute("TRUNCATE TABLE miaosha_user");
  }

  // ---------- HTTP 辅助 ----------

  protected ResponseEntity<String> postJson(String path, String json) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
  }

  protected ResponseEntity<String> getWithToken(String path, String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  protected JsonNode body(ResponseEntity<String> resp) {
    try {
      return objectMapper.readTree(resp.getBody());
    } catch (Exception e) {
      throw new AssertionError("响应不是合法 JSON: " + resp.getBody(), e);
    }
  }

  // ---------- Fixture ----------

  /** 按种子约定插入用户（密码 = 明文 123456 + 固定 salt 1a2b3c 的双层 MD5），返回手机号即 id。 */
  protected long insertUser(long mobile) {
    jdbc.update(
        "INSERT INTO miaosha_user (id, nickname, password, salt, register_date, login_count)"
            + " VALUES (?,?,?,?,NOW(),0)",
        mobile,
        "user" + mobile,
        MD5Util.inputPassToDbPass(PLAIN_PASSWORD, USER_SALT),
        USER_SALT);
    return mobile;
  }
}
