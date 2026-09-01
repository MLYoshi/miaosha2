package com.example.miaosha.cache;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 生产 Redis 适配器：Lua 原子预扣库存、失败补偿、库存写入。
 * SUCCESS 回写归 order-service（根 AGENTS.md 规则 3），本类不写 miaosha:result 的成功态。
 *
 * <p>Redis 是第一道库存拦截与防重复下单屏障，数据库仍是最终库存与订单事实来源。
 */
@Component
public class RedisMiaoshaStore implements MiaoshaRedisStore {

  private static final Logger log = LoggerFactory.getLogger(RedisMiaoshaStore.class);

  /** 用户抢购标记 / 结果的默认过期时间（秒），1 天 */
  private static final long DEFAULT_TTL_SECONDS = 86400L;

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<Long> tryScript;
  private final RedisScript<Long> compensateScript;

  public RedisMiaoshaStore(
      StringRedisTemplate redisTemplate,
      RedisScript<Long> miaoshaTryScript,
      RedisScript<Long> miaoshaCompensateScript) {
    this.redisTemplate = redisTemplate;
    this.tryScript = miaoshaTryScript;
    this.compensateScript = miaoshaCompensateScript;
  }

  @Override
  public TryResult tryMiaosha(Long goodsId, Long userId, String requestId) {
    List<String> keys =
        List.of(
            RedisKeyBuilder.stock(goodsId),
            RedisKeyBuilder.user(goodsId, userId),
            RedisKeyBuilder.result(goodsId, userId));
    Long ret =
        redisTemplate.execute(
            tryScript, keys, requestId, String.valueOf(DEFAULT_TTL_SECONDS));
    if (ret == null) {
      return TryResult.STOCK_EMPTY;
    }
    return switch (ret.intValue()) {
      case 0 -> TryResult.OK;
      case 2 -> TryResult.REPEAT;
      default -> TryResult.STOCK_EMPTY;
    };
  }

  @Override
  public void compensate(Long goodsId, Long userId, String requestId) {
    try {
      List<String> keys =
          List.of(
              RedisKeyBuilder.stock(goodsId),
              RedisKeyBuilder.user(goodsId, userId),
              RedisKeyBuilder.result(goodsId, userId));
      redisTemplate.execute(
          compensateScript, keys, requestId, String.valueOf(DEFAULT_TTL_SECONDS));
    } catch (Exception e) {
      log.error(
          "compensate 失败 goodsId={} userId={}: {}", goodsId, userId, e.getMessage(), e);
    }
  }

  @Override
  public void setStock(Long goodsId, int stock, Duration ttl) {
    redisTemplate
        .opsForValue()
        .set(RedisKeyBuilder.stock(goodsId), String.valueOf(stock), ttl);
  }

  @Override
  public String getResult(Long goodsId, Long userId) {
    return redisTemplate.opsForValue().get(RedisKeyBuilder.result(goodsId, userId));
  }
}
