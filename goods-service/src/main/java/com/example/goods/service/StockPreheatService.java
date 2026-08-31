package com.example.goods.service;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.goods.vo.GoodsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 秒杀库存预热的 DB 部分：读取并校验 stock_count。
 *
 * <p>由旧单体 {@code MiaoshaPreheatService} 拆出：DB 读取校验归 goods-service，
 * Redis setStock（TTL 计算）留在 backend，Step 4 归 miaosha-service。本类不引入 Redis 依赖。
 */
@Service
public class StockPreheatService {

  private static final Logger log = LoggerFactory.getLogger(StockPreheatService.class);

  private final GoodsService goodsService;

  public StockPreheatService(GoodsService goodsService) {
    this.goodsService = goodsService;
  }

  /** 活动开始前预热库存：从数据库读取 stock_count 并校验，不写 Redis、不修改 DB。 */
  public void preheatStock(Long goodsId) {
    GoodsVo goods = goodsService.getGoodsVo(goodsId);
    if (goods == null || goods.getStockCount() == null) {
      // getGoodsVo 对 goods 做 INNER JOIN miaosha_goods：商品或秒杀配置缺失均为"商品不存在"
      throw new MiaoshaException(CodeMsg.GOODS_NOT_EXIST);
    }
    log.info("校验秒杀库存 goodsId={} stock={}", goodsId, goods.getStockCount());
  }
}
