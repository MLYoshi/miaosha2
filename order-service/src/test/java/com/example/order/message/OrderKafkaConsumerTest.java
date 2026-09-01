package com.example.order.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.example.order.cache.RedisKeyBuilder;
import com.example.order.support.AbstractOrderIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * Kafka 消费链路集成测试（真实 listener + 手动 ack + DefaultErrorHandler）：
 *
 * <ul>
 *   <li>场景 1 正常消费：消息 → 落库 → markSuccess，轮询语义可查 SUCCESS:{orderId}</li>
 *   <li>场景 2 重复消息：同一 (userId, goodsId) 二次投递 → 只有一条订单、只扣一次库存；
 *       已 SUCCESS 的重复投递快跳，完全不触碰 DB</li>
 *   <li>业务失败（库存不足）：补偿后 ack，不重试、不进死信</li>
 *   <li>场景 6 retry→DLT：意外异常 1s/2s/4s 重试 3 次（共 4 次消费）后进
 *       seckill-order-dlt，消息体原样保留，位点继续推进</li>
 * </ul>
 */
class OrderKafkaConsumerTest extends AbstractOrderIntegrationTest {

  private static final long USER_ID = 22001L;

  @Test // 场景 1：正常消费落库 + Redis 结果回写
  void consumeSuccess_createsOrderAndWritesRedisResult() {
    long goodsId = 92001L;
    stubGoodsClient(snapshot(goodsId), 10);

    sendOrderMessage(USER_ID, goodsId, "req-ok-1");

    awaitUntil("订单应落库", () -> countOrderInfo(goodsId) == 1, Duration.ofSeconds(60));
    long orderId = orderIdOf(USER_ID, goodsId);
    assertThat(redisResult(goodsId, USER_ID))
        .as("markSuccess 应回写 SUCCESS:{orderId}（轮询接口语义）")
        .isEqualTo("SUCCESS:" + orderId);
    assertThat(deductCalls.get()).isEqualTo(1);
    assertThat(restoreCalls.get()).isZero();
    assertThat(countOrphanOrders()).as("miaosha_order.order_id 必须对应真实订单").isZero();
  }

