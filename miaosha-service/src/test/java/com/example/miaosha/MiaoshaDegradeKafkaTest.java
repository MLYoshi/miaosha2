package com.example.miaosha;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.common.JwtUtil;
import com.example.miaosha.cache.MiaoshaRedisStore;
import com.example.miaosha.cache.RedisKeyBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Kafka 不可用降级集成测试（Testcontainers Redis + 真实 HTTP）。
 *
 * <p>故障注入用死端口（避免停容器的测试抖动）：bootstrap-servers 指向
 * localhost:9099 + 缩短生产者超时，发送在秒级内失败，触发既有降级编排。
 *
 * <p>不变式（Step 4 端点未就绪）：Kafka 发送失败 → 降级同步下单（order-service
 * 同样不可达）→ 必须补偿 Redis 后上抛——不产生「已扣库存 + 永久失败 + 无补偿」。
 * 用户看到结构化服务端错误（HTTP 200 + code=500100）。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Kafka 死端口 + 快速失败，避免默认 delivery.timeout 120s 拖垮测试
      "spring.kafka.bootstrap-servers=localhost:9099",
      "spring.kafka.producer.properties.max.block.ms=2000",
      "spring.kafka.producer.properties.delivery.timeout.ms=3000",
      "spring.kafka.producer.properties.request.timeout.ms=2000"
    })
class MiaoshaDegradeKafkaTest {

  private static final long GOODS_ID = 9200L;
  private static final long USER_ID = 3001L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    // 预热数据源与降级接缝均为死端口（本测试不依赖 goods-service / order-service）
    registry.add("goods.base-url", () -> "http://localhost:1");
    registry.add("order.sync-base-url", () -> "http://localhost:1");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private MiaoshaRedisStore store;

  @Test // Kafka 失败 → 降级也失败 → 补偿 Redis，返回结构化错误
  void kafkaDown_degradeFails_compensatesRedisAndReturnsStructuredError() {
    preheat(GOODS_ID, 5);

    ResponseEntity<String> resp = post("/miaosha/do_miaosha?goodsId=" + GOODS_ID, USER_ID);
    JsonNode body = parse(resp);

    assertThat(body.get("code").asInt())
        .as("降级失败应返回统一服务端错误（HTTP 200 + 业务码）")
        .isEqualTo(500100);

    // 补偿不变式：库存回补、标记清除、result=FAILED
    assertThat(redisStock()).as("库存应回补").isEqualTo(5);
    assertThat(redisUserMark()).as("user 标记应清除").isNull();
    assertThat(redisResult()).as("result 应记 FAILED").isEqualTo("FAILED");
  }

  @Test // 幂等重试：再次受理仍走完整降级补偿链路，库存不泄漏
  void retryAfterKafkaDown_doesNotLeakStock() {
    preheat(GOODS_ID, 5);

    for (int i = 0; i < 2; i++) {
      ResponseEntity<String> resp = post("/miaosha/do_miaosha?goodsId=" + GOODS_ID, USER_ID);
      assertThat(parse(resp).get("code").asInt()).isEqualTo(500100);
    }

    assertThat(redisStock()).as("反复重试库存应守恒").isEqualTo(5);
    assertThat(redisUserMark()).isNull();
    assertThat(redisResult()).isEqualTo("FAILED");
  }

  // ---------- 辅助 ----------

  private void preheat(long goodsId, int stock) {
    store.setStock(goodsId, stock, Duration.ofHours(1));
  }

  private ResponseEntity<String> post(String path, long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(JwtUtil.generateToken(userId));
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
  }

  private JsonNode parse(ResponseEntity<String> resp) {
    try {
      return MAPPER.readTree(resp.getBody());
    } catch (Exception e) {
      throw new AssertionError("响应不是合法 JSON: " + resp.getBody(), e);
    }
  }

  private String stockKey() {
    return RedisKeyBuilder.stock(GOODS_ID);
  }

  private String userKey() {
    return RedisKeyBuilder.user(GOODS_ID, USER_ID);
  }

  private String resultKey() {
    return RedisKeyBuilder.result(GOODS_ID, USER_ID);
  }

  private int redisStock() {
    String v = redisTemplate.opsForValue().get(stockKey());
    return v == null ? -1 : Integer.parseInt(v);
  }

  private String redisUserMark() {
    return redisTemplate.opsForValue().get(userKey());
  }

  private String redisResult() {
    return redisTemplate.opsForValue().get(resultKey());
  }
}
