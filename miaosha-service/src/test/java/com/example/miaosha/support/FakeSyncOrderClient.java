package com.example.miaosha.support;

import com.example.miaosha.client.SyncOrderClient;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试假同步下单客户端：{@link SyncOrderClient} 接缝的内存实现，
 * 让受理编排的 Redis 不可用降级 / Kafka 失败降级路径脱离 order-service 直测。
 *
 * <p>提供成功返回值 / 失败注入与调用观测，供断言使用。
 */
public class FakeSyncOrderClient implements SyncOrderClient {

  private final List<long[]> calls = new ArrayList<>();

  private Long orderId;
  private RuntimeException failure;

  /** 配置成功返回的订单号（未配置时调用即失败，防静默通过）。 */
  public void succeedWith(Long orderId) {
    this.orderId = orderId;
  }

  /** 注入后每次 {@link #createOrder} 都抛出该异常，模拟 order-service 端点失败。 */
  public void failWith(RuntimeException e) {
    this.failure = e;
  }

  /** 已发生的调用次数（验证「未触碰降级接缝」用）。 */
  public int callCount() {
    return calls.size();
  }

  @Override
  public Long createOrder(Long userId, Long goodsId) {
    calls.add(new long[] {userId, goodsId});
    if (failure != null) {
      throw failure;
    }
    if (orderId == null) {
      throw new IllegalStateException("FakeSyncOrderClient 未配置成功返回值");
    }
    return orderId;
  }
}
