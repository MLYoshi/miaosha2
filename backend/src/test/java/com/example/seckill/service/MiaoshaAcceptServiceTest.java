package com.example.seckill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.seckill.cache.InMemoryMiaoshaRedisStore;
import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.domain.OrderInfo;
import com.example.seckill.message.FakeOrderMessageSender;
import com.example.seckill.vo.MiaoshaAcceptVo;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 秒杀受理编排直连单测：内存假 Redis 适配器 + 假消息发送器 + mock 下单事务，
 * 不起 Spring / HTTP / Redis / Kafka。
 *
 * <p>受理语义（票 03）：预扣成功 → 发 Kafka → 立即返回受理中，DB 写入移出请求路径；
 * Kafka 发送失败降级同步落库（用户直接拿单）；Redis 不可用直连 DB 的既有降级保持不变。
 * F9 语义（未预热先返 500214，先于 DB 商品校验）在此单测化。
 */
class MiaoshaAcceptServiceTest {

  private static final Long USER = 1L;
  private static final Long GOODS = 100L;
  private static final Duration TTL = Duration.ofHours(1);

  private final InMemoryMiaoshaRedisStore store = new InMemoryMiaoshaRedisStore();
  private final FakeOrderMessageSender sender = new FakeOrderMessageSender();
  private final MiaoshaService miaoshaService = mock(MiaoshaService.class);
  private final MiaoshaAcceptService accept =
      new MiaoshaAcceptService(store, miaoshaService, sender);

  private static OrderInfo order(long id) {
    OrderInfo order = new OrderInfo();
    order.setId(id);
    order.setUserId(USER);
    order.setGoodsId(GOODS);
    return order;
  }

  // ---------- 预扣成功：受理异步化 ----------

  @Test // 预扣 OK + 发送成功 → 立即受理中：不落库，消息带全链路 requestId，result=PROCESSING
  void tryOkAndSendAccepted_processingWithoutDbWrite() {
    store.setStock(GOODS, 5, TTL);

    MiaoshaAcceptVo result = accept.execute(USER, GOODS);

    assertThat(result.getStatus()).as("受理成功应返回受理中").isEqualTo(MiaoshaAcceptVo.Status.PROCESSING);
    assertThat(result.getOrderId()).as("受理中不携带订单号").isNull();

    assertThat(sender.sent()).as("应发出一条下单消息").hasSize(1);
    assertThat(sender.sent().get(0).getUserId()).isEqualTo(USER);
    assertThat(sender.sent().get(0).getGoodsId()).isEqualTo(GOODS);
    assertThat(sender.sent().get(0).getRequestId())
        .as("消息 requestId 应与 Redis 抢购标记一致（补偿归属校验用）")
        .isEqualTo(store.userMark(GOODS, USER));

    verify(miaoshaService, never()).createOrder(any(), any());
    assertThat(store.stock(GOODS)).isEqualTo(4);
    assertThat(store.result(GOODS, USER)).isEqualTo("PROCESSING");
  }

  // ---------- 拦截分支（不触碰 DB，不发消息） ----------

