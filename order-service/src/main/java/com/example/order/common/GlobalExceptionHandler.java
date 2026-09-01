package com.example.order.common;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（对齐 goods-service 模式）：
 *
 * <ul>
 *   <li>MethodArgumentNotValidException：@Valid 校验失败，返回 HTTP 400 + 友好错误信息</li>
 *   <li>MiaoshaException：业务异常，返回 HTTP 200 + Result.error(code, msg)，
 *       原码供调用方（如 HttpSyncOrderClient）还原异常语义</li>
 *   <li>Exception：兜底异常，返回 HTTP 200 + Result.error(SERVER_ERROR)，防止堆栈泄露</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 参数校验失败（@Valid 触发）：取第一个字段错误作为友好提示，返回 HTTP 400。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(f -> f.getField() + " 不能为空")
        .orElse("参数校验失败");
    log.warn("参数校验失败: {}", message);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Result.error(HttpStatus.BAD_REQUEST.value(), message));
  }

  /**
   * 业务异常：返回 HTTP 200 + 业务错误码（与现有 Result 约定一致，
   * 内部调用方按 code 还原 CodeMsg）。
   */
  @ExceptionHandler(MiaoshaException.class)
  public Result<Void> handleMiaoshaException(MiaoshaException ex) {
    CodeMsg codeMsg = ex.getCodeMsg();
    log.info("业务异常: code={}, msg={}", codeMsg.getCode(), codeMsg.getMsg());
    return Result.error(codeMsg.getCode(), codeMsg.getMsg());
  }

  /** 兜底异常处理，防止堆栈泄露。 */
  @ExceptionHandler(Exception.class)
  public Result<Void> handleException(Exception ex) {
    log.error("系统异常", ex);
    return Result.error(CodeMsg.SERVER_ERROR.getCode(), CodeMsg.SERVER_ERROR.getMsg());
  }
}
