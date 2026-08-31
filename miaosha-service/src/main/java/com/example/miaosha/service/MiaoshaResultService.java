package com.example.miaosha.service;

import com.example.miaosha.cache.MiaoshaRedisStore;
import com.example.miaosha.vo.MiaoshaResultVo;
import org.springframework.stereotype.Service;

/**
 * 秒杀结果查询：读 Redis 结果契约（轮询快路径）。
 *
 * <p>结果值约定沿用 Redis 契约：{@code PROCESSING} / {@code SUCCESS:{orderId}} /
 * {@code FAILED}。单体中的 DB 兜底（结果 key 丢失但订单已落库时查回成功态）
 * 不随本步骤迁移——miaosha-service 不得访问 order 表；该兜底由 Step 5
 * order-service 恢复（Redis 契约 TTL 内四态自足，本服务只做契约翻译）。
 */
@Service
public class MiaoshaResultService {

  private static final String PROCESSING = "PROCESSING";
  private static final String FAILED = "FAILED";
  private static final String SUCCESS_PREFIX = "SUCCESS:";

  private final MiaoshaRedisStore store;

  public MiaoshaResultService(MiaoshaRedisStore store) {
    this.store = store;
  }

  /** 查询用户对某商品的秒杀结果，四态可判别（见 {@link MiaoshaResultVo.Status}）。 */
  public MiaoshaResultVo query(Long userId, Long goodsId) {
    String result = store.getResult(goodsId, userId);
    if (result == null) {
      // 无记录（未参与 / 结果 key 已过期）：DB 兜底由 Step 5 order-service 恢复
      return MiaoshaResultVo.of(MiaoshaResultVo.Status.NONE);
    }
    return mapResult(result);
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
    // 未知结果值：按无记录处理（与单体一致）
    return MiaoshaResultVo.of(MiaoshaResultVo.Status.NONE);
  }
}
