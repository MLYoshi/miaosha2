package com.example.seckill.controller;

import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.Result;
import com.example.seckill.service.GoodsService;
import com.example.seckill.vo.GoodsDetailVo;
import com.example.seckill.vo.GoodsVo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/goods")
public class GoodsController {

  private final GoodsService goodsService;

  public GoodsController(GoodsService goodsService) {
    this.goodsService = goodsService;
  }

  @GetMapping("/list")
  public Result<List<GoodsVo>> list() {
    return Result.success(goodsService.listGoodsVo());
  }

  @GetMapping("/detail/{goodsId}")
  public Result<GoodsDetailVo> detail(@PathVariable Long goodsId) {
    GoodsDetailVo detail = goodsService.getGoodsDetail(goodsId);
    if (detail == null) {
      CodeMsg codeMsg = CodeMsg.GOODS_NOT_EXIST;
      return Result.error(codeMsg.getCode(), codeMsg.getMsg());
    }
    return Result.success(detail);
  }
}
