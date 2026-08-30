package com.example.common;

public enum CodeMsg {

  SUCCESS(0, "success"),
  SERVER_ERROR(500100, "服务端异常"),
  GOODS_NOT_EXIST(500104, "商品不存在"),
  MIAOSHA_STOCK_EMPTY(500214, "库存不足"),
  MIAOSHA_NOT_START(500215, "秒杀未开始"),
  MIAOSHA_OVER(500216, "秒杀已结束"),
  MIAOSHA_REPEAT(500212, "不能重复秒杀"),
  MOBILE_NOT_EXIST(500501, "手机号不存在"),
  PASSWORD_ERROR(500502, "密码错误"),
  MOBILE_ALREADY_EXIST(500503, "手机号已注册");

  private final int code;
  private final String msg;

  CodeMsg(int code, String msg) {
    this.code = code;
    this.msg = msg;
  }

  public int getCode() {
    return code;
  }

  public String getMsg() {
    return msg;
  }
}
