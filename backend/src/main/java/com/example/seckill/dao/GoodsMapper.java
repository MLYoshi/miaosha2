package com.example.seckill.dao;

import com.example.seckill.domain.Goods;
import com.example.seckill.vo.GoodsVo;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface GoodsMapper {

  Goods getById(@Param("id") Long id);

  List<GoodsVo> listGoodsVo();

  GoodsVo getGoodsVo(@Param("goodsId") Long goodsId);
}
