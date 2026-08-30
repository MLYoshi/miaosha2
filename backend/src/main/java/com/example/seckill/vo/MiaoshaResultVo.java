package com.example.seckill.vo;

/**
 * 秒杀结果轮询响应：四态可判别。
 *
 * <p>状态语义与 Redis 结果契约对齐（{@code PROCESSING} / {@code SUCCESS:{orderId}} /
 * {@code FAILED}），另设 {@link #NONE} 表示用户未参与过该商品秒杀。
 */
public class MiaoshaResultVo {

  /** 结果四态。 */
  public enum Status {
    /** 已受理，排队处理中 */
    PROCESSING,
    /** 抢购成功，携带订单号 */
    SUCCESS,
    /** 抢购失败（泛化失败态，不携带原因码） */
    FAILED,
    /** 无记录：用户未参与过该商品秒杀 */
    NONE
  }

  private Status status;
  private Long orderId;

  public MiaoshaResultVo() {}

  public MiaoshaResultVo(Status status, Long orderId) {
    this.status = status;
    this.orderId = orderId;
  }

  public static MiaoshaResultVo of(Status status) {
    return new MiaoshaResultVo(status, null);
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
