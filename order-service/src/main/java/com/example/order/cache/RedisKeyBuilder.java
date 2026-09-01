package com.example.order.cache;

/**
 * 秒杀 Redis Key 统一管理（复制 miaosha-service 规则，双端必须保持一致）。
 *
 * <pre>
 *   miaosha:stock:{goodsId}            秒杀库存（预扣减/补偿回补，值归 miaosha-service 维护）
 *   miaosha:user:{goodsId}:{userId}    用户已抢购标记，value = requestId
 *   miaosha:result:{goodsId}:{userId}  用户秒杀结果：PROCESSING / SUCCESS:orderId / FAILED
 * </pre>
 */
public final class RedisKeyBuilder {

  private static final String PREFIX = "miaosha";

  private RedisKeyBuilder() {}

  public static String stock(Long goodsId) {
    return PREFIX + ":stock:" + goodsId;
  }

  public static String user(Long goodsId, Long userId) {
    return PREFIX + ":user:" + goodsId + ":" + userId;
  }

  public static String result(Long goodsId, Long userId) {
    return PREFIX + ":result:" + goodsId + ":" + userId;
  }
}
