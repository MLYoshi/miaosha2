package com.example.miaosha.controller;

import com.example.common.Result;
import com.example.miaosha.service.MiaoshaPreheatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端接缝：运营/后台专用的写操作统一挂在此处（/admin/**）。
 *
 * <p>当前无角色体系，鉴权沿用全局 JWT 登录拦截；引入角色后应在此接缝
 * （如针对 /admin/** 的拦截器）统一收紧为管理员可见，而不是散落到各业务 Controller。
 *
 * <p>预热完整闭环归本服务（写 Redis 库存 key），商品数据经 HTTP 取自 goods-service。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private final MiaoshaPreheatService miaoshaPreheatService;

  public AdminController(MiaoshaPreheatService miaoshaPreheatService) {
    this.miaoshaPreheatService = miaoshaPreheatService;
  }

  /** 活动开始前预热 Redis 库存（写操作，仅 POST）。 */
  @PostMapping("/preheat")
  public Result<String> preheat(@RequestParam Long goodsId) {
    miaoshaPreheatService.preheatStock(goodsId);
    return Result.success("ok");
  }
}
