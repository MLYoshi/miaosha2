package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.message.KafkaConfig;
import com.example.seckill.message.SeckillOrderMessage;
import com.example.seckill.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 04 — 消费侧可靠性（经真实 MySQL/Redis/Kafka 容器）。
 *
 * <ul>
 *   <li>毒消息（落库持续抛意外异常）→ 指数退避有限重试（1 次 + 3 次）→ 进死信 topic
 *       {@code seckill-order-dlt}；同分区后续消息继续被消费（主循环不卡死）
 *   <li>同一消息重复投递：已成功的快跳；未成功的由 DB 唯一键兜底——最终仅一单，库存不泄漏
 * </ul>
 *
 * <p>失败注入采用 DB 触发器（order_info 插入时 SIGNAL 45000）而非 @SpyBean：
 * 多个缓存 Spring 上下文的消费者同组分摊分区，消息可能被任一上下文消费，触发器在 DB 层
 * 注入失败对任意消费者生效；审计表同时给出确定性的重试次数观测点。
 */
class MiaoshaConsumerReliabilityTest extends AbstractIntegrationTest {

  /** 重试总时长约 1s+2s+4s=7s，加上调度余量。 */
  private static final Duration RETRY_WINDOW = Duration.ofSeconds(30);
  private static final String AUDIT_TABLE = "poison_audit";

  @Autowired private KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate;

  @Test
  void poisonMessage_retriesExhausted_thenDeadLettered_andLoopContinues() throws Exception {
    long poisonUser = insertUser(13000000400L);
    long poisonGoods = insertGoods("poisonPhone", 5);
    long normalUser = insertUser(13000000401L);
    long normalGoods = insertGoods("normalPhone", 5);

    try {
      installPoisonTrigger(poisonGoods);

      // 同 key → 同分区 → 毒消息之后的正常消息被顺序消费，其成功即证明位点推进、主循环未卡死
      String key = "reliability-sequence-key";
      kafkaTemplate
          .send(
              KafkaConfig.ORDER_TOPIC,
              key,
              new SeckillOrderMessage(poisonUser, poisonGoods, "req-poison-04"))
          .join();
      kafkaTemplate
          .send(
              KafkaConfig.ORDER_TOPIC,
              key,
              new SeckillOrderMessage(normalUser, normalGoods, "req-normal-04"))
          .join();

      // 落库尝试恰好 1（首次）+ 3（指数退避重试）次后停止
      awaitCondition(
          "毒消息应重试耗尽（1+3 次落库尝试）",
          RETRY_WINDOW,
          () -> poisonAttempts() >= 4);

      // 毒消息进入死信 topic，且携带可定位字段
      ConsumerRecord<String, String> dead = pollFirstFromDlt(RETRY_WINDOW);
      assertThat(dead).as("重试耗尽后消息应进入死信 topic").isNotNull();
      assertThat(dead.value())
          .contains(String.valueOf(poisonUser))
          .contains(String.valueOf(poisonGoods))
          .contains("req-poison-04");

      // 毒消息之后的同分区正常消息继续被消费 → 主循环不卡死、位点推进
      JsonNode normal = awaitResult(normalUser, normalGoods, RETRY_WINDOW);
      assertThat(normal.get("data").get("status").asText())
          .as("毒消息死信后，后续消息应继续被消费: %s", normal)
          .isEqualTo("SUCCESS");

      // 毒消息端态：不产生订单，每次尝试的扣库存都被事务回滚，落库尝试稳定在 4 次
      assertThat(miaoshaOrderCount()).as("毒消息不得产生秒杀订单").isEqualTo(1); // 仅 normalUser
      assertThat(dbStock(poisonGoods)).as("毒消息每次尝试的库存扣减应被事务回滚").isEqualTo(5);
      assertThat(poisonAttempts()).as("重试耗尽后不应再有落库尝试").isEqualTo(4);
    } finally {
      dropPoisonTrigger();
    }
  }

