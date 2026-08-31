package com.example.miaosha.client;

/**
 * 同步下单降级接缝（本步骤唯一的新接缝，Step 5 明确的拆分点）。
 *
 * <p>受理编排只依赖本接口，不感知 HTTP 细节：生产实现 {@link HttpSyncOrderClient}
 * 走 order-service；测试用假实现等价替换（支持注入失败）。
 *
 * <p>幂等与兜底语义（DB 条件扣库存 + {@code miaosha_order} 唯一键）全部在
 * order-service 内实现，本接缝只传 userId/goodsId。
 */
public interface SyncOrderClient {

  /**
   * 同步下单降级（幂等，DB 条件扣库存 + 唯一键兜底在 order-service 内实现）。
   *
   * <p>Step 4：order-service 端点未就绪，失败上抛由 AcceptService 既有降级编排处理
   * （补偿 Redis 后向上抛出）；Step 5 端点接上后降级路径即恢复。
   *
   * @return 落库成功的订单号
   * @throws RuntimeException 下单失败（端点未就绪 / 业务码非 0 / 连接异常）时上抛
   */
  Long createOrder(Long userId, Long goodsId);
}
