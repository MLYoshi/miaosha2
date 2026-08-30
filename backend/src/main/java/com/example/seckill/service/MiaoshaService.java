package com.example.seckill.service;

import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.dao.MiaoshaGoodsMapper;
import com.example.seckill.dao.MiaoshaOrderMapper;
import com.example.seckill.dao.OrderInfoMapper;
import com.example.seckill.domain.MiaoshaOrder;
import com.example.seckill.domain.OrderInfo;
import com.example.seckill.vo.GoodsVo;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiaoshaService {

  private static final int ORDER_CHANNEL_PC = 1;
  private static final int ORDER_STATUS_NEW = 0;

  private final GoodsService goodsService;
  private final MiaoshaWindowService windowService;
  private final MiaoshaGoodsMapper miaoshaGoodsMapper;
  private final OrderInfoMapper orderInfoMapper;
  private final MiaoshaOrderMapper miaoshaOrderMapper;
  private final Clock clock;

  public MiaoshaService(
      GoodsService goodsService,
      MiaoshaWindowService windowService,
      MiaoshaGoodsMapper miaoshaGoodsMapper,
      OrderInfoMapper orderInfoMapper,
      MiaoshaOrderMapper miaoshaOrderMapper,
      Clock clock) {
    this.goodsService = goodsService;
    this.windowService = windowService;
    this.miaoshaGoodsMapper = miaoshaGoodsMapper;
    this.orderInfoMapper = orderInfoMapper;
    this.miaoshaOrderMapper = miaoshaOrderMapper;
    this.clock = clock;
  }

  /**
   * 秒杀下单唯一入口：Redis 预扣成功后的数据库落库事务（Redis 不可用降级直连时也走此方法）。
   *
   * <p>不依赖 Redis，DB 条件扣库存（{@code stock_count > 0}）+ 唯一键作为最终防线：
   * <ul>
   *   <li>防超卖：{@link MiaoshaGoodsMapper#reduceStock} 条件更新</li>
   *   <li>防重复：{@code miaosha_order} 唯一键 {@code u_uid_gid} 兜底</li>
   * </ul>
   */
  @Transactional
  public OrderInfo createOrder(Long userId, Long goodsId) {
    GoodsVo goods = goodsService.getGoodsVo(goodsId);
    if (goods == null) {
      throw new MiaoshaException(CodeMsg.GOODS_NOT_EXIST);
    }
    windowService.checkInWindow(goods.getStartDate(), goods.getEndDate());

    MiaoshaOrder existing = miaoshaOrderMapper.getByUserIdAndGoodsId(userId, goodsId);
    if (existing != null) {
      throw new MiaoshaException(CodeMsg.MIAOSHA_REPEAT);
    }

    int rows = miaoshaGoodsMapper.reduceStock(goodsId);
    if (rows <= 0) {
      throw new MiaoshaException(CodeMsg.MIAOSHA_STOCK_EMPTY);
    }

    OrderInfo orderInfo = buildOrderInfo(userId, goods);
    orderInfoMapper.insert(orderInfo);

    MiaoshaOrder miaoshaOrder = new MiaoshaOrder();
    miaoshaOrder.setUserId(userId);
    miaoshaOrder.setOrderId(orderInfo.getId());
    miaoshaOrder.setGoodsId(goodsId);
    miaoshaOrderMapper.insert(miaoshaOrder);

    return orderInfo;
  }

  private OrderInfo buildOrderInfo(Long userId, GoodsVo goods) {
    OrderInfo orderInfo = new OrderInfo();
    orderInfo.setUserId(userId);
    orderInfo.setGoodsId(goods.getId());
    orderInfo.setGoodsName(goods.getGoodsName());
    orderInfo.setGoodsCount(1);
    orderInfo.setGoodsPrice(goods.getMiaoshaPrice());
    orderInfo.setOrderChannel(ORDER_CHANNEL_PC);
    orderInfo.setStatus(ORDER_STATUS_NEW);
    orderInfo.setCreateDate(LocalDateTime.now(clock));
    return orderInfo;
  }
}
