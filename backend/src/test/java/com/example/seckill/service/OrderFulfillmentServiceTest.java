package com.example.seckill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.seckill.cache.InMemoryMiaoshaRedisStore;
import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.domain.OrderInfo;
import com.example.seckill.message.SeckillOrderMessage;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 消费者落库编排直连单测：内存假 Redis + mock 下单事务，不起 Spring / Kafka。
 *
 * <p>覆盖三条路径：落库成功回写；业务失败（如 DB 已有记录）补偿后正常结束（listener ack，
 * 不重试业务失败）；降级同步落库已成功后迟到的重复消息被 DB 唯一键拦下，跳过补偿。
 * 意外异常上抛（票 04 加固重试与死信）。
 */
class OrderFulfillmentServiceTest {

  private static final Long USER = 1L;
  private static final Long GOODS = 100L;
  private static final String REQUEST_ID = "req-100";
  private static final Duration TTL = Duration.ofHours(1);

  private final InMemoryMiaoshaRedisStore store = new InMemoryMiaoshaRedisStore();
  private final MiaoshaService miaoshaService = mock(MiaoshaService.class);
  private final OrderFulfillmentService fulfillment =
      new OrderFulfillmentService(miaoshaService, store);

  private static SeckillOrderMessage message() {
    return new SeckillOrderMessage(USER, GOODS, REQUEST_ID);
  }

  private static OrderInfo order(long id) {
    OrderInfo order = new OrderInfo();
    order.setId(id);
    order.setUserId(USER);
    order.setGoodsId(GOODS);
    return order;
  }

  /** 预置「受理侧已预扣」的 Redis 状态（库存已扣、标记已写、result=PROCESSING）。 */
  private void seedPreDeducted(int stockAfterDeduct) {
    store.setStock(GOODS, stockAfterDeduct + 1, TTL);
    store.tryMiaosha(GOODS, USER, REQUEST_ID);
    assertThat(store.result(GOODS, USER)).isEqualTo("PROCESSING");
  }

  @Test // 落库成功 → Redis 回写 SUCCESS:{orderId}
  void dbSuccess_marksSuccess() {
    seedPreDeducted(4);
    when(miaoshaService.createOrder(USER, GOODS)).thenReturn(order(7L));

    fulfillment.fulfill(message());

    assertThat(store.stock(GOODS)).isEqualTo(4);
    assertThat(store.userMark(GOODS, USER)).isNotBlank();
    assertThat(store.result(GOODS, USER)).isEqualTo("SUCCESS:7");
  }

  @Test // 业务失败（DB 已有记录）→ 补偿：库存回补、标记清除、result=FAILED；正常返回由 listener ack
  void businessFailure_compensates() {
    seedPreDeducted(4);
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new MiaoshaException(CodeMsg.MIAOSHA_REPEAT));

    fulfillment.fulfill(message());

    assertThat(store.stock(GOODS)).as("库存应回补").isEqualTo(5);
    assertThat(store.userMark(GOODS, USER)).as("user 标记应清除").isNull();
    assertThat(store.result(GOODS, USER)).isEqualTo("FAILED");
  }

  @Test // 迟到的重复消息：降级同步落库已成功（result=SUCCESS）→ DB 唯一键拦下后跳过补偿，
        // 不覆盖成功结果、不回补库存（否则 Redis/DB 库存不一致）
  void lateDuplicateAfterSyncDegrade_skipsCompensation() {
    seedPreDeducted(4);
    // 模拟受理侧降级同步落库成功后的 Redis 状态
    store.markSuccess(GOODS, USER, 7L);
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new MiaoshaException(CodeMsg.MIAOSHA_REPEAT));

    fulfillment.fulfill(message());

    assertThat(store.stock(GOODS)).as("迟到重复消息不应回补库存").isEqualTo(4);
    assertThat(store.userMark(GOODS, USER)).as("标记保持").isEqualTo(REQUEST_ID);
    assertThat(store.result(GOODS, USER)).as("成功结果不应被覆盖").isEqualTo("SUCCESS:7");
  }

  @Test // 重复投递（票 04）：结果标记已 SUCCESS → 幂等快跳，不再触碰 DB；
        // 结果标记丢失的重复仍由 DB 唯一键兜底（见 businessFailure_compensates）
  void duplicateDelivery_alreadySucceeded_fastSkips() {
    seedPreDeducted(4);
    // 模拟首次投递已落库成功并回写结果
    store.markSuccess(GOODS, USER, 7L);
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new MiaoshaException(CodeMsg.MIAOSHA_REPEAT));

    fulfillment.fulfill(message()); // 同一条消息重复投递

    verify(miaoshaService, never()).createOrder(anyLong(), anyLong());
    assertThat(store.stock(GOODS)).as("快跳不得回补库存").isEqualTo(4);
    assertThat(store.userMark(GOODS, USER)).as("标记保持").isEqualTo(REQUEST_ID);
    assertThat(store.result(GOODS, USER)).as("成功结果不得被覆盖").isEqualTo("SUCCESS:7");
  }

  @Test // 意外异常（非业务失败）→ 上抛，listener 不 ack（票 04 加固重试与死信）
  void unexpectedException_propagates() {
    seedPreDeducted(4);
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new RuntimeException("db connection lost"));

    assertThatThrownBy(() -> fulfillment.fulfill(message()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("db connection lost");
    // 无任何 Redis 副作用：等待重试（票 04）
    assertThat(store.stock(GOODS)).isEqualTo(4);
    assertThat(store.result(GOODS, USER)).isEqualTo("PROCESSING");
  }
}
