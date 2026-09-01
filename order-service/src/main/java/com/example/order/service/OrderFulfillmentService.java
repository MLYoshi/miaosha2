package com.example.order.service;

import com.example.common.MiaoshaException;
import com.example.order.cache.OrderResultStore;
import com.example.order.domain.OrderInfo;
import com.example.order.message.SeckillOrderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 消费者落库编排（迁移自单体 {@code OrderFulfillmentService}）：
 * 把一条下单请求消息变成数据库订单，并回写 Redis 结果。
 *
 * <p>编排语义（与基线逐行对齐，落库核心改为本地 {@link OrderService}、
 * Redis 回写改为本地 {@link OrderResultStore}）：
 * <ul>
 *   <li>幂等快跳：消费前查结果标记，已 {@code SUCCESS} 的重复投递直接返回，
 *       不再触碰 DB——DB 唯一键仍是结果标记丢失时的最终兜底
 *   <li>落库成功 → {@code markSuccess}，轮询接口可查到订单号
 *   <li>业务失败（{@link MiaoshaException}，如 DB 已有记录、时间窗外、库存不足）→
 *       再查一次 SUCCESS（迟到重复跳过补偿）→ {@code compensate}（库存回补、
 *       标记清除、result=FAILED）后正常返回，listener ack——不重试业务失败
 *   <li>意外异常上抛，listener 不 ack，由容器错误处理重试并终入死信
 * </ul>
 *
 * <p>不依赖 Kafka，可经假 {@link OrderResultStore} + 假 {@code GoodsClient} 直测。
 */
@Service
public class OrderFulfillmentService {

  private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentService.class);

  private static final String SUCCESS_PREFIX = "SUCCESS:";

  private final OrderService orderService;
  private final OrderResultStore store;

  public OrderFulfillmentService(OrderService orderService, OrderResultStore store) {
    this.orderService = orderService;
    this.store = store;
  }

  /** 消费一条下单请求消息：幂等快跳 + 落库 + 回写 / 补偿，业务失败正常返回，意外异常上抛。 */
  public void fulfill(SeckillOrderMessage message) {
    Long userId = message.getUserId();
    Long goodsId = message.getGoodsId();
    if (alreadySucceeded(goodsId, userId)) {
      // 幂等快跳：重复投递且已有成功结果 → 直接视为处理完成；
      // 结果标记丢失的重复仍由 DB 唯一键兜底（业务失败补偿路径）
      log.info("重复投递快跳：已有成功结果 goodsId={} userId={} requestId={}", goodsId, userId,
          message.getRequestId());
      return;
    }
    try {
      // requestId 随扣减下发 goods-service 做短期幂等：扣减响应丢失 → 本消息重放时
      // 同一 requestId 命中幂等缓存，不重复扣减（review report Issue 1）
      OrderInfo order = orderService.createOrder(userId, goodsId, message.getRequestId());
      store.markSuccess(goodsId, userId, order.getId());
    } catch (MiaoshaException e) {
      if (alreadySucceeded(goodsId, userId)) {
        // 降级同步落库已成功，这条是迟到的重复消息：DB 唯一键已拦下，跳过补偿
        log.warn("迟到重复消息跳过补偿 goodsId={} userId={} requestId={}", goodsId, userId,
            message.getRequestId());
        return;
      }
      log.warn("下单业务失败，执行补偿 goodsId={} userId={} requestId={}: {}", goodsId, userId,
          message.getRequestId(), e.getCodeMsg().getMsg());
      store.compensate(goodsId, userId, message.getRequestId());
    }
  }

  /** 该用户该商品的受理是否已有成功结果（降级同步落库后 result=SUCCESS:{orderId}）。 */
  private boolean alreadySucceeded(Long goodsId, Long userId) {
    String result = store.getResult(goodsId, userId);
    return result != null && result.startsWith(SUCCESS_PREFIX);
  }
}
