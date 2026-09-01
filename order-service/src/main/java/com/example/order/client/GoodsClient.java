package com.example.order.client;

import com.example.order.vo.GoodsSnapshotVo;

/**
 * goods-service 侧接缝：商品快照获取与库存扣减/回补（测试可用假实现替换）。
 *
 * <p>对应 goods-service {@code /internal/goods/**} 内部接口，等价单体
 * {@code createOrder} 时刻对 goods/miaosha_goods 表的本地读写。
 *
 * <p>异常契约：
 * <ul>
 *   <li>{@link #getGoodsVo} / {@link #deductStock} 连接类异常向上抛，
 *       由调用方决定语义（消费路径 → 重试/DLT）</li>
 *   <li>{@link #restoreStock} 补偿回补，调用方（OrderService）负责吞异常记日志，
 *       不做重试风暴（库存渗漏由对账发现）</li>
 * </ul>
 */
public interface GoodsClient {

  /**
   * 取商品快照（等价单体 createOrder 时刻读 goods 表）。
   *
   * @return 快照；商品不存在返回 {@code null}（调用方转为 GOODS_NOT_EXIST）
   */
  GoodsSnapshotVo getGoodsVo(Long goodsId);

  /**
   * 条件扣减秒杀库存（防超卖，SQL {@code stock_count > 0}）。
   *
   * <p>Issue 1（扣减结果二义性 × Kafka 自动重试 → 重复扣库存）：requestId 随扣减请求下发，
   * goods-service 以其做 Redis 短期幂等（SETNX 语义，TTL 60s）——响应丢失后整条消息重放时
   * 命中同一 requestId，直接返回上次影响行数，不再二次执行条件 UPDATE，
   * 保证「1 个订单 ↔ 1 次 DB 扣减」。
   *
   * @param requestId 消息幂等键；同步降级路径无重放，传 {@code null}
   * @return 影响行数：1 成功 / 0 库存不足
   */
  int deductStock(Long goodsId, String requestId);

  /** 回补秒杀库存（Saga 补偿，无条件 +1；幂等性由调用方编排保证：每次扣减至多一次）。 */
  void restoreStock(Long goodsId);
}
