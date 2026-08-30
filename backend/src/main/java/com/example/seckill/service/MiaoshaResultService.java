package com.example.seckill.service;

import com.example.seckill.cache.MiaoshaRedisStore;
import com.example.seckill.dao.MiaoshaOrderMapper;
import com.example.seckill.domain.MiaoshaOrder;
import com.example.seckill.vo.MiaoshaResultVo;
import org.springframework.stereotype.Service;

/**
 * 秒杀结果查询：先读 Redis 结果契约，无记录时从 DB 订单兜底查回。
 *
 * <p>结果值约定沿用 Redis 契约：{@code PROCESSING} / {@code SUCCESS:{orderId}} /
 * {@code FAILED}。Redis 是轮询快路径；DB 是最终事实来源——结果 key 丢失但订单已落库时，
 * 兜底返回成功态与订单号，避免用户已抢到却查无结果。
 */
@Service
public class MiaoshaResultService {

  private static final String PROCESSING = "PROCESSING";
  private static final String FAILED = "FAILED";
  private static final String SUCCESS_PREFIX = "SUCCESS:";

  private final MiaoshaRedisStore store;
  private final MiaoshaOrderMapper miaoshaOrderMapper;

  public MiaoshaResultService(MiaoshaRedisStore store, MiaoshaOrderMapper miaoshaOrderMapper) {
    this.store = store;
    this.miaoshaOrderMapper = miaoshaOrderMapper;
  }

  /** 查询用户对某商品的秒杀结果，四态可判别（见 {@link MiaoshaResultVo.Status}）。 */
  public MiaoshaResultVo query(Long userId, Long goodsId) {
    String result = store.getResult(goodsId, userId);
    if (result != null) {
      return mapResult(result);
    }
    return fallbackToDb(userId, goodsId);
  }

  private MiaoshaResultVo mapResult(String result) {
    if (PROCESSING.equals(result)) {
      return MiaoshaResultVo.of(MiaoshaResultVo.Status.PROCESSING);
    }
    if (FAILED.equals(result)) {
      return MiaoshaResultVo.of(MiaoshaResultVo.Status.FAILED);
    }
    if (result.startsWith(SUCCESS_PREFIX)) {
      return new MiaoshaResultVo(
          MiaoshaResultVo.Status.SUCCESS, Long.parseLong(result.substring(SUCCESS_PREFIX.length())));
    }
    // 未知结果值：按无记录处理，交由 DB 兜底判定
    return MiaoshaResultVo.of(MiaoshaResultVo.Status.NONE);
  }

  private MiaoshaResultVo fallbackToDb(Long userId, Long goodsId) {
    MiaoshaOrder order = miaoshaOrderMapper.getByUserIdAndGoodsId(userId, goodsId);
    if (order == null) {
      return MiaoshaResultVo.of(MiaoshaResultVo.Status.NONE);
    }
    return new MiaoshaResultVo(MiaoshaResultVo.Status.SUCCESS, order.getOrderId());
  }
}
