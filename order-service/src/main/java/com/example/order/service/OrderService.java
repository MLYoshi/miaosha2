package com.example.order.service;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.order.client.GoodsClient;
import com.example.order.dao.MiaoshaOrderMapper;
import com.example.order.dao.OrderInfoMapper;
import com.example.order.domain.MiaoshaOrder;
import com.example.order.domain.OrderInfo;
import com.example.order.vo.GoodsSnapshotVo;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单落库核心（跨服务 Saga 编排）。
 *
 * <p>与单体 {@code MiaoshaService.createOrder} 的语义差异（有意拆分）：
 * 单体一个 {@code @Transactional} 覆盖「读商品 → 时间窗 → 幂等预检 → 扣库存 → 两表 INSERT」；
 * 微服务下库存扣减在 goods-service（远程、无本地事务），本地事务只覆盖两表 INSERT。
 * 编排顺序与不变量逐行对齐基线：
 * <ol>
 *   <li>取商品快照（等价单体时刻读 goods 表）</li>
 *   <li>时间窗校验（边界语义与单体一致）</li>
 *   <li>幂等预检：{@code getByUserIdAndGoodsId} 已存在 → MIAOSHA_REPEAT（不碰库存，
 *       重复消息在扣库存之前被拦下）</li>
 *   <li>远程条件扣库存：影响行数 0 → MIAOSHA_STOCK_EMPTY（库存不足，不建单）</li>
 *   <li>本地事务 INSERT order_info（拿自增 id）→ INSERT miaosha_order（唯一键最终兜底）</li>
 * </ol>
 *
 * <p>Saga 最小补偿：扣库存成功但建单失败 → {@link #restoreStockQuietly} 回补后——
 * 唯一键冲突抛 MIAOSHA_REPEAT（业务失败路径，调用方补偿 Redis 后 ack 不重试）；
 * 其他异常原样上抛（意外异常路径，走重试 → DLT）。补偿自身失败只记 error 日志，
 * 不做重试风暴（库存渗漏由对账发现）。
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  private static final int ORDER_CHANNEL_PC = 1;
  private static final int ORDER_STATUS_NEW = 0;

  private final GoodsClient goodsClient;
  private final MiaoshaWindowService windowService;
  private final OrderInfoMapper orderInfoMapper;
  private final MiaoshaOrderMapper miaoshaOrderMapper;
  private final Clock clock;
  /** 自注入代理：事务方法必须经代理调用，直接 this 调用会使 @Transactional 失效。 */
  private final OrderService self;

  public OrderService(
      GoodsClient goodsClient,
      MiaoshaWindowService windowService,
      OrderInfoMapper orderInfoMapper,
      MiaoshaOrderMapper miaoshaOrderMapper,
      Clock clock,
      @Lazy OrderService self) {
    this.goodsClient = goodsClient;
    this.windowService = windowService;
    this.orderInfoMapper = orderInfoMapper;
    this.miaoshaOrderMapper = miaoshaOrderMapper;
    this.clock = clock;
    this.self = self;
  }

  /**
   * 同步下单入口（requestId 未知，如同步降级）：等价 {@link #createOrder(Long, Long, String)}
   * 且不做扣减幂等（同步路径单次尝试、无 Kafka 重放，不存在重放重复扣减）。
   */
  public OrderInfo createOrder(Long userId, Long goodsId) {
    return createOrder(userId, goodsId, null);
  }

  /**
   * 秒杀下单唯一入口：远程扣库存（无事务）+ 本地两表 INSERT（独立事务）。
   * 不依赖 Redis，DB 条件扣库存（{@code stock_count > 0}）+ 唯一键作为最终防线。
   *
   * <p>requestId 来自消息（Kafka 消费路径）并随扣减请求下发 goods-service 做短期幂等：
   * 扣减响应丢失 → 消息重放 → 同一 requestId 命中幂等缓存返回上次影响行数，
   * 不重复扣减（review report Issue 1）。
   */
  public OrderInfo createOrder(Long userId, Long goodsId, String requestId) {
    GoodsSnapshotVo goods = goodsClient.getGoodsVo(goodsId);
    if (goods == null) {
      throw new MiaoshaException(CodeMsg.GOODS_NOT_EXIST);
    }
    windowService.checkInWindow(goods.getStartDate(), goods.getEndDate());

    MiaoshaOrder existing = miaoshaOrderMapper.getByUserIdAndGoodsId(userId, goodsId);
    if (existing != null) {
      throw new MiaoshaException(CodeMsg.MIAOSHA_REPEAT);
    }

    int rows = goodsClient.deductStock(goodsId, requestId);
    if (rows <= 0) {
      throw new MiaoshaException(CodeMsg.MIAOSHA_STOCK_EMPTY);
    }

    try {
      return self.insertOrderTx(userId, goodsId, goods);
    } catch (DuplicateKeyException e) {
      // miaosha_order UNIQUE(user_id, goods_id) 兜底命中：回补库存后按业务失败处理
      restoreStockQuietly(goodsId);
      log.info("唯一键冲突，重复下单 goodsId={} userId={}", goodsId, userId);
      throw new MiaoshaException(CodeMsg.MIAOSHA_REPEAT);
    } catch (RuntimeException e) {
      // 其他建单失败（意外异常路径）：先补偿再上抛，由调用方决定重试/DLT
      restoreStockQuietly(goodsId);
      throw e;
    }
  }

  /**
   * 两表 INSERT 的独立事务方法。必须经 {@code self} 代理调用（自调用会使事务失效）：
   * {@code order_info} 先插入拿自增 id，再插入 {@code miaosha_order}，
   * 唯一键冲突以 {@link DuplicateKeyException} 形态抛出供外层捕获。
   */
  @Transactional
  public OrderInfo insertOrderTx(Long userId, Long goodsId, GoodsSnapshotVo goods) {
    OrderInfo orderInfo = buildOrderInfo(userId, goodsId, goods);
    orderInfoMapper.insert(orderInfo);

    MiaoshaOrder miaoshaOrder = new MiaoshaOrder();
    miaoshaOrder.setUserId(userId);
    miaoshaOrder.setOrderId(orderInfo.getId());
    miaoshaOrder.setGoodsId(goodsId);
    miaoshaOrderMapper.insert(miaoshaOrder);

    return orderInfo;
  }

  /** goods_name/goods_price 保持下单时快照语义（等价单体 buildOrderInfo）。 */
  private OrderInfo buildOrderInfo(Long userId, Long goodsId, GoodsSnapshotVo goods) {
    OrderInfo orderInfo = new OrderInfo();
    orderInfo.setUserId(userId);
    orderInfo.setGoodsId(goodsId);
    orderInfo.setGoodsName(goods.getGoodsName());
    orderInfo.setGoodsCount(1);
    orderInfo.setGoodsPrice(goods.getMiaoshaPrice());
    orderInfo.setOrderChannel(ORDER_CHANNEL_PC);
    orderInfo.setStatus(ORDER_STATUS_NEW);
    orderInfo.setCreateDate(LocalDateTime.now(clock));
    return orderInfo;
  }

  /**
   * Saga 补偿：回补库存。幂等性由编排保证（每次成功扣减至多触发一次补偿，
   * 且补偿发生在单次消费尝试内、业务失败 ack 后不重试）；
   * 补偿自身失败只记 error 日志，不做重试风暴（库存渗漏由对账发现）。
   */
  private void restoreStockQuietly(Long goodsId) {
    try {
      goodsClient.restoreStock(goodsId);
    } catch (Exception e) {
      log.error("库存回补失败 goodsId={}: {}", goodsId, e.getMessage(), e);
    }
  }
}
