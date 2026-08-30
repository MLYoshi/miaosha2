package com.example.seckill.service;

import com.example.seckill.dao.GoodsMapper;
import com.example.seckill.domain.Goods;
import com.example.seckill.vo.GoodsDetailVo;
import com.example.seckill.vo.GoodsVo;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GoodsService {

  private final GoodsMapper goodsMapper;
  private final MiaoshaWindowService windowService;

  public GoodsService(GoodsMapper goodsMapper, MiaoshaWindowService windowService) {
    this.goodsMapper = goodsMapper;
    this.windowService = windowService;
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
}
