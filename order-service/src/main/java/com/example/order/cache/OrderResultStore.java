package com.example.order.cache;

/**
 * 秒杀结果回写接缝（order-service 自带 Redis 客户端，直写 {@code miaosha:result:*}）。
 *
 * <p>Key 归属（根 AGENTS.md 规则 3）：预扣 stock/user 归 miaosha-service；
 * order-service 只负责结果回写与补偿（markSuccess/compensate/getResult），
 * 补偿经 Lua 校验 requestId 归属后才回补库存。
 *
 * <p>契约：三个方法均尽力而为、不得抛异常——Redis 回写/查询失败不能破坏已完成的
 * 数据库订单事实；result 丢失时的幂等由 DB 唯一键兜底（重复消息走业务失败补偿路径）。
 */
public interface OrderResultStore {

  /** DB 下单成功后回写结果 {@code SUCCESS:{orderId}}（尽力而为）。 */
  void markSuccess(Long goodsId, Long userId, Long orderId);

  /**
   * DB 下单失败后补偿：仅当用户标记仍是本次 requestId 时回补库存、清标记、记 FAILED
   * （Lua 原子，语义对齐 scripts/miaosha_compensate.lua）。
   */
  void compensate(Long goodsId, Long userId, String requestId);

  /**
   * 读取用户秒杀结果（幂等快跳 / 迟到重复判定用）。
   *
   * @return {@code PROCESSING} / {@code SUCCESS:{orderId}} / {@code FAILED}；
   *         无记录（未参与 / 已过期 / Redis 不可用）返回 {@code null}
   */
  String getResult(Long goodsId, Long userId);
}
