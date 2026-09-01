package com.example.order.dao;

import com.example.order.domain.MiaoshaOrder;
import org.apache.ibatis.annotations.Param;

public interface MiaoshaOrderMapper {

  MiaoshaOrder getByUserIdAndGoodsId(
      @Param("userId") Long userId, @Param("goodsId") Long goodsId);

  int insert(MiaoshaOrder miaoshaOrder);
}
