package com.example.order.message;

/**
 * 下单请求消息体（消费侧副本）：经 Kafka {@code seckill-order} topic 传输的下单请求载荷。
 *
 * <p>与 producer 侧（miaosha-service）消息契约一致：纯 JSON、无类型头，
 * 字段为 userId / goodsId / requestId。反序列化目标类型由
 * {@code spring.json.value.default.type} 指定为本类（见 application.yml）。
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