  @Test // 场景 2：重复消息只建一单、只扣一次库存
  void duplicateMessage_singleOrderSingleDeduct() {
    long goodsId = 92002L;
    stubGoodsClient(snapshot(goodsId), 10);

    sendOrderMessage(USER_ID, goodsId, "req-dup-1");
    awaitUntil("首条消息应落库", () -> countOrderInfo(goodsId) == 1, Duration.ofSeconds(60));
    long firstOrderId = orderIdOf(USER_ID, goodsId);

    // 同一 (userId, goodsId) 二次投递（不同 requestId，模拟生产侧重试/重复）
    sendOrderMessage(USER_ID, goodsId, "req-dup-2");
    try {
      Thread.sleep(4000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(countMiaoshaOrder(goodsId)).as("重复消息不得重复建单").isEqualTo(1);
    assertThat(deductCalls.get()).as("重复消息不得重复扣库存").isEqualTo(1);
    assertThat(redisResult(goodsId, USER_ID))
        .as("结果保持首次成功结果")
        .isEqualTo("SUCCESS:" + firstOrderId);
  }

  @Test // 场景 2（快跳）：已有 SUCCESS 结果的重复投递不触碰 DB
  void duplicateWithSuccessResult_fastSkipsWithoutDbAccess() {
    long goodsId = 92003L;
    stubGoodsClient(snapshot(goodsId), 10);
    redisTemplate
        .opsForValue()
        .set(RedisKeyBuilder.result(goodsId, USER_ID), "SUCCESS:999", Duration.ofHours(1));

    sendOrderMessage(USER_ID, goodsId, "req-skip-1");
    try {
      Thread.sleep(4000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(snapshotCalls.get()).as("快跳不应调用商品快照接口").isZero();
    assertThat(deductCalls.get()).as("快跳不应扣库存").isZero();
    assertThat(countOrderInfo(goodsId)).isZero();
  }

  @Test // 业务失败（库存不足）：补偿后 ack，不重试、不进死信、无订单
  void stockEmptyBusinessFailure_ackedWithoutRetryOrDlt() {
    long goodsId = 92004L;
    stubGoodsClient(snapshot(goodsId), 0);

    sendOrderMessage(USER_ID, goodsId, "req-empty-1");
    try {
      Thread.sleep(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(countOrderInfo(goodsId)).as("库存不足不建单").isZero();
    assertThat(restoreCalls.get()).as("库存不足是业务失败，不做库存回补").isZero();
    assertThat(awaitDltRecords(goodsId, 0, Duration.ofSeconds(2)))
        .as("业务失败 ack 不重试，不得进入死信")
        .isEmpty();
  }

  @Test // 场景 6：意外异常重试 1s/2s/4s 共 4 次消费后进死信，位点继续推进
  void unexpectedException_retriesExhausted_thenDeadLetter() {
    long goodsId = 92005L;
    stubGoodsClientThrowing(snapshot(goodsId), new IllegalStateException("模拟 goods-service 意外异常"));

    sendOrderMessage(USER_ID, goodsId, "req-dlt-1");

    awaitUntil("意外异常应重试 1+3=4 次后耗尽", () -> deductCalls.get() >= 4, Duration.ofSeconds(60));
    List<String> dlt = awaitDltRecords(goodsId, 1, Duration.ofSeconds(60));
    assertThat(dlt).as("重试耗尽应进入 seckill-order-dlt").hasSize(1);

    JsonNode payload = parse(dlt.get(0));
    assertThat(payload.get("userId").asLong()).isEqualTo(USER_ID);
    assertThat(payload.get("goodsId").asLong()).isEqualTo(goodsId);
    assertThat(payload.get("requestId").asText()).isEqualTo("req-dlt-1");

    assertThat(countOrderInfo(goodsId)).as("全程未成功扣减，不得留下订单").isZero();
    assertThat(restoreCalls.get()).isZero();
  }

  @Test // Issue 1 端到端（修复后）：扣减响应丢失 → 不 ack → Kafka 重试 → 同一 requestId 幂等命中 → 1 订单 1 扣减
  void deductAmbiguity_retryReplaysWithSameRequestId_deductsExactlyOnce() {
    long goodsId = 92006L;
    snapshotCalls = new AtomicInteger();
    deductCalls = new AtomicInteger();
    deductSuccessCalls = new AtomicInteger();
    restoreCalls = new AtomicInteger();
    given(goodsClient.getGoodsVo(any())).willReturn(snapshot(goodsId));
    AtomicInteger attempts = new AtomicInteger();
    // 模拟带短期幂等的 goods-service：重放携带同一 requestId → SETNX 命中，
    // 直接返回上次影响行数，不再真实扣减（deductCalls 不增加）。
    // 若修复未把消息 requestId 传到扣减请求（如传 null/新 requestId），此处退化为真实扣减 → 断言失败。
    Set<String> seenRequestIds = ConcurrentHashMap.newKeySet();
    given(goodsClient.deductStock(any(), any()))
        .willAnswer(
            inv -> {
              String requestId = inv.getArgument(1);
              if (requestId != null && !seenRequestIds.add(requestId)) {
                return 1;
              }
              deductCalls.incrementAndGet();
              if (attempts.getAndIncrement() == 0) {
                throw new ResourceAccessException("read timeout");
              }
              return 1;
            });

    sendOrderMessage(USER_ID, goodsId, "req-amb-1");

    awaitUntil("重放后订单应落库", () -> countOrderInfo(goodsId) == 1, Duration.ofSeconds(60));

    // Issue 1 修复：重放复用同一 requestId → 幂等命中，1 个订单恰好对应 1 次 DB 扣减
    assertThat(deductCalls.get())
        .as("扣减响应丢失 + 重放幂等 → 不再重复扣减")
        .isEqualTo(1);
    assertThat(countMiaoshaOrder(goodsId)).as("最终只有 1 个订单").isEqualTo(1);
    assertThat(restoreCalls.get()).as("二义性路径不回补（可能已扣，回补会超卖）").isZero();
    assertThat(redisResult(goodsId, USER_ID)).as("订单落库后回写 SUCCESS").startsWith("SUCCESS:");
  }

  private JsonNode parse(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      throw new AssertionError("消息不是合法 JSON: " + json, e);
    }
  }
}
