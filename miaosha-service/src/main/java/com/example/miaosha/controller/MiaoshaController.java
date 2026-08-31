package com.example.miaosha.controller;

import com.example.common.Result;
import com.example.miaosha.service.MiaoshaAcceptService;
import com.example.miaosha.service.MiaoshaResultService;
import com.example.miaosha.vo.MiaoshaAcceptVo;
import com.example.miaosha.vo.MiaoshaResultVo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀 HTTP 入口，只做 HTTP 翻译：鉴头取 userId、参数解析、结果包装。
 *
 * <p>业务失败（{@code MiaoshaException}）统一由全局异常处理器转换为 Result.error，
 * 此处不做第二份转换。运营/管理动作（如库存预热）见 {@link AdminController}。
 */
@RestController
@RequestMapping("/miaosha")
public class MiaoshaController {

  private final MiaoshaAcceptService miaoshaAcceptService;
  private final MiaoshaResultService miaoshaResultService;

  public MiaoshaController(
      MiaoshaAcceptService miaoshaAcceptService, MiaoshaResultService miaoshaResultService) {
    this.miaoshaAcceptService = miaoshaAcceptService;
    this.miaoshaResultService = miaoshaResultService;
  }

  /**
   * 秒杀主流程入口（写操作，仅 POST）：受理编排见 {@link MiaoshaAcceptService}。
   *
   * <p>票 03 破坏性变化：响应不再含订单详情，改为受理态（受理中 / 降级直接拿单），
   * 订单经 GET /miaosha/result 轮询获取。
   */
  @PostMapping("/do_miaosha")
  public Result<MiaoshaAcceptVo> doMiaosha(HttpServletRequest request, @RequestParam Long goodsId) {
    Long userId = (Long) request.getAttribute("userId");
    return Result.success(miaoshaAcceptService.execute(userId, goodsId));
  }

  /** 结果轮询（读操作，GET）：四态可判别（DB 兜底由 Step 5 order-service 恢复）。 */
  @GetMapping("/result")
  public Result<MiaoshaResultVo> result(HttpServletRequest request, @RequestParam Long goodsId) {
    Long userId = (Long) request.getAttribute("userId");
    return Result.success(miaoshaResultService.query(userId, goodsId));
  }
}
