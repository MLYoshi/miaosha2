package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.order.cache.OrderResultStore;
import com.example.order.domain.OrderInfo;
import com.example.order.message.SeckillOrderMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;

/**
 * 消费编排单测（场景 1/2/4/6 的编排层覆盖）：假 {@link OrderResultStore} + mock
 * {@link OrderService}，不依赖容器，验证 fulfill 的编排语义与 ack 归属：
 *
 * <ul>
 *   <li>落库成功 → markSuccess，正常返回（ack）</li>
 *   <li>已 SUCCESS 的重复投递 → 快跳，不触碰 DB</li>
 *   <li>业务失败 → compensate 后正常返回（ack，不重试）</li>
 *   <li>迟到重复（业务失败但 result 已 SUCCESS）→ 跳过补偿</li>
 *   <li>意外异常 → 原样上抛（不 ack，交给容器重试/DLT）</li>
 * </ul>
 */
class OrderFulfillmentServiceTest {

  private static final long USER_ID = 1L;
  private static final long GOODS_ID = 2L;

  private OrderService orderService;
  private RecordingResultStore store;
  private OrderFulfillmentService fulfillment;

  @BeforeEach
  void setUp() {
    orderService = mock(OrderService.class);
    store = new RecordingResultStore();
    fulfillment = new OrderFulfillmentService(orderService, store);
  }

  @Test // 场景 1（编排层）：落库成功 → markSuccess(goodsId, userId, orderId)
  void fulfill_success_marksResult() {
    OrderInfo order = new OrderInfo();
    order.setId(7L);
    BDDMockito.given(orderService.createOrder(USER_ID, GOODS_ID, "r1")).willReturn(order);

    fulfillment.fulfill(new SeckillOrderMessage(USER_ID, GOODS_ID, "r1"));

    assertThat(store.successMarks).containsExactly("goods=2:user=1->orderId=7");
    assertThat(store.compensations).isEmpty();
  }

  @Test // 场景 2（编排层）：已有 SUCCESS 结果的重复投递快跳，不触碰 DB
  void fulfill_alreadySucceeded_fastSkipsWithoutDbAccess() {
    store.results.put(resultKey(), "SUCCESS:99");

    fulfillment.fulfill(new SeckillOrderMessage(USER_ID, GOODS_ID, "r1"));

    verifyNoInteractions(orderService);
    assertThat(store.compensations).isEmpty();
  }

  @Test // 场景 4（编排层）：业务失败 → compensate(requestId) 后正常返回（ack 不重试）
  void fulfill_businessFailure_compensatesAndReturns() {
    BDDMockito.given(orderService.createOrder(USER_ID, GOODS_ID, "r-biz"))
        .willThrow(new MiaoshaException(CodeMsg.MIAOSHA_STOCK_EMPTY));

    fulfillment.fulfill(new SeckillOrderMessage(USER_ID, GOODS_ID, "r-biz"));

    assertThat(store.compensations).containsExactly("goods=2:user=1:requestId=r-biz");
    assertThat(store.successMarks).isEmpty();
  }

  @Test // 场景 2 迟到重复：业务失败但 result 已 SUCCESS（降级同步先落库）→ 跳过补偿
  void fulfill_lateDuplicate_skipsCompensation() {
    store.results.put(resultKey(), "SUCCESS:99");
    BDDMockito.given(orderService.createOrder(USER_ID, GOODS_ID, "r-late"))
        .willThrow(new MiaoshaException(CodeMsg.MIAOSHA_REPEAT));

    fulfillment.fulfill(new SeckillOrderMessage(USER_ID, GOODS_ID, "r-late"));

    assertThat(store.compensations).isEmpty();
  }

  @Test // 场景 6（编排层）：意外异常原样上抛（不 ack），无任何 Redis 回写
  void fulfill_unexpectedException_rethrownWithoutWrites() {
    BDDMockito.given(orderService.createOrder(USER_ID, GOODS_ID, "r-err"))
        .willThrow(new IllegalStateException("模拟 goods-service 连接异常"));

    assertThatThrownBy(
            () -> fulfillment.fulfill(new SeckillOrderMessage(USER_ID, GOODS_ID, "r-err")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("模拟 goods-service 连接异常");

    assertThat(store.successMarks).isEmpty();
    assertThat(store.compensations).isEmpty();
  }

  private static String resultKey() {
    return GOODS_ID + ":" + USER_ID;
  }

  /** 记录型假实现：契约要求三个方法均不得抛异常。 */
  private static class RecordingResultStore implements OrderResultStore {

    final Map<String, String> results = new HashMap<>();
    final List<String> successMarks = new ArrayList<>();
    final List<String> compensations = new ArrayList<>();

    @Override
    public void markSuccess(Long goodsId, Long userId, Long orderId) {
      successMarks.add("goods=" + goodsId + ":user=" + userId + "->orderId=" + orderId);
      results.put(goodsId + ":" + userId, "SUCCESS:" + orderId);
    }

    @Override
    public void compensate(Long goodsId, Long userId, String requestId) {
      compensations.add("goods=" + goodsId + ":user=" + userId + ":requestId=" + requestId);
      results.put(goodsId + ":" + userId, "FAILED");
    }

    @Override
    public String getResult(Long goodsId, Long userId) {
      return results.get(goodsId + ":" + userId);
    }
  }
}
