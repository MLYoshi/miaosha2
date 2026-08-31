package com.example.goods.controller;

import com.example.common.Result;
import com.example.goods.service.StockPreheatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端接缝：运营/后台专用的写操作统一挂在此处（/admin/**）。
 *
 * <p>当前无角色体系，鉴权沿用全局 JWT 登录拦截；引入角色后应在此接缝
 * （如针对 /admin/** 的拦截器）统一收紧为管理员可见，而不是散落到各业务 Controller。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private final StockPreheatService stockPreheatService;

  public AdminController(StockPreheatService stockPreheatService) {
    this.stockPreheatService = stockPreheatService;
  }

  /** 活动开始前预热库存（DB 读取与校验；Redis setStock 仍由 backend 承担）。 */
  @PostMapping("/preheat")
  public Result<String> preheat(@RequestParam Long goodsId) {
    stockPreheatService.preheatStock(goodsId);
    return Result.success("ok");
  }
}
