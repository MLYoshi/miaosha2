package com.example.goods.dao;

import com.example.goods.domain.Goods;
import com.example.goods.vo.GoodsVo;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface GoodsMapper {

  Goods getById(@Param("id") Long id);

  List<GoodsVo> listGoodsVo();

  GoodsVo getGoodsVo(@Param("goodsId") Long goodsId);
}
