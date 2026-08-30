package com.example.seckill.vo;

public class GoodsDetailVo {

  private GoodsVo goods;
  private int miaoshaStatus;
  private int remainSeconds;

  public GoodsDetailVo() {}

  public GoodsDetailVo(GoodsVo goods, int miaoshaStatus, int remainSeconds) {
    this.goods = goods;
    this.miaoshaStatus = miaoshaStatus;
    this.remainSeconds = remainSeconds;
  }

  public GoodsVo getGoods() {
    return goods;
  }

  public void setGoods(GoodsVo goods) {
    this.goods = goods;
  }

  public int getMiaoshaStatus() {
    return miaoshaStatus;
  }

  public void setMiaoshaStatus(int miaoshaStatus) {
    this.miaoshaStatus = miaoshaStatus;
  }

  public int getRemainSeconds() {
    return remainSeconds;
  }

  public void setRemainSeconds(int remainSeconds) {
    this.remainSeconds = remainSeconds;
  }
}
