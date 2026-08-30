package com.example.seckill.cache;

import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.service.GoodsService;
import com.example.seckill.vo.GoodsVo;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 秒杀库存预热：从数据库读取 stock_count，经 {@link MiaoshaRedisStore#setStock} 写入 Redis。
 *
 * <p>TTL = 活动结束后 30 分钟，最小 1 小时；endDate 为空时取默认 1 天。
 */
@Service
public class MiaoshaPreheatService {

  private static final Logger log = LoggerFactory.getLogger(MiaoshaPreheatService.class);

  /** 库存 key 在预热时的额外保留时间（活动结束后 30 分钟） */
  private static final long STOCK_TTL_BUFFER_SECONDS = 1800L;

  private static final long MIN_TTL_SECONDS = 3600L;

  private static final long DEFAULT_TTL_SECONDS = 86400L;

  private final MiaoshaRedisStore store;
  private final GoodsService goodsService;
  private final Clock clock;

  public MiaoshaPreheatService(MiaoshaRedisStore store, GoodsService goodsService, Clock clock) {
    this.store = store;
    this.goodsService = goodsService;
    this.clock = clock;
  }

  /** 活动开始前预热库存：从数据库读取 stock_count 写入 Redis。 */
  public void preheatStock(Long goodsId) {
    GoodsVo goods = goodsService.getGoodsVo(goodsId);
    if (goods == null || goods.getStockCount() == null) {
      // getGoodsVo 对 goods 做 INNER JOIN miaosha_goods：商品或秒杀配置缺失均为"商品不存在"
      throw new MiaoshaException(CodeMsg.GOODS_NOT_EXIST);
    }
    long ttl = computeTtl(goods.getEndDate());
    store.setStock(goodsId, goods.getStockCount(), Duration.ofSeconds(ttl));
    log.info(
        "预热秒杀库存 goodsId={} stock={} ttl={}s", goodsId, goods.getStockCount(), ttl);
  }

  private long computeTtl(LocalDateTime endDate) {
    if (endDate == null) {
      return DEFAULT_TTL_SECONDS;
    }
    long ttl =
        Duration.between(LocalDateTime.now(clock), endDate).getSeconds()
            + STOCK_TTL_BUFFER_SECONDS;
    return Math.max(ttl, MIN_TTL_SECONDS);
  }
}
