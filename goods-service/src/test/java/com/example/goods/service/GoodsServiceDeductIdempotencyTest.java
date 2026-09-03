package com.example.goods.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.goods.dao.GoodsMapper;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 扣减短期幂等单测（review report Issue 1：扣减结果二义性 × Kafka 自动重试 → 重复扣库存）：
 *
 * <ul>
 *   <li>同一 requestId 首次扣减：执行条件 UPDATE，影响行数写入 Redis（TTL 60s）</li>
 *   <li>同一 requestId 重放（响应丢失后 Kafka 重试）：命中缓存直接返回上次影响行数，
 *       不再执行条件 UPDATE —— 1 个订单 ↔ 1 次 DB 扣减</li>
 *   <li>requestId 为 null（同步降级路径）：单次尝试无重放，直接扣减、不触碰 Redis</li>
 *   <li>Redis 不可用：幂等缓存尽力而为，降级为直接扣减（不得阻断扣减主路径）</li>
 * </ul>
 */
class GoodsServiceDeductIdempotencyTest {

  private static final String KEY = "goods:deduct:req:req-1";

  private GoodsMapper goodsMapper;
  private ValueOperations<String, String> valueOps;
  private GoodsService goodsService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    goodsMapper = mock(GoodsMapper.class);
    MiaoshaWindowService windowService = mock(MiaoshaWindowService.class);
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    BDDMockito.given(redisTemplate.opsForValue()).willReturn(valueOps);
    goodsService =
        new GoodsService(goodsMapper, windowService, redisTemplate, mock(Clock.class));
  }

  @Test // 首次扣减：条件 UPDATE + 缓存影响行数
  void firstDeduct_updatesDbAndCachesRows() {
    BDDMockito.given(valueOps.get(KEY)).willReturn(null);
    BDDMockito.given(goodsMapper.reduceStock(2L)).willReturn(1);

    int rows = goodsService.deductStock(2L, "req-1");

    assertThat(rows).isEqualTo(1);
    verify(goodsMapper).reduceStock(2L);
    verify(valueOps).set(KEY, "1", Duration.ofSeconds(60));
  }

  @Test // 重放命中幂等：返回缓存行数，不再触碰 DB（防重复扣减的核心断言）
  void replayWithSameRequestId_returnsCachedRowsWithoutDbUpdate() {
    BDDMockito.given(valueOps.get(KEY)).willReturn("1");

    int rows = goodsService.deductStock(2L, "req-1");

    assertThat(rows).as("重放必须返回上次影响行数").isEqualTo(1);
    verify(goodsMapper, never()).reduceStock(2L);
  }

  @Test // 库存不足的结果同样被缓存：重放返回 0，不会二次尝试扣减
  void replayWithStockEmptyResult_returnsCachedZero() {
    BDDMockito.given(valueOps.get(KEY)).willReturn("0");
    BDDMockito.given(goodsMapper.reduceStock(2L)).willReturn(1);

    int rows = goodsService.deductStock(2L, "req-1");

    assertThat(rows).isZero();
    verify(goodsMapper, never()).reduceStock(2L);
  }

  @Test // 同步降级路径（requestId=null）：直接扣减，不触碰 Redis
  void nullRequestId_deductsWithoutIdempotency() {
    BDDMockito.given(goodsMapper.reduceStock(2L)).willReturn(1);

    int rows = goodsService.deductStock(2L, null);

    assertThat(rows).isEqualTo(1);
    verify(goodsMapper).reduceStock(2L);
    verify(valueOps, never()).get(anyString());
  }

  @Test // Redis 不可用：幂等缓存尽力而为，降级为直接扣减（不阻断主路径）
  void redisDown_degradesToPlainDeduct() {
    BDDMockito.given(valueOps.get(KEY)).willThrow(new QueryTimeoutException("redis timeout"));
    BDDMockito.given(goodsMapper.reduceStock(2L)).willReturn(1);

    int rows = goodsService.deductStock(2L, "req-1");

    assertThat(rows).as("Redis 故障不得阻断扣减主路径").isEqualTo(1);
    verify(goodsMapper).reduceStock(2L);
  }
}
