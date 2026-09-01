package com.example.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.MiaoshaException;
import com.example.common.Result;
import com.example.order.support.AbstractOrderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue 3（step5 审查报告）：同步降级成功后未回写 result Key，迟到重复守卫
 * 依赖 miaosha-service 越权写 Key。
 *
 * <p>修复目标（TDD，测试先行）：{@code InternalOrderController#sync} 下单成功后
 * 由 order-service 自己调用 {@code OrderResultStore#markSuccess} 回写
 * {@code SUCCESS:{orderId}}（result Key 归属 order-service，根 AGENTS.md 规则 3），
 * 使 {@code OrderFulfillmentService} 的「迟到重复消息跳过补偿」守卫自洽：
 *
 * <ol>
 *   <li>sync 成功 → result Key = {@code SUCCESS:{orderId}}，响应 data = orderId</li>
 *   <li>sync 后迟到的重复 Kafka 消息（业务失败 MIAOSHA_REPEAT）→ 被 SUCCESS 快跳拦下：
 *       不补偿（库存不回补）、result 不被 FAILED 覆盖</li>
 *   <li>sync 业务失败（如库存不足）→ 异常上抛，不回写任何 result</li>
 * </ol>
 *
 * <p>goods-service 以 {@code @MockBean} 打桩（基座既有约定）；Redis/MySQL/Kafka 为真实容器。
 */
class SyncFallbackResultKeyTest extends AbstractOrderIntegrationTest {

  /** 每用例独立 goodsId/userId，与基座的 goodsId 过滤隔离策略对齐。 */
  private static final long GOODS_SYNC = 77001L;
  private static final long USER_SYNC = 77001L;

  private static final long GOODS_LATE = 77002L;
  private static final long USER_LATE = 77002L;

  private static final long GOODS_BIZ_FAIL = 77003L;
  private static final long USER_BIZ_FAIL = 77003L;

  @Autowired private InternalOrderController controller;

  @Test // Issue 3 断言 1：sync 成功 → order-service 自己回写 result=SUCCESS:{orderId}
  void sync_success_writesResultKeyWithOrderId() {
    stubGoodsClient(snapshot(GOODS_SYNC), 5);

    Result<Long> resp =
        controller.sync(new InternalOrderController.SyncOrderRequest(USER_SYNC, GOODS_SYNC));

    assertThat(resp.getCode()).as("同步下单响应码应为 0").isEqualTo(Result.SUCCESS_CODE);
    Long orderId = orderIdOf(USER_SYNC, GOODS_SYNC);
    assertThat(resp.getData()).as("响应 data 应为落库订单号").isEqualTo(orderId);
    assertThat(redisResult(GOODS_SYNC, USER_SYNC))
        .as("result Key 应由 order-service 回写为 SUCCESS:{orderId}")
        .isEqualTo("SUCCESS:" + orderId);
  }

  @Test // Issue 3 核心场景：sync 后迟到的重复消息被 SUCCESS 快跳拦下，不误补偿
  void lateDuplicateMessage_afterSyncSkippedByResultKey() {
    stubGoodsClient(snapshot(GOODS_LATE), 5);

    // 1) 同步降级先成功落库并回写 result
    controller.sync(new InternalOrderController.SyncOrderRequest(USER_LATE, GOODS_LATE));
    Long orderId = orderIdOf(USER_LATE, GOODS_LATE);
    assertThat(redisResult(GOODS_LATE, USER_LATE)).isEqualTo("SUCCESS:" + orderId);

    // 2) 迟到的重复 Kafka 消息（不同 requestId）：DB 唯一键兜底 + SUCCESS 快跳跳过补偿
    sendOrderMessage(USER_LATE, GOODS_LATE, "late-dup-req");
    try {
      Thread.sleep(5000); // 与 RedisDownIntegrationTest 相同：等待消费完成（无可观测状态变化）
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(countOrderInfo(GOODS_LATE)).as("重复消息不产生第二单").isEqualTo(1);
    assertThat(countMiaoshaOrder(GOODS_LATE)).isEqualTo(1);
    assertThat(deductCalls.get()).as("重复消息不重复扣库存").isEqualTo(1);
    assertThat(restoreCalls.get())
        .as("迟到重复消息必须走快跳，不得触发补偿（库存回补=误补偿）")
        .isZero();
    assertThat(redisResult(GOODS_LATE, USER_LATE))
        .as("已成功的 result 不得被补偿覆盖为 FAILED")
        .isEqualTo("SUCCESS:" + orderId);
  }

  @Test // Issue 3 边界：sync 业务失败（库存不足）不回写 result Key
  void sync_businessFailure_doesNotWriteResultKey() {
    stubGoodsClient(snapshot(GOODS_BIZ_FAIL), 0);

    // 无 Web 层：业务失败直接以异常形态上抛（部署时由 GlobalExceptionHandler 转 Result.error）
    assertThatThrownBy(
            () ->
                controller.sync(
                    new InternalOrderController.SyncOrderRequest(USER_BIZ_FAIL, GOODS_BIZ_FAIL)))
        .isInstanceOf(MiaoshaException.class);

    assertThat(countOrderInfo(GOODS_BIZ_FAIL)).as("业务失败不建单").isZero();
    assertThat(redisResult(GOODS_BIZ_FAIL, USER_BIZ_FAIL))
        .as("业务失败不得回写 result")
        .isNull();
  }
}
