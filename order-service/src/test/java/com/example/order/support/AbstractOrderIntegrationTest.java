package com.example.order.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import com.example.order.client.GoodsClient;
import com.example.order.message.SeckillOrderMessage;
import com.example.order.vo.GoodsSnapshotVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * order-service 集成测试基座：Testcontainers 单例 MySQL 8 + Redis 7 + Kafka（confluent 7.5）。
 *
 * <p>goods-service 不启动：{@link GoodsClient} 以 {@code @MockBean} 打桩（内存库存 CAS 语义，
 * 等价 goods-service 条件扣减 {@code stock_count > 0}），并记录 deduct/restore/快照调用次数
 * 供「不重复扣减 / 补偿恰好一次」断言。
 *
 * <p>隔离策略：每个用例前 TRUNCATE 两表 + Redis FLUSHALL；Kafka topic 消息按 goodsId 过滤
 * （各用例使用互不相同的 goodsId）。测试生产者与 miaosha-service 契约一致：纯 JSON、
 * 无类型头，key 用 UUID 随机打散。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractOrderIntegrationTest {

  protected static final int CODE_MIAOSHA_REPEAT = 500212;
  protected static final int CODE_MIAOSHA_STOCK_EMPTY = 500214;
  protected static final int CODE_MIAOSHA_OVER = 500216;

  public static final String ORDER_TOPIC = "seckill-order";
  public static final String ORDER_DLT_TOPIC = "seckill-order-dlt";

  // 单例容器模式：static 块显式启动，全测试类共享，JVM 退出时由 Testcontainers 回收
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
          .withInitScript("schema.sql")
          .withStartupTimeoutSeconds(240);

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

  static {
    MYSQL.start();
    REDIS.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void containerProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // 容器未设密码，覆盖 yml 中的 123456（空串 = 不 AUTH）
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    // 消费组随上下文启动，earliest 保证先建上下文后发消息也不丢
    registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    // goods-service 未启动：GoodsClient 已 mock，base-url 指向死端口占位
    registry.add("goods.base-url", () -> "http://localhost:1");
  }

  @MockBean protected GoodsClient goodsClient;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected StringRedisTemplate redisTemplate;
  @Autowired protected ObjectMapper objectMapper;

  /** 打桩后的调用计数（每次测试由 stub 方法重建）。 */
  protected AtomicInteger snapshotCalls;
  protected AtomicInteger deductCalls;
  /** deductStock 实际扣减成功（返回 1）的次数，用于防超卖断言。 */
  protected AtomicInteger deductSuccessCalls;
  protected AtomicInteger restoreCalls;

  private volatile KafkaTemplate<String, String> rawTemplate;

  @BeforeEach
  void resetEnvironment() {
    jdbc.update("TRUNCATE TABLE miaosha_order");
    jdbc.update("TRUNCATE TABLE order_info");
    redisTemplate.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  // ---------- GoodsClient 打桩 ----------

  /** 在窗口内的默认商品快照。 */
  protected GoodsSnapshotVo snapshot(long goodsId) {
    return snapshot(goodsId, "测试商品-" + goodsId, null, null);
  }

  /** 自定义快照：可指定秒杀时间窗（null = 立即开始 / 永不过期）。 */
  protected GoodsSnapshotVo snapshot(
      long goodsId, String goodsName, LocalDateTime startDate, LocalDateTime endDate) {
    GoodsSnapshotVo vo = new GoodsSnapshotVo();
    vo.setId(goodsId);
    vo.setGoodsName(goodsName);
    vo.setMiaoshaPrice(new BigDecimal("99.00"));
    vo.setStartDate(startDate);
    vo.setEndDate(endDate);
    return vo;
  }

  /**
   * 打桩为正常 goods-service：内存库存 CAS 扣减（等价 {@code stock_count > 0} 条件更新，
   * 单线程消费下 deduct 恰好成功 stock 次），restoreStock 无条件 +1。
   */
  protected void stubGoodsClient(GoodsSnapshotVo vo, int stock) {
    snapshotCalls = new AtomicInteger();
    deductCalls = new AtomicInteger();
    deductSuccessCalls = new AtomicInteger();
    restoreCalls = new AtomicInteger();
    AtomicInteger remaining = new AtomicInteger(stock);
    given(goodsClient.getGoodsVo(any())).willAnswer(inv -> {
      snapshotCalls.incrementAndGet();
      return vo;
    });
    given(goodsClient.deductStock(any(), any()))
        .willAnswer(
            inv -> {
              deductCalls.incrementAndGet();
              while (true) {
                int cur = remaining.get();
                if (cur <= 0) {
                  return 0;
                }
                if (remaining.compareAndSet(cur, cur - 1)) {
                  deductSuccessCalls.incrementAndGet();
                  return 1;
                }
              }
            });
    // restoreStock 返回 void：given() 不能接收 void 表达式，须用 doAnswer().when() 风格
    doAnswer(
            inv -> {
              restoreCalls.incrementAndGet();
              return null;
            })
        .when(goodsClient)
        .restoreStock(any());
  }

  /** 打桩为 deductStock 抛意外异常（模拟 goods-service 连接类故障，走重试 → DLT）。 */
  protected void stubGoodsClientThrowing(GoodsSnapshotVo vo, RuntimeException cause) {
    snapshotCalls = new AtomicInteger();
    deductCalls = new AtomicInteger();
    deductSuccessCalls = new AtomicInteger();
    restoreCalls = new AtomicInteger();
    given(goodsClient.getGoodsVo(any()))
        .willAnswer(
            inv -> {
              snapshotCalls.incrementAndGet();
              return vo;
            });
    given(goodsClient.deductStock(any(), any()))
        .willAnswer(
            inv -> {
              deductCalls.incrementAndGet();
              throw cause;
            });
  }

  // ---------- Kafka 生产 / 观测 ----------

  /** 与 miaosha-service 生产者契约一致的原始模板：纯 JSON、无类型头、String 序列化。 */
  protected KafkaTemplate<String, String> rawKafkaTemplate() {
    if (rawTemplate == null) {
      Map<String, Object> props = new HashMap<>();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
      rawTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
    return rawTemplate;
  }

  /** 发送下单消息（key 用 UUID 随机打散，与生产语义一致）。 */
  protected void sendOrderMessage(long userId, long goodsId, String requestId) {
    sendOrderMessage(userId, goodsId, requestId, UUID.randomUUID().toString());
  }

  /**
   * 发送下单消息，可指定 partition key。同一 key 的消息保证同分区有序，
   * 用于「重复投递批次 + 哨兵消息」的完成观测。
   */
  protected void sendOrderMessage(
      long userId, long goodsId, String requestId, String partitionKey) {
    try {
      String json = objectMapper.writeValueAsString(new SeckillOrderMessage(userId, goodsId, requestId));
      rawKafkaTemplate().send(ORDER_TOPIC, partitionKey, json).get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("发送测试消息失败 requestId=" + requestId, e);
    }
  }

  /**
   * 轮询 DLT topic，收集指定 goodsId 的死信原始 JSON，直到达到期望数或超时。
   * expected = 0 表示负向断言：拉满整个窗口，返回窗口内观测到的全部死信。
   */
  protected List<String> awaitDltRecords(long goodsId, int expected, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<String> matched = new ArrayList<>();
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-observe-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(ORDER_DLT_TOPIC));
      while (System.nanoTime() < deadline && (expected == 0 || matched.size() < expected)) {
        consumer.poll(Duration.ofMillis(300))
            .forEach(
                record -> {
                  try {
                    var node = objectMapper.readTree(record.value());
                    if (node.hasNonNull("goodsId") && node.get("goodsId").asLong() == goodsId) {
                      matched.add(record.value());
                    }
                  } catch (Exception e) {
                    throw new AssertionError("死信消息不是合法 JSON: " + record.value(), e);
                  }
                });
      }
    }
    return matched;
  }

  // ---------- DB 观测 ----------

  protected int countOrderInfo(long goodsId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_info WHERE goods_id = ?", Integer.class, goodsId);
    return n == null ? 0 : n;
  }

  protected int countMiaoshaOrder(long goodsId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM miaosha_order WHERE goods_id = ?", Integer.class, goodsId);
    return n == null ? 0 : n;
  }

  protected int countAllMiaoshaOrders() {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM miaosha_order", Integer.class);
    return n == null ? 0 : n;
  }

  /** 全表 DISTINCT(user_id, goods_id) 行数，用于无重复断言。 */
  protected int countDistinctUserGoods() {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT DISTINCT user_id, goods_id FROM miaosha_order) t",
            Integer.class);
    return n == null ? 0 : n;
  }

  /** 孤儿订单数：miaosha_order.order_id 在 order_info 中不存在的行数（应为 0）。 */
  protected int countOrphanOrders() {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM miaosha_order m LEFT JOIN order_info o"
                + " ON m.order_id = o.id WHERE o.id IS NULL",
            Integer.class);
    return n == null ? 0 : n;
  }

  protected Long orderIdOf(long userId, long goodsId) {
    return jdbc.queryForObject(
        "SELECT order_id FROM miaosha_order WHERE user_id = ? AND goods_id = ?",
        Long.class,
        userId,
        goodsId);
  }

  protected Map<String, Object> orderInfoRow(long orderId) {
    return jdbc.queryForMap("SELECT * FROM order_info WHERE id = ?", orderId);
  }

  // ---------- Redis 观测 ----------

  protected String redisResult(long goodsId, long userId) {
    return redisTemplate.opsForValue().get("miaosha:result:" + goodsId + ":" + userId);
  }

  protected int redisStock(long goodsId) {
    String v = redisTemplate.opsForValue().get("miaosha:stock:" + goodsId);
    return v == null ? -1 : Integer.parseInt(v);
  }

  protected int countRedisSuccessResults(long goodsId) {
    Set<String> keys = redisTemplate.keys("miaosha:result:" + goodsId + ":*");
    int n = 0;
    if (keys != null) {
      for (String key : new HashSet<>(keys)) {
        String v = redisTemplate.opsForValue().get(key);
        if (v != null && v.startsWith("SUCCESS:")) {
          n++;
        }
      }
    }
    return n;
  }

  // ---------- 轮询等待 ----------

  /** 每 200ms 轮询条件直到满足或超时；超时则以断言失败收尾（报告描述与时长）。 */
  protected void awaitUntil(String description, BooleanSupplier condition, Duration timeout) {
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
