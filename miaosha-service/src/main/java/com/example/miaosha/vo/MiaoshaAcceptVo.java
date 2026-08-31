package com.example.miaosha.vo;

/**
 * 秒杀受理响应（票 03 破坏性变化）：do_miaosha 不再返回订单详情。
 *
 * <ul>
 *   <li>{@link #PROCESSING}：预扣成功、消息已入队，前端轮询 result 拿单
 *   <li>{@link #SUCCESS}：降级同步落库（Kafka 发送失败 / Redis 不可用直连 DB），
 *       直接携带订单号
 * </ul>
 */
public class MiaoshaAcceptVo {

  /** 受理两态。 */
  public enum Status {
    /** 已受理排队中，订单异步落库，经轮询获取结果 */
    PROCESSING,
    /** 降级同步落库成功，直接携带订单号 */
    SUCCESS
  }

  private Status status;
  private Long orderId;

  public MiaoshaAcceptVo() {}

  public MiaoshaAcceptVo(Status status, Long orderId) {
    this.status = status;
    this.orderId = orderId;
  }

  /** 受理中（无订单号）。 */
  public static MiaoshaAcceptVo processing() {
    return new MiaoshaAcceptVo(Status.PROCESSING, null);
  }

  /** 降级同步落库成功，携带订单号。 */
  public static MiaoshaAcceptVo success(Long orderId) {
    return new MiaoshaAcceptVo(Status.SUCCESS, orderId);
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long orderId) {
    this.orderId = orderId;
  }
}
