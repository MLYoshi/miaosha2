package com.example.miaosha;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.miaosha.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 秒杀受理 API 集成测试（Testcontainers Redis + Kafka，真实 HTTP）：
 * 正常受理（预扣 + PROCESSING + 消息入队）、重复下单、库存不足、未预热。
 *
 * <p>Step 4 验证边界：到「Redis 预扣 + PROCESSING + Kafka 消息成功入队」为止，
 * 消费与 SUCCESS 回写归 Step 5 order-service。
 */
class MiaoshaAcceptApiTest extends AbstractIntegrationTest {

  private static final Duration KAFKA_WAIT = Duration.ofSeconds(30);

  @Test // 正常受理：code=0 + PROCESSING，消息入队且三字段与 Redis 抢购标记一致
  void normalAccept_processingAndMessageEnqueued() {
    long goodsId = 8100L;
    long userId = 1001L;
    preheat(goodsId, 5);

    JsonNode resp = doMiaosha(userId, goodsId);

    assertThat(resp.get("code").asInt()).as("受理成功").isEqualTo(CODE_SUCCESS);
    assertThat(resp.path("data").path("status").asText()).isEqualTo("PROCESSING");
    assertThat(resp.path("data").path("orderId").isNull())
        .as("受理中不携带订单号")
        .isTrue();

    List<JsonNode> messages = awaitKafkaMessages(goodsId, 1, KAFKA_WAIT);
    assertThat(messages).as("应恰好入队一条下单消息").hasSize(1);
    assertThat(messages.get(0).path("userId").asLong()).isEqualTo(userId);
    assertThat(messages.get(0).path("goodsId").asLong()).isEqualTo(goodsId);
    assertThat(messages.get(0).path("requestId").asText())
        .as("消息 requestId 应与 Redis user 标记一致（补偿归属校验用）")
        .isEqualTo(redisUserMark(goodsId, userId));

    assertThat(redisStock(goodsId)).as("Redis 库存应扣减").isEqualTo(4);
    assertThat(redisResult(goodsId, userId)).isEqualTo("PROCESSING");

    // 轮询接口返回排队中
    JsonNode result = result(userId, goodsId);
    assertThat(result.get("code").asInt()).isEqualTo(CODE_SUCCESS);
    assertThat(result.path("data").path("status").asText()).isEqualTo("PROCESSING");
  }

  @Test // 重复下单：500212，不重复发消息、不重复扣库存
  void repeat_500212_noExtraMessageOrDeduction() {
    long goodsId = 8200L;
    long userId = 1002L;
    preheat(goodsId, 5);

    assertThat(doMiaosha(userId, goodsId).get("code").asInt()).isEqualTo(CODE_SUCCESS);
    awaitKafkaMessages(goodsId, 1, KAFKA_WAIT);

    JsonNode second = doMiaosha(userId, goodsId);
    assertThat(second.get("code").asInt()).isEqualTo(CODE_MIAOSHA_REPEAT);
    assertThat(second.get("msg").asText()).isEqualTo("不能重复秒杀");

    assertThat(redisStock(goodsId)).as("重复请求不得再扣库存").isEqualTo(4);
    assertThat(pollKafkaMessageCount(goodsId, Duration.ofSeconds(3)))
        .as("重复请求不得再发消息")
        .isEqualTo(1);
  }

  @Test // 库存为 0（预热 0）→ 500214，不发消息
  void preheatZeroStock_500214_noMessage() {
    long goodsId = 8300L;
    long userId = 1003L;
    preheat(goodsId, 0);

    JsonNode resp = doMiaosha(userId, goodsId);

    assertThat(resp.get("code").asInt()).isEqualTo(CODE_MIAOSHA_STOCK_EMPTY);
    assertThat(redisUserMark(goodsId, userId)).as("失败不得留抢购标记").isNull();
    assertThat(pollKafkaMessageCount(goodsId, Duration.ofSeconds(3))).isZero();
  }

  @Test // 库存被抢空后 → 500214，不发消息
  void drainedStock_500214_noMessage() {
    long goodsId = 8400L;
    long first = 1004L;
    long second = 1005L;
    preheat(goodsId, 1);

    assertThat(doMiaosha(first, goodsId).get("code").asInt()).isEqualTo(CODE_SUCCESS);
    awaitKafkaMessages(goodsId, 1, KAFKA_WAIT);

    JsonNode resp = doMiaosha(second, goodsId);
    assertThat(resp.get("code").asInt()).isEqualTo(CODE_MIAOSHA_STOCK_EMPTY);
    assertThat(redisStock(goodsId)).isZero();
    assertThat(pollKafkaMessageCount(goodsId, Duration.ofSeconds(3)))
        .as("库存不足不得再发消息")
        .isEqualTo(1);
  }

  @Test // F9：未预热（库存 key 不存在）→ 500214，先于商品校验，不发消息
  void notPreheated_500214_noMessage() {
    long goodsId = 8500L;
    long userId = 1006L;

    JsonNode resp = doMiaosha(userId, goodsId);

    assertThat(resp.get("code").asInt()).isEqualTo(CODE_MIAOSHA_STOCK_EMPTY);
    assertThat(redisUserMark(goodsId, userId)).isNull();
    assertThat(pollKafkaMessageCount(goodsId, Duration.ofSeconds(3))).isZero();
  }

  @Test // 未参与过：result 四态之 NONE
  void resultOfNeverParticipated_none() {
    long goodsId = 8600L;
    long userId = 1007L;

    JsonNode result = result(userId, goodsId);

    assertThat(result.get("code").asInt()).isEqualTo(CODE_SUCCESS);
    assertThat(result.path("data").path("status").asText()).isEqualTo("NONE");
    assertThat(result.path("data").path("orderId").isNull()).isTrue();
  }
}
