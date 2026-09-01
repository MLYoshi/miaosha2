package com.example.goods.service;

import com.example.goods.dao.GoodsMapper;
import com.example.goods.domain.Goods;
import com.example.goods.vo.GoodsDetailVo;
import com.example.goods.vo.GoodsVo;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GoodsService {

  private static final Logger log = LoggerFactory.getLogger(GoodsService.class);

  /** 扣减幂等缓存：TTL 60s 覆盖 Kafka 重试窗口（1s/2s/4s），过期后由 DB 唯一订单对账兜底。 */
  private static final Duration DEDUCT_IDEMPOTENT_TTL = Duration.ofSeconds(60);

  private static final String DEDUCT_REQUEST_KEY_PREFIX = "goods:deduct:req:";

  private final GoodsMapper goodsMapper;
  private final MiaoshaWindowService windowService;
  private final StringRedisTemplate redisTemplate;

  public GoodsService(
      GoodsMapper goodsMapper,
      MiaoshaWindowService windowService,
      StringRedisTemplate redisTemplate) {
    this.goodsMapper = goodsMapper;
    this.windowService = windowService;
    this.redisTemplate = redisTemplate;
  }

  public Goods getById(Long id) {
    return goodsMapper.getById(id);
  }

  public List<GoodsVo> listGoodsVo() {
    return goodsMapper.listGoodsVo();
  }

  public GoodsVo getGoodsVo(Long goodsId) {
    return goodsMapper.getGoodsVo(goodsId);
  }

  public GoodsDetailVo getGoodsDetail(Long goodsId) {
    GoodsVo goodsVo = goodsMapper.getGoodsVo(goodsId);
    if (goodsVo == null) {
      return null;
    }

    MiaoshaWindowService.WindowStatus status =
        windowService.resolveStatus(goodsVo.getStartDate(), goodsVo.getEndDate());
    return new GoodsDetailVo(goodsVo, status.status(), status.remainSeconds());
  }

  /**
   * 条件扣减秒杀库存，返回影响行数：1 成功 / 0 库存不足。
   * 防超卖语义由 SQL 条件更新保证（stock_count > 0）。
   *
   * <p>requestId 幂等（review report Issue 1：扣减结果二义性 × Kafka 自动重试 → 重复扣库存）：
   * order-service 扣减响应丢失后整条消息重放，同一 requestId 第二次到达时直接返回缓存的
   * 上次影响行数，不再执行条件 UPDATE——保证「1 个订单 ↔ 1 次 DB 扣减」。
   * 幂等缓存尽力而为：Redis 不可用时降级为直接扣减（宁少卖不超卖，方向与基线一致）。
   */
  public int deductStock(Long goodsId, String requestId) {
    if (requestId == null || requestId.isBlank()) {
      // 同步降级路径：单次尝试、无重放，无需幂等
      return goodsMapper.reduceStock(goodsId);
    }
    String key = DEDUCT_REQUEST_KEY_PREFIX + requestId;
    try {
      String cached = redisTemplate.opsForValue().get(key);
      if (cached != null) {
        int rows = Integer.parseInt(cached);
        log.info("deduct-stock 幂等命中 requestId={} goodsId={} rows={}", requestId, goodsId, rows);
        return rows;
      }
    } catch (DataAccessException e) {
      log.warn("deduct-stock 幂等缓存读取失败，降级直接扣减 requestId={}: {}", requestId, e.getMessage());
    }
    int rows = goodsMapper.reduceStock(goodsId);
    try {
      redisTemplate.opsForValue().set(key, String.valueOf(rows), DEDUCT_IDEMPOTENT_TTL);
    } catch (DataAccessException e) {
      log.warn("deduct-stock 幂等缓存写入失败 requestId={}: {}", requestId, e.getMessage());
    }
    return rows;
  }

  /**
   * 回补秒杀库存（Saga 补偿），无条件 stock_count + 1。
   * 幂等性由调用方保证：每次成功扣减至多触发一次补偿。
   */
  public int restoreStock(Long goodsId) {
    return goodsMapper.restoreStock(goodsId);
  }
}
