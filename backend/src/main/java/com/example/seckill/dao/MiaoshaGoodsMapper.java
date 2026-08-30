package com.example.seckill.dao;

import org.apache.ibatis.annotations.Param;

public interface MiaoshaGoodsMapper {

  int reduceStock(@Param("goodsId") Long goodsId);
}
