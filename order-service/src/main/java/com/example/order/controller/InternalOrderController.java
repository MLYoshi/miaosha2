package com.example.order.controller;

import com.example.common.Result;
import com.example.order.cache.OrderResultStore;
import com.example.order.domain.OrderInfo;
import com.example.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供内部微服务调用的同步下单接口（miaosha-service Kafka 发送失败降级用）。
 *
 * <p>与 Kafka 消费路径共用同一 {@link OrderService#createOrder} 核心；
 * 业务失败经 {@code GlobalExceptionHandler} 返回 HTTP 200 + {@code Result.error(code)}，
 * 响应码与 common {@code CodeMsg} 原码一致，供调用方还原异常语义
 * （对齐 miaosha-service {@code HttpSyncOrderClient} 既有约定）。
 *
 * <p>result Key 归属（根 AGENTS.md 规则 3）：下单成功后由 order-service 自己
 * {@link OrderResultStore#markSuccess} 回写 {@code SUCCESS:{orderId}}，
 * 使消费者「迟到重复消息跳过补偿」守卫自洽，不依赖 miaosha-service 越权写 Key。
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

  private static final Logger log = LoggerFactory.getLogger(InternalOrderController.class);

  private final OrderService orderService;
  private final OrderResultStore resultStore;

  public InternalOrderController(OrderService orderService, OrderResultStore resultStore) {
    this.orderService = orderService;
    this.resultStore = resultStore;
  }

  /**
   * 同步下单：Body {@code {"userId":1,"goodsId":2}}，成功返回 {@code data=orderId}。
   * 请求体不含 requestId（幂等靠 DB 唯一键兜底，与消息契约对齐）。
   * 成功后回写 result=SUCCESS:{orderId}（尽力而为，失败不破坏订单事实）。
   */
  @PostMapping("/sync")
  public Result<Long> sync(@RequestBody @Valid SyncOrderRequest request) {
    log.info("同步降级下单 userId={} goodsId={}", request.userId(), request.goodsId());
    OrderInfo order = orderService.createOrder(request.userId(), request.goodsId());
    resultStore.markSuccess(request.goodsId(), request.userId(), order.getId());
    return Result.success(order.getId());
  }

  /** 同步下单请求体（字段与 Kafka 消息对齐，不含 requestId）。 */
  record SyncOrderRequest(@NotNull Long userId, @NotNull Long goodsId) {}
}
