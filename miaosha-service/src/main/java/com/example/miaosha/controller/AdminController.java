package com.example.miaosha.controller;

import com.example.common.Result;
import com.example.miaosha.client.GoodsClient;
import com.example.miaosha.service.MiaoshaPreheatService;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端接缝：运营/后台专用的写操作统一挂在此处（/admin/**）。
 *
 * <p>
 * 当前无角色体系，鉴权沿用全局 JWT 登录拦截；引入角色后应在此接缝
 * （如针对 /admin/** 的拦截器）统一收紧为管理员可见，而不是散落到各业务 Controller。
 *
 * <p>
 * 预热完整闭环归本服务（写 Redis 库存 key），商品数据经 HTTP 取自 goods-service。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private final MiaoshaPreheatService miaoshaPreheatService;
  private final GoodsClient goodsClient; // 构造器新增注入

  public AdminController(
      MiaoshaPreheatService miaoshaPreheatService, GoodsClient goodsClient) {
    this.miaoshaPreheatService = miaoshaPreheatService;
    this.goodsClient = goodsClient;
  }

  /** 活动开始前预热 Redis 库存（写操作，仅 POST）。 */
  @PostMapping("/preheat")
  public Result<String> preheat(@RequestParam Long goodsId) {
    miaoshaPreheatService.preheatStock(goodsId);
    return Result.success("ok");
  }

  /**
   * 重置某商品秒杀配置（时间窗对齐 + 可选重置库存）：
   * 1. goods-service 落库新窗口/库存（数据所有权在它那）；
   * 2. 本服务重写 Redis 预扣库存 Key（Key 归属在本服务，TTL 按新 end_date 重算）。
   */
  @PostMapping("/goods/{goodsId}/miaosha")
  public Result<GoodsClient.MiaoshaConfig> resetMiaosha(
      @PathVariable Long goodsId,
      @RequestParam(name = "durationMinutes", defaultValue = "60") long durationMinutes,
      @RequestParam(name = "stockCount", required = false) Integer stockCount) {
    GoodsClient.MiaoshaConfig config = goodsClient.updateMiaoshaConfig(goodsId, durationMinutes, stockCount);
    miaoshaPreheatService.preheatStock(goodsId);
    return Result.success(config);
  }

}
