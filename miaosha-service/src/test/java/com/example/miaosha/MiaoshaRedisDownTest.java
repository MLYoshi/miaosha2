package com.example.miaosha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis 不可用降级集成测试（真实 HTTP，无容器——Redis / Kafka / order-service 均为死端口）。
 *
 * <p>既有降级编排：Redis 预扣异常 → 经 SyncOrderClient 直连 order-service 同步下单；
 * Step 4 端点未就绪 → 降级失败原样上抛 → 全局异常处理器转换为结构化服务端错误
 * （HTTP 200 + code=500100），不得 500 崩溃或堆栈泄露。
 *
 * <p>生产者 / KafkaAdmin 面向死端口为非致命（fail-fast=false），仅日志告警。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.data.redis.host=localhost",
      "spring.data.redis.port=1",
      "spring.data.redis.password=",
      "spring.kafka.bootstrap-servers=localhost:9099",
      "goods.base-url=http://localhost:1",
      "order.sync-base-url=http://localhost:1",
      "spring.cloud.nacos.discovery.enabled=false"
    })
class MiaoshaRedisDownTest {

  private static final long GOODS_ID = 9300L;
  private static final long USER_ID = 3002L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  @Test // Redis 不可用 → 降级接缝失败 → 结构化错误，而非堆栈泄露
  void redisDown_returnsStructuredError() {
    ResponseEntity<String> resp = post("/miaosha/do_miaosha?goodsId=" + GOODS_ID, USER_ID);

    assertThat(resp.getStatusCode().value())
        .as("全局异常处理器应保持 HTTP 200 契约")
        .isEqualTo(200);
    JsonNode body = parse(resp);
    assertThat(body.get("code").asInt()).isEqualTo(500100);
    assertThat(body.get("msg").asText()).isEqualTo("服务端异常");
    assertThat(body.get("data").isNull()).isTrue();
  }

  // ---------- 辅助 ----------

  private ResponseEntity<String> post(String path, long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-User-Id", String.valueOf(userId));
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
  }

  private JsonNode parse(ResponseEntity<String> resp) {
    try {
      return MAPPER.readTree(resp.getBody());
    } catch (Exception e) {
      throw new AssertionError("响应不是合法 JSON: " + resp.getBody(), e);
    }
  }
}
