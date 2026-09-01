package com.example.goods.controller;

import com.example.common.CodeMsg;
import com.example.common.Result;
import com.example.goods.service.GoodsService;
import com.example.goods.vo.GoodsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供内部微服务调用的商品接口（order-service），与对外 /goods/** 隔离：
 *
 * - GET  /internal/goods/{goodsId}              商品快照（GoodsVo）
 * - POST /internal/goods/{goodsId}/deduct-stock 条件扣减库存，data=影响行数（1 成功 / 0 库存不足）
 * - POST /internal/goods/{goodsId}/restore-stock 回补库存（Saga 补偿，无条件 +1）
 *
 * 不受 JWT 拦截器影响（WebConfig 放行 /internal/**）。
 */
@RestController
@RequestMapping("/internal/goods")
public class InternalGoodsController {

  private static final Logger log = LoggerFactory.getLogger(InternalGoodsController.class);

  private final GoodsService goodsService;

  public InternalGoodsController(GoodsService goodsService) {
    this.goodsService = goodsService;
  }

  /**
   * 商品快照：等价单体 createOrder 时刻读 goods 表的语义，订单表存快照后不再实时回查。
   */
  @GetMapping("/{goodsId}")
  public Result<GoodsVo> snapshot(@PathVariable Long goodsId) {
    GoodsVo goodsVo = goodsService.getGoodsVo(goodsId);
    if (goodsVo == null) {
      CodeMsg codeMsg = CodeMsg.GOODS_NOT_EXIST;
      return Result.error(codeMsg.getCode(), codeMsg.getMsg());
    }
    return Result.success(goodsVo);
  }

  /**
   * 条件扣减秒杀库存：data=影响行数，0 表示库存不足（由调用方决定后续语义）。
   *
   * <p>requestId（可选，来自 Kafka 消息）：做短期幂等——扣减响应丢失后消息重放同一 requestId，
   * 直接返回上次影响行数，不重复扣减（review report Issue 1）。
   */
  @PostMapping("/{goodsId}/deduct-stock")
  public Result<Integer> deductStock(
      @PathVariable Long goodsId,
      @RequestParam(name = "requestId", required = false) String requestId) {
    int rows = goodsService.deductStock(goodsId, requestId);
    log.info("internal deduct-stock goodsId={}, requestId={}, rows={}", goodsId, requestId, rows);
    return Result.success(rows);
  }

  /**
   * 回补秒杀库存（补偿）：无条件 +1，幂等性由调用方编排保证。
   */
  @PostMapping("/{goodsId}/restore-stock")
  public Result<Void> restoreStock(@PathVariable Long goodsId) {
    int rows = goodsService.restoreStock(goodsId);
    log.info("internal restore-stock goodsId={}, rows={}", goodsId, rows);
    return Result.success(null);
  }
}
