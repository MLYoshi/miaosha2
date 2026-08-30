package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * F5-F10：秒杀主流程功能正确性（单线程）。
 *
 * <p>票 03 受理异步化后口径：预扣失败的拦截分支同步拒绝（F6/F9）；
 * 预扣成功的用例为「受理 → 等待消费完成 → 轮询/对账断言」（F5/F7/F8/F10），
 * 对账不变式不变（订单数、DB/Redis 库存一致、无重复下单）。
 */
class MiaoshaApiTest extends AbstractIntegrationTest {

  private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(20);

  @Test // F5 预热 → 受理 → 消费落库 → 轮询拿单，DB + Redis 双边对账
  void fullFlow_acceptThenPollOrder() {
    long user = insertUser(13000000100L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);
    assertThat(redisStock(goodsId)).isEqualTo(9);

    JsonNode resp = doMiaosha(user, goodsId);
    assertThat(resp.get("code").asInt()).as(resp.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(resp.get("data").get("status").asText()).as("受理应返回受理中").isEqualTo("PROCESSING");
    assertThat(resp.get("data").get("orderId").isNull()).as("受理中不携带订单号").isTrue();

    JsonNode result = awaitResult(user, goodsId, CONSUME_TIMEOUT);
    assertThat(result.get("data").get("status").asText())
        .as("消费完成后应轮询到成功态: %s", result)
        .isEqualTo("SUCCESS");
    long orderId = result.get("data").get("orderId").asLong();
    assertThat(orderId).isPositive();

    // DB 对账
    assertThat(dbStock(goodsId)).isEqualTo(8);
    assertThat(orderCount()).isEqualTo(1);
    assertThat(miaoshaOrderCount()).isEqualTo(1);
    Map<String, Object> order = jdbc.queryForMap("SELECT * FROM order_info WHERE id=?", orderId);
    assertThat(order.get("user_id")).isEqualTo(user);
    assertThat(order.get("goods_id")).isEqualTo(goodsId);
    assertThat(order.get("goods_name")).isEqualTo("iphoneX");
    assertThat(new BigDecimal(order.get("goods_price").toString()))
        .as("下单时秒杀价快照")
        .isEqualByComparingTo(new BigDecimal("0.01"));
    assertThat(order.get("goods_count")).isEqualTo(1);
    assertThat(order.get("order_channel")).isEqualTo(1);
    assertThat(order.get("status")).isEqualTo(0);

    // Redis 对账
    assertThat(redisStock(goodsId)).isEqualTo(8);
    assertThat(redisUserKey(goodsId, user)).isNotNull();
    assertThat(redisResult(goodsId, user)).isEqualTo("SUCCESS:" + orderId);
  }

  @Test // F6 未预热直接秒杀 → 500214（Lua stock key 缺失语义，同步拦截）
  void miaoshaWithoutPreheat_stockEmpty() {
    long user = insertUser(13000000101L);
    long goodsId = insertGoods("iphoneX", 9);

    JsonNode resp = doMiaosha(user, goodsId);
    assertThat(resp.get("code").asInt()).isEqualTo(CODE_MIAOSHA_STOCK_EMPTY);
    assertThat(dbStock(goodsId)).isEqualTo(9);
    assertThat(orderCount()).isZero();
    assertThat(miaoshaOrderCount()).isZero();
  }

  @Test // F7 时间窗外秒杀：受理放行，消费侧落库时被拒 → 补偿，轮询终态 FAILED
  void miaoshaOutsideTimeWindow_compensatedOnConsume() {
    long user = insertUser(13000000102L);

    long notStarted =
        insertGoods(
            "notStarted", 9, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2));
    preheat(notStarted, user);
    JsonNode r1 = doMiaosha(user, notStarted);
    assertThat(r1.get("code").asInt()).as("受理阶段预扣成功").isEqualTo(CODE_SUCCESS);

    JsonNode f1 = awaitResult(user, notStarted, CONSUME_TIMEOUT);
    assertThat(f1.get("data").get("status").asText())
        .as("活动未开始应在消费侧失败并补偿: %s", f1)
        .isEqualTo("FAILED");
    assertThat(orderCount()).as("不应产生订单").isZero();
    assertThat(dbStock(notStarted)).isEqualTo(9);
    assertThat(redisStock(notStarted)).as("库存应回补").isEqualTo(9);
    assertThat(redisUserKey(notStarted, user)).as("标记应清除").isNull();

    long ended =
        insertGoods("ended", 9, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1));
    preheat(ended, user);
    JsonNode r2 = doMiaosha(user, ended);
    assertThat(r2.get("code").asInt()).isEqualTo(CODE_SUCCESS);

