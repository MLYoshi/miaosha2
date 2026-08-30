package com.example.seckill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.seckill.cache.InMemoryMiaoshaRedisStore;
import com.example.seckill.dao.MiaoshaOrderMapper;
import com.example.seckill.domain.MiaoshaOrder;
import com.example.seckill.vo.MiaoshaResultVo;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 秒杀结果查询直连单测：内存假 Redis 适配器 + mock 订单 Mapper，不起 Spring / HTTP / Redis。
 *
 * <p>覆盖四态映射（排队中 / 成功 / 失败 / 无记录）与 Redis 结果丢失时的 DB 兜底。
 */
class MiaoshaResultServiceTest {

  private static final Long USER = 1L;
  private static final Long GOODS = 100L;
  private static final Duration TTL = Duration.ofHours(1);

  private final InMemoryMiaoshaRedisStore store = new InMemoryMiaoshaRedisStore();
  private final MiaoshaOrderMapper miaoshaOrderMapper = mock(MiaoshaOrderMapper.class);
  private final MiaoshaResultService service =
      new MiaoshaResultService(store, miaoshaOrderMapper);

  private static MiaoshaOrder dbOrder(long orderId) {
    MiaoshaOrder order = new MiaoshaOrder();
    order.setUserId(USER);
    order.setGoodsId(GOODS);
    order.setOrderId(orderId);
    return order;
  }

  // ---------- 四态映射 ----------

  @Test // result=PROCESSING → 排队中，不触碰 DB
  void processing_returnsQueuing() {
    store.setStock(GOODS, 5, TTL);
    store.tryMiaosha(GOODS, USER, "req-1");

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.PROCESSING);
    assertThat(vo.getOrderId()).isNull();
    verify(miaoshaOrderMapper, never()).getByUserIdAndGoodsId(USER, GOODS);
  }

  @Test // result=SUCCESS:{orderId} → 成功，携带订单号，不触碰 DB
  void success_returnsOrderId() {
    store.setStock(GOODS, 5, TTL);
    store.tryMiaosha(GOODS, USER, "req-1");
    store.markSuccess(GOODS, USER, 7L);

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.SUCCESS);
    assertThat(vo.getOrderId()).isEqualTo(7L);
    verify(miaoshaOrderMapper, never()).getByUserIdAndGoodsId(USER, GOODS);
  }

  @Test // result=FAILED → 失败态，不触碰 DB
  void failed_returnsFailed() {
    store.setStock(GOODS, 5, TTL);
    store.tryMiaosha(GOODS, USER, "req-1");
    store.compensate(GOODS, USER, "req-1");

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.FAILED);
    assertThat(vo.getOrderId()).isNull();
    verify(miaoshaOrderMapper, never()).getByUserIdAndGoodsId(USER, GOODS);
  }

  // ---------- DB 兜底 ----------

  @Test // Redis 结果丢失（key 不存在）但 DB 已有订单 → 兜底返回成功态与订单号
  void redisKeyLost_dbHasOrder_returnsSuccessWithOrderId() {
    when(miaoshaOrderMapper.getByUserIdAndGoodsId(USER, GOODS)).thenReturn(dbOrder(9L));

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.SUCCESS);
    assertThat(vo.getOrderId()).isEqualTo(9L);
  }

  @Test // 未参与过（Redis 无记录且 DB 无订单）→ 无记录态，不报错
  void neverParticipated_returnsNone() {
    when(miaoshaOrderMapper.getByUserIdAndGoodsId(USER, GOODS)).thenReturn(null);

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.NONE);
    assertThat(vo.getOrderId()).isNull();
  }
}
