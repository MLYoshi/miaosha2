package com.example.miaosha.client;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * {@link SyncOrderClient} 的 OpenFeign 实现（替换原 HttpSyncOrderClient 的手写 RestClient）：
 * POST order-service {@code /internal/orders/sync}（Step 5 端点）。
 *
 * <p>静态 url 复用既有 {@code order.sync-base-url} 配置：默认 {@code http://order-service}
 * 走 Nacos 服务名负载均衡；测试覆盖为死端口且 discovery 关闭时直连失败，行为与原实现一致。
 * 超时（connect 2s / read 3s）由 application.yml 的
 * {@code spring.cloud.openfeign.client.config.order-service} 配置。
 *
 * <p>约定：order-service 返回统一 {@code Result<Long>}（data 为订单号），HTTP 200 +
 * body 业务码（业务错误不走 HTTP 错误码，ErrorDecoder 不触发，故在 default 方法内显式还原）。
 * 业务码非 0 时按 common {@link CodeMsg} 还原异常语义（如 500212 重复秒杀），
 * 使降级失败的用户可见原因与单体一致；连接失败直接上抛 Feign 异常（RuntimeException），
 * 由受理编排补偿 Redis 后向上抛出。
 */
@FeignClient(name = "order-service", url = "${order.sync-base-url}")
public interface OrderFeignClient extends SyncOrderClient {

  /** Feign 声明式端点：请求体 JSON {userId, goodsId}，响应为 Result 同构壳。 */
  @PostMapping("/internal/orders/sync")
  SyncOrderResponse createOrderInternal(@RequestBody SyncOrderRequest request);

  /** 桥接接缝方法：发请求 + 业务码还原，语义与原 HttpSyncOrderClient 逐行等价。 */
  @Override
  default Long createOrder(Long userId, Long goodsId) {
    SyncOrderResponse resp = createOrderInternal(new SyncOrderRequest(userId, goodsId));

    if (resp == null || resp.getCode() != Result.SUCCESS_CODE || resp.getData() == null) {
      int code = resp == null ? CodeMsg.SERVER_ERROR.getCode() : resp.getCode();
      String msg = resp == null ? "order-service 无响应" : resp.getMsg();
      throw new MiaoshaException(resolveCodeMsg(code, msg));
    }
    return resp.getData();
  }

  /** 把 order-service 返回的业务码还原为 common CodeMsg（未知码按服务端异常处理）。 */
  private CodeMsg resolveCodeMsg(int code, String msg) {
    for (CodeMsg candidate : CodeMsg.values()) {
      if (candidate.getCode() == code) {
        return candidate;
      }
    }
    return CodeMsg.SERVER_ERROR;
  }

  /** 同步下单请求体（消息契约对齐 Kafka 消息字段：不含 requestId，幂等靠 DB 唯一键）。 */
  record SyncOrderRequest(Long userId, Long goodsId) {}

  /** order-service 统一响应壳（Result 同构，避免跨服务依赖）。 */
  class SyncOrderResponse {
    private int code;
    private String msg;
    private Long data;

    public int getCode() {
      return code;
    }

    public void setCode(int code) {
      this.code = code;
    }

    public String getMsg() {
      return msg;
    }

    public void setMsg(String msg) {
      this.msg = msg;
    }

    public Long getData() {
      return data;
    }

    public void setData(Long data) {
      this.data = data;
    }
  }
}