    JsonNode f2 = awaitResult(user, ended, CONSUME_TIMEOUT);
    assertThat(f2.get("data").get("status").asText())
        .as("活动已结束应在消费侧失败并补偿: %s", f2)
        .isEqualTo("FAILED");
    assertThat(orderCount()).isZero();
    assertThat(dbStock(ended)).isEqualTo(9);
    assertThat(redisStock(ended)).isEqualTo(9);
  }

  @Test // F8 串行重复秒杀 → 第二次受理被 Redis 标记拦截 500212
  void repeatMiaosha_rejected() {
    long user = insertUser(13000000103L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);

    assertThat(doMiaosha(user, goodsId).get("code").asInt()).isEqualTo(CODE_SUCCESS);
    JsonNode second = doMiaosha(user, goodsId);
    assertThat(second.get("code").asInt()).isEqualTo(CODE_MIAOSHA_REPEAT);

    awaitResult(user, goodsId, CONSUME_TIMEOUT);
    assertThat(dbStock(goodsId)).isEqualTo(8);
    assertThat(orderCount()).isEqualTo(1);
    assertThat(miaoshaOrderCount()).isEqualTo(1);
    assertThat(duplicateMiaoshaOrderCount()).isZero();
  }

  @Test // F9 秒杀不存在的商品：未预热 → Lua 在 DB 商品校验之前返回 500214
  void miaoshaNonExistentGoods() {
    long user = insertUser(13000000104L);

    JsonNode resp = doMiaosha(user, 999999L);
    assertThat(resp.get("code").asInt()).isEqualTo(CODE_MIAOSHA_STOCK_EMPTY);
    assertThat(orderCount()).isZero();
    assertThat(miaoshaOrderCount()).isZero();
  }

  @Test // F10 Redis 放行但 DB 已有秒杀记录 → 消费侧业务失败补偿：库存回补、标记清除、result=FAILED，重试不泄漏库存
  void dbRepeatAfterRedisPrecheck_triggersCompensation() {
    long user = insertUser(13000000105L);
    long goodsId = insertGoods("iphoneX", 5);
    // 预埋 DB 秒杀记录（Redis 无 user 标记），制造 Redis↔DB 不一致
    jdbc.update(
        "INSERT INTO miaosha_order (user_id, order_id, goods_id) VALUES (?,?,?)",
        user, 0L, goodsId);
    preheat(goodsId, user);

    assertThat(doMiaosha(user, goodsId).get("code").asInt()).isEqualTo(CODE_SUCCESS);

    JsonNode result = awaitResult(user, goodsId, CONSUME_TIMEOUT);
    assertThat(result.get("data").get("status").asText())
        .as("消费侧被 DB 唯一键拦下应补偿为失败态: %s", result)
        .isEqualTo("FAILED");

    // 补偿对账
    assertThat(redisStock(goodsId)).as("库存应回补").isEqualTo(5);
    assertThat(redisUserKey(goodsId, user)).as("user 标记应清除").isNull();
    assertThat(redisResult(goodsId, user)).isEqualTo("FAILED");
    assertThat(dbStock(goodsId)).isEqualTo(5);
    assertThat(orderCount()).isZero();
    assertThat(miaoshaOrderCount()).isEqualTo(1);

    // 幂等重试：补偿后标记已清，重试重新预扣 → 再次被 DB 唯一键拦下 → 再次补偿，库存不泄漏
    assertThat(doMiaosha(user, goodsId).get("code").asInt()).isEqualTo(CODE_SUCCESS);
    JsonNode retry = awaitResult(user, goodsId, CONSUME_TIMEOUT);
    assertThat(retry.get("data").get("status").asText()).isEqualTo("FAILED");
    assertThat(redisStock(goodsId)).as("反复重试不泄漏库存").isEqualTo(5);
    assertThat(dbStock(goodsId)).isEqualTo(5);
    assertThat(miaoshaOrderCount()).isEqualTo(1);
  }

  @Test // 契约：do_miaosha 是写操作，仅接受 POST，GET 一律 405
  void doMiaosha_rejectsGet() {
    long user = insertUser(13000000106L);
    insertGoods("iphoneX", 9);

    assertThat(get("/miaosha/do_miaosha?goodsId=1", user).getStatusCode().value())
        .as("GET do_miaosha 应返回 405")
        .isEqualTo(405);
  }
}
