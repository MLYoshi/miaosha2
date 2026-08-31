package com.example.goods.vo;

import com.example.goods.domain.Goods;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class GoodsVo extends Goods {

  private BigDecimal miaoshaPrice;
  private Integer stockCount;
  private LocalDateTime startDate;
  private LocalDateTime endDate;

  public BigDecimal getMiaoshaPrice() {
    return miaoshaPrice;
  }

  public void setMiaoshaPrice(BigDecimal miaoshaPrice) {
    this.miaoshaPrice = miaoshaPrice;
  }

  public Integer getStockCount() {
    return stockCount;
  }

  public void setStockCount(Integer stockCount) {
    this.stockCount = stockCount;
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
