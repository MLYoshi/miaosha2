package com.example.order.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品快照 DTO（order-service 本地副本，禁止跨服务 import goods-service 的 GoodsVo）。
 *
 * <p>只承载建单所需字段：订单表 {@code order_info.goods_name/goods_price} 存下单时快照，
 * 展示订单不再实时回查商品。多余字段由 goods-service 返回、Jackson 忽略。
 */
public class GoodsSnapshotVo {

  private Long id;
  private String goodsName;
  private BigDecimal miaoshaPrice;
  private LocalDateTime startDate;
  private LocalDateTime endDate;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getGoodsName() {
    return goodsName;
  }

  public void setGoodsName(String goodsName) {
    this.goodsName = goodsName;
  }

  public BigDecimal getMiaoshaPrice() {
    return miaoshaPrice;
  }

  public void setMiaoshaPrice(BigDecimal miaoshaPrice) {
    this.miaoshaPrice = miaoshaPrice;
  }

  public LocalDateTime getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDateTime startDate) {
    this.startDate = startDate;
  }

  public LocalDateTime getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDateTime endDate) {
    this.endDate = endDate;
  }
}
