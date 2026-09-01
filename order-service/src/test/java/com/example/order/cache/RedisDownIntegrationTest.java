package com.example.order.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.example.order.client.GoodsClient;
import com.example.order.message.SeckillOrderMessage;
import com.example.order.vo.GoodsSnapshotVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 场景 5：Redis 不可用，订单仍完成（独立容器，Redis 指向死端口注入故障）。
 *
 * <p>契约（RedisOrderResultStore 尽力而为语义）：
 *
 * <ul>
 *   <li>getResult 失败返回 null → 幂等快跳失效，但 DB 幂等预检 + 唯一键兜底仍生效</li>
 *   <li>markSuccess 失败只记日志，已完成的数据库订单事实不受影响</li>
 *   <li>compensate 失败只记日志，消息仍 ack，不产生重试风暴</li>
 * </ul>
 *
 * <p>独立启动 MySQL + Kafka（与基座隔离）：避免与其他用例共享消费组「seckill」导致
 * 分区被空闲消费者抢占。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RedisDownIntegrationTest {

  private static final long USER_ID = 33001L;

  /** 故障注入端口：先占用再释放，保证死端口。 */
  private static final int REDIS_DEAD_PORT = findClosedPort();

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
          .withInitScript("schema.sql")
          .withStartupTimeoutSeconds(240);

  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

  static {
    MYSQL.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void containerProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    // Redis 故障注入：连接拒绝
    registry.add("spring.data.redis.host", () -> "localhost");
    registry.add("spring.data.redis.port", () -> REDIS_DEAD_PORT);
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    registry.add("goods.base-url", () -> "http://localhost:1");
  }

  private static int findClosedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (Exception e) {
      throw new IllegalStateException("无法找到死端口", e);
    }
  }

  @MockBean private GoodsClient goodsClient;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  private final AtomicInteger deductCalls = new AtomicInteger();

  @BeforeEach
  void resetDb() {
    jdbc.update("TRUNCATE TABLE miaosha_order");
    jdbc.update("TRUNCATE TABLE order_info");
    deductCalls.set(0);
    given(goodsClient.getGoodsVo(any())).willReturn(snapshot());
    given(goodsClient.deductStock(any(), any()))
        .willAnswer(
            inv -> {
              deductCalls.incrementAndGet();
              return 1;
            });
  }

  @Test
  void redisDown_orderStillCompleted() {
    sendOrderMessage(USER_ID, 93001L, "req-redis-down-1");

    awaitUntil("Redis 不可用时订单仍应落库", () -> count("order_info") == 1, Duration.ofSeconds(60));

    Long orderId =
        jdbc.queryForObject(
            "SELECT order_id FROM miaosha_order WHERE user_id = ? AND goods_id = ?",
            Long.class,
            USER_ID,
            93001L);
    assertThat(orderId).as("订单事实不因 markSuccess 失败而丢失").isNotNull();
    assertThat(deductCalls.get()).isEqualTo(1);
  }

  @Test // Redis 不可用下重复投递：result 缺失 → 快跳失效，DB 唯一键/预检兜底仍拦下
  void redisDown_duplicateMessage_stillProtectedByDb() {
    sendOrderMessage(USER_ID, 93002L, "req-redis-down-2");
    awaitUntil("首条消息落库", () -> count("order_info") == 1, Duration.ofSeconds(60));

    sendOrderMessage(USER_ID, 93002L, "req-redis-down-3");
    try {
      Thread.sleep(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(count("order_info")).as("DB 幂等兜底：仍只有一单").isEqualTo(1);
    assertThat(count("miaosha_order")).isEqualTo(1);
    assertThat(deductCalls.get()).as("重复消息不重复扣库存").isEqualTo(1);
  }

  // ---------- 辅助 ----------

  private GoodsSnapshotVo snapshot() {
    GoodsSnapshotVo vo = new GoodsSnapshotVo();
    vo.setId(1L);
    vo.setGoodsName("测试商品");
    vo.setMiaoshaPrice(new BigDecimal("99.00"));
    return vo;
  }

  private void sendOrderMessage(long userId, long goodsId, String requestId) {
    try {
      Map<String, Object> props = new HashMap<>();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
      KafkaTemplate<String, String> template =
          new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
      String json = objectMapper.writeValueAsString(new SeckillOrderMessage(userId, goodsId, requestId));
      template.send("seckill-order", UUID.randomUUID().toString(), json).get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("发送测试消息失败", e);
    }
  }

  private int count(String table) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    return n == null ? 0 : n;
  }

  private void awaitUntil(String description, BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(condition.getAsBoolean())
        .as(description + "（等待 %ds 超时）", timeout.toSeconds())
        .isTrue();
  }
}
