package com.example.seckill.service;

import com.example.seckill.cache.MiaoshaRedisStore;
import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.domain.OrderInfo;
import com.example.seckill.message.OrderMessageSender;
import com.example.seckill.message.SeckillOrderMessage;
import com.example.seckill.vo.MiaoshaAcceptVo;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 秒杀受理：一次秒杀请求的编排——Redis 预扣库存 → 发 Kafka → 立即返回受理中；
 * 消费者异步落库，前端轮询 result 拿单（票 03 削峰改造，DB 写入移出请求路径）。
 *
 * <p>模块唯一入口 {@link #execute(Long, Long)}，不依赖 HTTP / Redis / Kafka 实现，可直测：
 * Redis 侧经 {@link MiaoshaRedisStore} 接缝、消息侧经 {@link OrderMessageSender} 接缝注入
 * （生产 Redis/Kafka 适配器 / 测试假适配器）。
 *
 * <p>降级哲学（多层兜底）：
 * <ul>
 *   <li>Redis 不可用 → 直连 DB 落库（既有路径，行为不变），由 DB 条件扣库存 + 唯一键兜底
 *   <li>Kafka 发送失败 → 降级同步落库，用户直接拿单；降级后迟到的重复消息由
 *       DB 唯一键拦下后跳过补偿（见消费者编排），不产生重复订单
 * </ul>
 *
 * <p>语义约定（F9 已固化）：未预热 / 商品不存在时先由 Redis 库存闸门返回「库存不足」
 * 500214，不把商品存在性校验前置到预扣之前。
 */
@Service
public class MiaoshaAcceptService {

  private static final Logger log = LoggerFactory.getLogger(MiaoshaAcceptService.class);

  private final MiaoshaRedisStore store;
  private final MiaoshaService miaoshaService;
  private final OrderMessageSender sender;

  public MiaoshaAcceptService(
      MiaoshaRedisStore store, MiaoshaService miaoshaService, OrderMessageSender sender) {
    this.store = store;
    this.miaoshaService = miaoshaService;
    this.sender = sender;
  }

  /**
   * 受理一次秒杀请求：成功返回受理态（正常受理中 / 降级直接拿单）；
   * 失败抛出对应业务码的 {@link MiaoshaException}。
   */
  public MiaoshaAcceptVo execute(Long userId, Long goodsId) {
    String requestId = newRequestId();

    MiaoshaRedisStore.TryResult result;
    try {
      result = store.tryMiaosha(goodsId, userId, requestId);
    } catch (Exception e) {
      // Redis 不可用降级（既有路径，行为不变）：直接走数据库，由 DB 条件扣库存 + 唯一键兜底
      log.warn("Redis 预扣异常，降级直连 DB goodsId={} userId={}: {}", goodsId, userId,
          e.getMessage());
      OrderInfo order = miaoshaService.createOrder(userId, goodsId);
      return MiaoshaAcceptVo.success(order.getId());
    }

    switch (result) {
      case REPEAT -> throw new MiaoshaException(CodeMsg.MIAOSHA_REPEAT);
      case STOCK_EMPTY -> throw new MiaoshaException(CodeMsg.MIAOSHA_STOCK_EMPTY);
      case OK -> { }
    }

    // 预扣成功：发消息受理，落库移出请求路径
    try {
      sender.send(new SeckillOrderMessage(userId, goodsId, requestId));
      return MiaoshaAcceptVo.processing();
    } catch (Exception e) {
      // Kafka 发送失败降级：同步落库，用户直接拿单（沿用 Redis 降级哲学）
      log.warn("Kafka 发送失败，降级同步落库 goodsId={} userId={}: {}", goodsId, userId,
          e.getMessage());
      try {
        OrderInfo order = miaoshaService.createOrder(userId, goodsId);
        store.markSuccess(goodsId, userId, order.getId());
        return MiaoshaAcceptVo.success(order.getId());
      } catch (RuntimeException dbEx) {
        // 降级落库失败：补偿 Redis
        store.compensate(goodsId, userId, requestId);
        throw dbEx;
      }
    }
  }

  /** 生成本次受理请求的全链路标识，落库失败补偿时凭它校验归属。 */
  private String newRequestId() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
