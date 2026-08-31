package com.example.miaosha.message;

/**
 * 下单请求消息体：经 Kafka {@code seckill-order} topic 传输的下单请求载荷。
 *
 * <p>承载一次秒杀下单所需的最小信息：谁（userId）、买什么（goodsId）、
 * 哪次请求（requestId，全链路去重标识）。本票仅定义并打通收发通道，
 * 业务消费编排在票 03 落地。
 */
public class SeckillOrderMessage {

  private Long userId;
  private Long goodsId;
  private String requestId;

  public SeckillOrderMessage() {}

  public SeckillOrderMessage(Long userId, Long goodsId, String requestId) {
    this.userId = userId;
    this.goodsId = goodsId;
    this.requestId = requestId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getGoodsId() {
    return goodsId;
  }

  public void setGoodsId(Long goodsId) {
    this.goodsId = goodsId;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }
}
