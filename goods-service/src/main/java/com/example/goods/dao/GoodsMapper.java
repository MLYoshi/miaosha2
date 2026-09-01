package com.example.goods.dao;

import com.example.goods.domain.Goods;
import com.example.goods.vo.GoodsVo;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface GoodsMapper {

  Goods getById(@Param("id") Long id);

  List<GoodsVo> listGoodsVo();

  GoodsVo getGoodsVo(@Param("goodsId") Long goodsId);

  /**
   * 条件扣减秒杀库存（防超卖）：stock_count > 0 才扣，影响行数 0 → 库存不足。
   * SQL 与基线 backend MiaoshaGoodsMapper.reduceStock 逐字对齐。
   */
  int reduceStock(@Param("goodsId") Long goodsId);

  /**
   * 无条件回补秒杀库存（Saga 补偿），幂等性由调用方（order-service 编排）保证。
   */
  int restoreStock(@Param("goodsId") Long goodsId);
}
