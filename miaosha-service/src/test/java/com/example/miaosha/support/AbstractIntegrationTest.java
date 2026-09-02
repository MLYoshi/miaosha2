package com.example.miaosha.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.miaosha.cache.MiaoshaRedisStore;
import com.example.miaosha.message.KafkaConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基座：Testcontainers 单例 Redis 7 + Kafka（confluent 7.5，KRaft 单节点），
 * 真实 HTTP（RANDOM_PORT）。无 MySQL——miaosha-service 不落库（消费与回写归 Step 5）。
 *
 * <p>隔离策略：每个用例前 Redis FLUSHALL；Kafka topic 消息按 goodsId 过滤（各用例
 * 使用互不相同的 goodsId），消费组每次新建 + earliest。
 *
 * <p>goods-service / order-service 未启动：base-url 指向死端口（预热在测试内经
 * {@link MiaoshaRedisStore#setStock} 直写，降级接缝失败路径由专门的降级测试类覆盖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

  protected static final int CODE_SUCCESS = 0;
  protected static final int CODE_MIAOSHA_REPEAT = 500212;
  protected static final int CODE_MIAOSHA_STOCK_EMPTY = 500214;
  protected static final int CODE_SERVER_ERROR = 500100;

  // 单例容器模式：static 块显式启动，全测试类共享，JVM 退出时由 Testcontainers shutdown hook 回收
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

  static {
    REDIS.start();
    KAFKA.start();
  }

  @org.springframework.test.context.DynamicPropertySource
  static void containerProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // 容器未设密码，覆盖 yml 中的 123456（空串 = 不 AUTH）
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("goods.base-url", () -> "http://localhost:1");
    registry.add("order.sync-base-url", () -> "http://localhost:1");
    // 测试上下文禁用 Nacos 注册，避免反复连接注册中心
    registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
  }

  @Autowired protected TestRestTemplate rest;
  @Autowired protected StringRedisTemplate redisTemplate;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected MiaoshaRedisStore store;

  @BeforeEach
  void resetState() {
    redisTemplate.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  // ---------- HTTP 辅助 ----------

  protected ResponseEntity<String> get(String path, Long userId) {
    HttpHeaders headers = new HttpHeaders();
    if (userId != null) {
      headers.set("X-User-Id", String.valueOf(userId));
    }
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  /** 带身份头的 POST（写操作契约：do_miaosha / 预热均为 POST）。 */
  protected ResponseEntity<String> post(String path, Long userId) {
    HttpHeaders headers = new HttpHeaders();
    if (userId != null) {
      headers.set("X-User-Id", String.valueOf(userId));
    }
    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
  }

  protected JsonNode body(ResponseEntity<String> resp) {
    try {
      return objectMapper.readTree(resp.getBody());
    } catch (Exception e) {
      throw new AssertionError("响应不是合法 JSON: " + resp.getBody(), e);
    }
  }

  protected JsonNode doMiaosha(long userId, long goodsId) {
    return body(post("/miaosha/do_miaosha?goodsId=" + goodsId, userId));
  }

  protected JsonNode result(long userId, long goodsId) {
    return body(get("/miaosha/result?goodsId=" + goodsId, userId));
  }

  // ---------- Fixture ----------

  /** 预热 Redis 库存（直接经接缝写入，不依赖 goods-service；TTL 固定 1 小时）。 */
  protected void preheat(long goodsId, int stock) {
    store.setStock(goodsId, stock, Duration.ofHours(1));
  }

  // ---------- Redis 状态观测 ----------

  protected int redisStock(long goodsId) {
    String v = redisTemplate.opsForValue().get(com.example.miaosha.cache.RedisKeyBuilder.stock(goodsId));
    return v == null ? -1 : Integer.parseInt(v);
  }

  protected String redisUserMark(long goodsId, long userId) {
    return redisTemplate
        .opsForValue()
        .get(com.example.miaosha.cache.RedisKeyBuilder.user(goodsId, userId));
  }

  protected String redisResult(long goodsId, long userId) {
    return redisTemplate
        .opsForValue()
        .get(com.example.miaosha.cache.RedisKeyBuilder.result(goodsId, userId));
  }

  // ---------- Kafka 观测 ----------

  private KafkaConsumer<String, String> newKafkaConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  /**
   * 轮询 {@code seckill-order} topic，收集指定 goodsId 的下单消息，直到达到期望数或超时。
   *
   * <p>消息按 goodsId 过滤 + requestId 去重：topic 为全测试类共享，隔离历史消息。
   */
  protected List<JsonNode> awaitKafkaMessages(long goodsId, int expected, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<JsonNode> matched = new ArrayList<>();
    List<String> seenRequestIds = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = newKafkaConsumer()) {
      consumer.subscribe(List.of(KafkaConfig.ORDER_TOPIC));
      while (System.nanoTime() < deadline && matched.size() < expected) {
        for (var record : consumer.poll(Duration.ofMillis(300))) {
          collect(record, goodsId, matched, seenRequestIds);
        }
      }
    }
    return matched;
  }

  /**
   * 在给定时间窗内统计指定 goodsId 的下单消息数（requestId 去重）。
   *
   * <p>用于「不应再发消息」的负向断言：拉满整个窗口，结果即窗口内观测到的消息总量。
   */
  protected int pollKafkaMessageCount(long goodsId, Duration window) {
    long deadline = System.nanoTime() + window.toNanos();
    List<JsonNode> matched = new ArrayList<>();
    List<String> seenRequestIds = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = newKafkaConsumer()) {
      consumer.subscribe(List.of(KafkaConfig.ORDER_TOPIC));
      while (System.nanoTime() < deadline) {
        for (var record : consumer.poll(Duration.ofMillis(300))) {
          collect(record, goodsId, matched, seenRequestIds);
        }
      }
    }
    return matched.size();
  }

  private void collect(
      org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
      long goodsId,
      List<JsonNode> matched,
      List<String> seenRequestIds) {
    try {
      JsonNode node = objectMapper.readTree(record.value());
      if (!node.hasNonNull("goodsId") || node.get("goodsId").asLong() != goodsId) {
        return;
      }
      String requestId = node.path("requestId").asText(null);
      if (requestId != null && seenRequestIds.contains(requestId)) {
        return;
      }
      seenRequestIds.add(requestId);
      matched.add(node);
    } catch (Exception e) {
      throw new AssertionError("消息不是合法 JSON: " + record.value(), e);
    }
  }

  /** 断言辅助：requestId 非空且互不重复。 */
  protected void assertDistinctRequestIds(List<JsonNode> messages) {
    List<String> ids = new ArrayList<>();
    for (JsonNode msg : messages) {
      String requestId = msg.path("requestId").asText(null);
      assertThat(requestId).as("消息 requestId 不应缺失").isNotBlank();
      assertThat(ids).as("requestId 不应重复").doesNotContain(requestId);
      ids.add(requestId);
    }
  }
}
