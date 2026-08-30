package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.cache.RedisKeyBuilder;
import com.example.seckill.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 02：秒杀结果轮询接口 GET /miaosha/result，四态可判别 + DB 兜底。
 *
 * <p>票 03 受理异步化后：受理态经轮询拿单（受理 → 等待消费完成 → 轮询断言），
 * do_miaosha 不再返回订单详情。
 */
class MiaoshaResultApiTest extends AbstractIntegrationTest {

  private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(20);

  private JsonNode pollResult(long userId, long goodsId) {
    ResponseEntity<String> resp = get("/miaosha/result?goodsId=" + goodsId, userId);
    assertThat(resp.getStatusCode().value()).as("轮询应返回 200: %s", resp.getBody()).isEqualTo(200);
    return body(resp);
  }

  @Test // 异步全链路：受理（受理中）→ 消费落库 → 轮询到 SUCCESS 且携带订单号
  void fullFlow_acceptThenPollToSuccess() {
    long user = insertUser(13000000200L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);

    JsonNode miaosha = doMiaosha(user, goodsId);
    assertThat(miaosha.get("code").asInt()).as(miaosha.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(miaosha.get("data").get("status").asText()).isEqualTo("PROCESSING");

    JsonNode poll = awaitResult(user, goodsId, CONSUME_TIMEOUT);
    assertThat(poll.get("code").asInt()).as(poll.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(poll.get("data").get("status").asText()).isEqualTo("SUCCESS");
    long orderId = poll.get("data").get("orderId").asLong();
    assertThat(orderId).isPositive();

    // 轮询到的订单号与 DB 事实一致
    Map<String, Object> order =
        jdbc.queryForMap(
            "SELECT order_id FROM miaosha_order WHERE user_id=? AND goods_id=?", user, goodsId);
    assertThat(orderId).isEqualTo(((Number) order.get("order_id")).longValue());
  }

  @Test // result=PROCESSING → 排队中，无订单号
  void processingState_returnsQueuing() {
    long user = insertUser(13000000201L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);
    redisTemplate.opsForValue().set(RedisKeyBuilder.result(goodsId, user), "PROCESSING");

    JsonNode poll = pollResult(user, goodsId);
    assertThat(poll.get("code").asInt()).isEqualTo(CODE_SUCCESS);
    assertThat(poll.get("data").get("status").asText()).isEqualTo("PROCESSING");
    assertThat(poll.get("data").get("orderId").isNull()).isTrue();
  }

  @Test // result=FAILED → 失败态
  void failedState_returnsFailed() {
    long user = insertUser(13000000202L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);
    redisTemplate.opsForValue().set(RedisKeyBuilder.result(goodsId, user), "FAILED");

    JsonNode poll = pollResult(user, goodsId);
    assertThat(poll.get("code").asInt()).isEqualTo(CODE_SUCCESS);
    assertThat(poll.get("data").get("status").asText()).isEqualTo("FAILED");
    assertThat(poll.get("data").get("orderId").isNull()).isTrue();
  }

  @Test // 未参与过该商品秒杀 → 无记录态，不报错
  void neverParticipated_returnsNone() {
    long user = insertUser(13000000203L);
    long goodsId = insertGoods("iphoneX", 9);

    JsonNode poll = pollResult(user, goodsId);
    assertThat(poll.get("code").asInt()).as(poll.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(poll.get("data").get("status").asText()).isEqualTo("NONE");
    assertThat(poll.get("data").get("orderId").isNull()).isTrue();
  }

  @Test // Redis 结果丢失但 DB 已有订单 → 兜底返回成功态与订单号
  void redisResultLost_fallsBackToDbOrder() {
    long user = insertUser(13000000204L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);

    doMiaosha(user, goodsId);
    JsonNode polled = awaitResult(user, goodsId, CONSUME_TIMEOUT);
    long orderId = polled.get("data").get("orderId").asLong();

    // 模拟 Redis 结果 key 丢失（过期 / 淘汰）
    redisTemplate.delete(RedisKeyBuilder.result(goodsId, user));

    JsonNode poll = pollResult(user, goodsId);
    assertThat(poll.get("code").asInt()).isEqualTo(CODE_SUCCESS);
    assertThat(poll.get("data").get("status").asText()).isEqualTo("SUCCESS");
    assertThat(poll.get("data").get("orderId").asLong()).isEqualTo(orderId);
  }

  @Test // 契约：result 是读操作，未携带 JWT 一律 401
  void pollResult_requiresJwt() {
    long goodsId = insertGoods("iphoneX", 9);
    assertThat(get("/miaosha/result?goodsId=" + goodsId, null).getStatusCode().value())
        .isEqualTo(401);
  }

  @Test // 兜底只认本人订单：他人订单不串结果
  void fallbackDoesNotLeakOtherUsersOrder() {
    long participant = insertUser(13000000205L);
    long bystander = insertUser(13000000206L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, participant);
    assertThat(doMiaosha(participant, goodsId).get("code").asInt()).isEqualTo(CODE_SUCCESS);
    awaitResult(participant, goodsId, CONSUME_TIMEOUT);

    Map<String, Object> order =
        jdbc.queryForMap(
            "SELECT order_id FROM miaosha_order WHERE user_id=? AND goods_id=?",
            participant, goodsId);
    assertThat(order).isNotNull();

    JsonNode poll = pollResult(bystander, goodsId);
    assertThat(poll.get("data").get("status").asText()).isEqualTo("NONE");
  }
}
