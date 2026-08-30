package com.example.seckill.cache;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试内存假适配器：与 scripts/miaosha_try.lua、miaosha_compensate.lua 语义对齐
 * （不模拟过期时间），让受理编排的降级与补偿路径可以脱离 Redis 直测。
 *
 * <p>额外提供故障注入与状态观测，供断言使用。
 */
public class InMemoryMiaoshaRedisStore implements MiaoshaRedisStore {

  private final Map<String, String> data = new HashMap<>();

  private RuntimeException tryFailure;

  /** 注入后每次 {@link #tryMiaosha} 都抛出该异常，模拟 Redis 不可用。 */
  public void failTryWith(RuntimeException e) {
    this.tryFailure = e;
  }

  /** 预置用户抢购标记（模拟该用户已在处理中 / 已成功）。 */
  public void seedUserMark(Long goodsId, Long userId, String requestId) {
    data.put(RedisKeyBuilder.user(goodsId, userId), requestId);
  }

  @Override
  public TryResult tryMiaosha(Long goodsId, Long userId, String requestId) {
    if (tryFailure != null) {
      throw tryFailure;
    }
    String userKey = RedisKeyBuilder.user(goodsId, userId);
    if (data.containsKey(userKey)) {
      return TryResult.REPEAT;
    }
    String stockKey = RedisKeyBuilder.stock(goodsId);
    String stock = data.get(stockKey);
    if (stock == null || Integer.parseInt(stock) <= 0) {
      return TryResult.STOCK_EMPTY;
    }
    data.put(stockKey, String.valueOf(Integer.parseInt(stock) - 1));
    data.put(userKey, requestId);
    data.put(RedisKeyBuilder.result(goodsId, userId), "PROCESSING");
    return TryResult.OK;
  }

  @Override
  public void markSuccess(Long goodsId, Long userId, Long orderId) {
    data.put(RedisKeyBuilder.result(goodsId, userId), "SUCCESS:" + orderId);
  }

  @Override
  public void compensate(Long goodsId, Long userId, String requestId) {
    String userKey = RedisKeyBuilder.user(goodsId, userId);
    if (!requestId.equals(data.get(userKey))) {
      return;
    }
    String stockKey = RedisKeyBuilder.stock(goodsId);
    String stock = data.get(stockKey);
    data.put(stockKey, stock == null ? "1" : String.valueOf(Integer.parseInt(stock) + 1));
    data.remove(userKey);
    data.put(RedisKeyBuilder.result(goodsId, userId), "FAILED");
  }

  @Override
  public void setStock(Long goodsId, int stock, Duration ttl) {
    data.put(RedisKeyBuilder.stock(goodsId), String.valueOf(stock));
  }

  @Override
  public String getResult(Long goodsId, Long userId) {
    return data.get(RedisKeyBuilder.result(goodsId, userId));
  }

  // ---------- 状态观测（断言用） ----------

  /** 当前库存；未写入（未预热）返回 -1。 */
  public int stock(Long goodsId) {
    String v = data.get(RedisKeyBuilder.stock(goodsId));
    return v == null ? -1 : Integer.parseInt(v);
  }

  public String userMark(Long goodsId, Long userId) {
    return data.get(RedisKeyBuilder.user(goodsId, userId));
  }

  public String result(Long goodsId, Long userId) {
    return data.get(RedisKeyBuilder.result(goodsId, userId));
  }
}
