package com.example.common;

public class MiaoshaException extends RuntimeException {

  private final CodeMsg codeMsg;

  public MiaoshaException(CodeMsg codeMsg) {
    super(codeMsg.getMsg());
    this.codeMsg = codeMsg;
  }

  public CodeMsg getCodeMsg() {
    return codeMsg;
  }
}
