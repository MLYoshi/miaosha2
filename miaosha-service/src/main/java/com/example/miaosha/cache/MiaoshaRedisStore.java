package com.example.miaosha.cache;

import java.time.Duration;

/**
 * 秒杀受理所需的 Redis 侧接缝。
 *
 * <p>接口只描述「秒杀受理」要问 Redis 的问题，不暴露任何存储细节（key、Lua、模板）。
 * 生产实现 {@link RedisMiaoshaStore} 走 Redis + Lua；测试用内存假适配器等价替换，
 * 编排模块因此可以脱离 Redis 直测降级与补偿路径。
 *
 * <p>异常契约：
 * <ul>
 *   <li>{@link #tryMiaosha} / {@link #getResult} 连接类异常向上抛，由调用方决定如何处理</li>
 *   <li>{@link #markSuccess} / {@link #compensate} 不得向调用方抛异常——
 *       回写失败不能破坏已完成的数据库事实</li>
 * </ul>
 */
public interface MiaoshaRedisStore {

  /** 预扣尝试的三种结局，语义与 scripts/miaosha_try.lua 一致。 */
  enum TryResult {
    /** 预扣成功，获得下单资格；DB 落库后须回写结果 */
    OK,
    /** 库存不足（含库存 key 不存在即未预热——先于 DB 商品校验拦截，F9 约定） */
    STOCK_EMPTY,
    /** 该用户已有抢购标记（处理中或已成功），重复下单 */
    REPEAT
  }

  /**
   * 原子预扣：重复检查 → 库存检查 → 扣减并标记 PROCESSING。
   *
   * @param requestId 本次受理请求的全链路标识，落库失败时凭它补偿校验
   */
  TryResult tryMiaosha(Long goodsId, Long userId, String requestId);

  /** DB 下单成功后回写结果 SUCCESS:{orderId}（尽力而为）。 */
  void markSuccess(Long goodsId, Long userId, Long orderId);

  /** DB 下单失败后补偿：仅当用户标记仍是本次 requestId 时回补库存、清标记、记 FAILED。 */
  void compensate(Long goodsId, Long userId, String requestId);

  /** 写入秒杀库存（预热用，带过期时间）。 */
  void setStock(Long goodsId, int stock, Duration ttl);

  /**
   * 读取用户秒杀结果（轮询用）。
   *
   * @return 原始结果值 {@code PROCESSING} / {@code SUCCESS:{orderId}} / {@code FAILED}；
   *         无记录（未参与 / 已过期）返回 {@code null}
   */
  String getResult(Long goodsId, Long userId);
}
