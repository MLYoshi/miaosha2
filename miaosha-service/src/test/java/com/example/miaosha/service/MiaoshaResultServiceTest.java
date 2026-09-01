package com.example.miaosha.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.miaosha.support.InMemoryMiaoshaRedisStore;
import com.example.miaosha.vo.MiaoshaResultVo;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 秒杀结果查询直连单测：内存假 Redis 适配器，不起 Spring / HTTP / Redis。
 *
 * <p>覆盖 Redis 结果契约四态映射（排队中 / 成功 / 失败 / 无记录）。
 * 单体中的 DB 兜底不随本步骤迁移（miaosha-service 不得访问 order 表），
 * 该兜底由 Step 5 order-service 恢复，此处不再有对应用例。
 */
class MiaoshaResultServiceTest {

  private static final Long USER = 1L;
  private static final Long GOODS = 100L;
  private static final Duration TTL = Duration.ofHours(1);

  private final InMemoryMiaoshaRedisStore store = new InMemoryMiaoshaRedisStore();
  private final MiaoshaResultService service = new MiaoshaResultService(store);

  // ---------- 四态映射 ----------

  @Test // result=PROCESSING → 排队中
  void processing_returnsQueuing() {
    store.setStock(GOODS, 5, TTL);
    store.tryMiaosha(GOODS, USER, "req-1");

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.PROCESSING);
    assertThat(vo.getOrderId()).isNull();
  }

  @Test // result=SUCCESS:{orderId} → 成功，携带订单号
  void success_returnsOrderId() {
    store.setStock(GOODS, 5, TTL);
    store.tryMiaosha(GOODS, USER, "req-1");
    // SUCCESS 回写归 order-service（根 AGENTS.md 规则 3），此处用 seedResult 造状态
    store.seedResult(GOODS, USER, "SUCCESS:7");

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.SUCCESS);
    assertThat(vo.getOrderId()).isEqualTo(7L);
  }

  @Test // result=FAILED → 失败态
  void failed_returnsFailed() {
    store.setStock(GOODS, 5, TTL);
    store.tryMiaosha(GOODS, USER, "req-1");
    store.compensate(GOODS, USER, "req-1");

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.FAILED);
    assertThat(vo.getOrderId()).isNull();
  }

  @Test // 未参与过（Redis 无记录）→ 无记录态，不报错（DB 兜底由 Step 5 order-service 恢复）
  void neverParticipated_returnsNone() {
    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.NONE);
    assertThat(vo.getOrderId()).isNull();
  }

  @Test // 未知结果值 → 按无记录处理（与单体一致）
  void unknownResultValue_returnsNone() {
    store.seedResult(GOODS, USER, "WHAT:IS:THIS");

    MiaoshaResultVo vo = service.query(USER, GOODS);

    assertThat(vo.getStatus()).isEqualTo(MiaoshaResultVo.Status.NONE);
    assertThat(vo.getOrderId()).isNull();
  }
}
