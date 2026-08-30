package com.example.seckill.dao;

import com.example.seckill.domain.MiaoshaOrder;
import org.apache.ibatis.annotations.Param;

public interface MiaoshaOrderMapper {

  MiaoshaOrder getByUserIdAndGoodsId(
      @Param("userId") Long userId, @Param("goodsId") Long goodsId);

  int insert(MiaoshaOrder miaoshaOrder);
}