  @Test
  void duplicateDelivery_yieldsExactlyOneOrder_andNoStockLeak() throws Exception {
    long user = insertUser(13000000402L);
    long goodsId = insertGoods("iphoneX", 5);
    preheat(goodsId, user);

    JsonNode resp = doMiaosha(user, goodsId);
    assertThat(resp.get("code").asInt()).as(resp.toString()).isEqualTo(CODE_SUCCESS);
    JsonNode result = awaitResult(user, goodsId, RETRY_WINDOW);
    assertThat(result.get("data").get("status").asText()).isEqualTo("SUCCESS");
    long orderId = result.get("data").get("orderId").asLong();

    // 同一用户同一商品的重复投递（新 requestId 模拟另一条重复消息）
    kafkaTemplate
        .send(
            KafkaConfig.ORDER_TOPIC,
            UUID.randomUUID().toString(),
            new SeckillOrderMessage(user, goodsId, "req-duplicate-04"))
        .join();

    // 稳定窗口持续断言不变式：已成功快跳（或 DB 唯一键兜底）——仅一单、库存不泄漏
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      assertSingleOrderInvariants(goodsId, user, orderId);
      Thread.sleep(200);
    }
    assertSingleOrderInvariants(goodsId, user, orderId);
  }

  private void assertSingleOrderInvariants(long goodsId, long user, long orderId) {
    assertThat(orderCount()).as("重复投递不得产生重复订单").isEqualTo(1);
    assertThat(miaoshaOrderCount()).as("重复投递不得产生重复秒杀记录").isEqualTo(1);
    assertThat(duplicateMiaoshaOrderCount()).isZero();
    assertThat(dbStock(goodsId)).as("DB 库存只扣一次").isEqualTo(4);
    assertThat(redisStock(goodsId)).as("Redis 库存不得被补偿回补（泄漏）").isEqualTo(4);
    assertThat(redisResult(goodsId, user)).as("成功结果不得被覆盖").isEqualTo("SUCCESS:" + orderId);
  }

  // ---------- 失败注入：DB 触发器（跨上下文生效）+ 审计表（重试次数观测点） ----------

  private void installPoisonTrigger(long poisonGoodsId) {
    // MyISAM 非事务表：毒消息落库事务回滚时审计行保留，从而可精确观测重试次数
    jdbc.execute("DROP TABLE IF EXISTS " + AUDIT_TABLE);
    jdbc.execute("CREATE TABLE " + AUDIT_TABLE + " (n INT) ENGINE=MyISAM");
    jdbc.execute("DROP TRIGGER IF EXISTS poison_order_trigger");
    jdbc.execute(
        "CREATE TRIGGER poison_order_trigger BEFORE INSERT ON order_info FOR EACH ROW "
            + "BEGIN "
            + "IF NEW.goods_id = "
            + poisonGoodsId
            + " THEN "
            + "INSERT INTO "
            + AUDIT_TABLE
            + " VALUES (1); "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected poison'; "
            + "END IF; "
            + "END");
  }

  private void dropPoisonTrigger() {
    jdbc.execute("DROP TRIGGER IF EXISTS poison_order_trigger");
    jdbc.execute("DROP TABLE IF EXISTS " + AUDIT_TABLE);
  }

  private int poisonAttempts() {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + AUDIT_TABLE, Integer.class);
    return n == null ? 0 : n;
  }

  // ---------- 等待 / 死信消费辅助 ----------

  private void awaitCondition(String what, Duration timeout, BooleanSupplier cond)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (cond.getAsBoolean()) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError(what);
  }

  /** 用独立消费组从死信 topic 取第一条消息（String 反序列化，直接断言 JSON 载荷字段）。 */
  private ConsumerRecord<String, String> pollFirstFromDlt(Duration timeout) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-verifier-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    long deadline = System.nanoTime() + timeout.toNanos();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(KafkaConfig.ORDER_DLT_TOPIC));
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          return record;
        }
      }
    }
    return null;
  }
}
