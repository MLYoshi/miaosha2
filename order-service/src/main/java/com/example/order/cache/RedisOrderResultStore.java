package com.example.order.cache;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link OrderResultStore} 的生产实现：StringRedisTemplate + Lua，尽力而为。
 *
 * <p>与基线 {@code RedisMiaoshaStore} 的差异（有意取舍）：基线 {@code getResult}
 * 连接异常上抛；本实现按接缝契约吞异常返回 {@code null}——Redis 不可用时订单仍可落库，
 * 幂等由 DB 唯一键兜底（验收场景「Redis 不可用订单仍完成」）。
 */
@Component
public class RedisOrderResultStore implements OrderResultStore {

  private static final Logger log = LoggerFactory.getLogger(RedisOrderResultStore.class);

  /** 用户抢购标记 / 结果的默认过期时间（秒），1 天，对齐 miaosha-service。 */
  private static final long DEFAULT_TTL_SECONDS = 86400L;

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<Long> compensateScript;

  public RedisOrderResultStore(
      StringRedisTemplate redisTemplate, RedisScript<Long> miaoshaCompensateScript) {
    this.redisTemplate = redisTemplate;
    this.compensateScript = miaoshaCompensateScript;
  }

  @Override
  public void markSuccess(Long goodsId, Long userId, Long orderId) {
    try {
      redisTemplate
          .opsForValue()
          .set(
              RedisKeyBuilder.result(goodsId, userId),
              "SUCCESS:" + orderId,
              Duration.ofSeconds(DEFAULT_TTL_SECONDS));
    } catch (Exception e) {
      // 尽力而为：回写失败不能破坏已完成的数据库订单事实
      log.warn("markSuccess 失败 goodsId={} userId={}: {}", goodsId, userId, e.getMessage());
    }
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
      log.error("compensate 失败 goodsId={} userId={}: {}", goodsId, userId, e.getMessage(), e);
    }
  }

  @Override
  public String getResult(Long goodsId, Long userId) {
    try {
      return redisTemplate.opsForValue().get(RedisKeyBuilder.result(goodsId, userId));
    } catch (Exception e) {
      log.warn("getResult 失败 goodsId={} userId={}: {}", goodsId, userId, e.getMessage());
      return null;
    }
  }
}