  @Test // F9 语义：未预热（库存 key 不存在）→ 500214，先于 DB 商品校验
  void notPreheated_stockEmptyBeforeGoodsCheck() {
    assertThatThrownBy(() -> accept.execute(USER, GOODS))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg()).isEqualTo(CodeMsg.MIAOSHA_STOCK_EMPTY));
    verify(miaoshaService, never()).createOrder(any(), any());
    assertThat(sender.sent()).isEmpty();
    assertThat(store.userMark(GOODS, USER)).isNull();
  }

  @Test // 库存为 0 → 500214
  void stockDrained_stockEmpty() {
    store.setStock(GOODS, 0, TTL);

    assertThatThrownBy(() -> accept.execute(USER, GOODS))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg()).isEqualTo(CodeMsg.MIAOSHA_STOCK_EMPTY));
    verify(miaoshaService, never()).createOrder(any(), any());
    assertThat(sender.sent()).isEmpty();
  }

  @Test // 用户已有抢购标记 → 500212，库存不动
  void userAlreadyMarked_repeat() {
    store.setStock(GOODS, 5, TTL);
    store.seedUserMark(GOODS, USER, "other-request");

    assertThatThrownBy(() -> accept.execute(USER, GOODS))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg()).isEqualTo(CodeMsg.MIAOSHA_REPEAT));
    verify(miaoshaService, never()).createOrder(any(), any());
    assertThat(sender.sent()).isEmpty();
    assertThat(store.stock(GOODS)).isEqualTo(5);
  }

  // ---------- Redis 不可用降级（既有行为） ----------

  @Test // Redis 异常 → 降级直连 DB：同步落库直接拿单，不触碰 Redis、不发消息
  void redisFailure_fallsBackToSyncDbOrder() {
    store.failTryWith(new RuntimeException("redis down"));
    when(miaoshaService.createOrder(USER, GOODS)).thenReturn(order(9L));

    MiaoshaAcceptVo result = accept.execute(USER, GOODS);

    assertThat(result.getStatus()).isEqualTo(MiaoshaAcceptVo.Status.SUCCESS);
    assertThat(result.getOrderId()).isEqualTo(9L);
    assertThat(sender.sent()).isEmpty();
    assertThat(store.stock(GOODS)).as("降级不触碰 Redis 库存").isEqualTo(-1);
    assertThat(store.userMark(GOODS, USER)).isNull();
    assertThat(store.result(GOODS, USER)).isNull();
  }

  @Test // 降级后 DB 也失败 → 业务异常原样上抛，无补偿副作用
  void redisFailureAndDbFails_exceptionPropagates() {
    store.failTryWith(new RuntimeException("redis down"));
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new MiaoshaException(CodeMsg.MIAOSHA_STOCK_EMPTY));

    assertThatThrownBy(() -> accept.execute(USER, GOODS))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg()).isEqualTo(CodeMsg.MIAOSHA_STOCK_EMPTY));
    verify(miaoshaService).createOrder(USER, GOODS);
  }

  // ---------- Kafka 发送失败降级 ----------

  @Test // 发送失败 → 降级同步落库：用户直接拿单，回写 SUCCESS
  void sendFails_degradesToSyncOrder() {
    store.setStock(GOODS, 5, TTL);
    sender.failSendWith(new RuntimeException("kafka down"));
    when(miaoshaService.createOrder(USER, GOODS)).thenReturn(order(7L));

    MiaoshaAcceptVo result = accept.execute(USER, GOODS);

    assertThat(result.getStatus()).as("降级落库成功应直接返回成功态").isEqualTo(MiaoshaAcceptVo.Status.SUCCESS);
    assertThat(result.getOrderId()).isEqualTo(7L);
    assertThat(store.stock(GOODS)).isEqualTo(4);
    assertThat(store.result(GOODS, USER)).isEqualTo("SUCCESS:7");
  }

  @Test // 发送失败 + DB 也失败 → 补偿 Redis（库存回补、标记清除、result=FAILED），异常上抛
  void sendFailsAndDbFails_compensates() {
    store.setStock(GOODS, 5, TTL);
    sender.failSendWith(new RuntimeException("kafka down"));
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new MiaoshaException(CodeMsg.MIAOSHA_REPEAT));

    assertThatThrownBy(() -> accept.execute(USER, GOODS))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg()).isEqualTo(CodeMsg.MIAOSHA_REPEAT));

    assertThat(store.stock(GOODS)).as("库存应回补").isEqualTo(5);
    assertThat(store.userMark(GOODS, USER)).as("user 标记应清除").isNull();
    assertThat(store.result(GOODS, USER)).isEqualTo("FAILED");
  }

  @Test // 幂等重试：降级落库持续被 DB 唯一记录拦下 → 反复补偿，库存不泄漏
  void retryAfterSendFailDegrade_doesNotLeakStock() {
    store.setStock(GOODS, 5, TTL);
    sender.failSendWith(new RuntimeException("kafka down"));
    when(miaoshaService.createOrder(USER, GOODS))
        .thenThrow(new MiaoshaException(CodeMsg.MIAOSHA_REPEAT));

    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> accept.execute(USER, GOODS)).isInstanceOf(MiaoshaException.class);
    }

    assertThat(store.stock(GOODS)).as("反复重试不泄漏库存").isEqualTo(5);
  }
}
