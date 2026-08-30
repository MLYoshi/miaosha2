package com.example.seckill.service;

import com.example.seckill.cache.MiaoshaRedisStore;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.domain.OrderInfo;
import com.example.seckill.message.SeckillOrderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 消费者落库编排：把一条下单请求消息变成数据库订单，并回写 Redis 结果。
 *
 * <p>编排语义（票 03 + 票 04）：
 * <ul>
 *   <li>幂等快跳（票 04）：消费前查结果标记，已 {@code SUCCESS} 的重复投递直接返回，
 *       不再触碰 DB——DB 唯一键仍是结果标记丢失时的最终兜底
 *   <li>落库成功 → {@code markSuccess}，轮询接口可查到订单号
 *   <li>业务失败（{@link MiaoshaException}，如 DB 已有记录、时间窗外）→
 *       {@code compensate}（库存回补、标记清除、result=FAILED）后正常返回，
 *       listener ack——不重试业务失败
 *   <li>意外异常上抛，listener 不 ack，由容器错误处理重试并终入死信（票 04）
 * </ul>
 *
 * <p>不依赖 Kafka，可经内存假 Redis + mock 下单事务直测。
 */
@Service
public class OrderFulfillmentService {

  private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentService.class);

  private static final String SUCCESS_PREFIX = "SUCCESS:";

  private final MiaoshaService miaoshaService;
  private final MiaoshaRedisStore store;

  public OrderFulfillmentService(MiaoshaService miaoshaService, MiaoshaRedisStore store) {
    this.miaoshaService = miaoshaService;
    this.store = store;
  }

  /** 消费一条下单请求消息：幂等快跳 + 落库 + 回写 / 补偿，业务失败正常返回，意外异常上抛。 */
  public void fulfill(SeckillOrderMessage message) {
    Long userId = message.getUserId();
    Long goodsId = message.getGoodsId();
    if (alreadySucceeded(goodsId, userId)) {
      // 幂等快跳（票 04）：重复投递且已有成功结果 → 直接视为处理完成；
      // 结果标记丢失的重复仍由 DB 唯一键兜底（业务失败补偿路径）
      log.info("重复投递快跳：已有成功结果 goodsId={} userId={} requestId={}", goodsId, userId,
          message.getRequestId());
      return;
    }
    try {
      OrderInfo order = miaoshaService.createOrder(userId, goodsId);
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
