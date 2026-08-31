package com.example.miaosha.client;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.common.Result;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link SyncOrderClient} 的 HTTP 实现：POST order-service {@code /internal/orders/sync}
 * （Step 5 端点，Step 4 未就绪）。
 *
 * <p>约定：order-service 返回统一 {@code Result<Long>}（data 为订单号）。
 * 业务码非 0 时按 common {@link CodeMsg} 还原异常语义（如 500212 重复秒杀），
 * 使降级失败的用户可见原因与单体一致；端点不存在 / 连接失败直接上抛连接类异常，
 * 由受理编排补偿 Redis 后向上抛出。
 */
@Component
public class HttpSyncOrderClient implements SyncOrderClient {

  /** 降级路径是跨服务 HTTP 调用（基线为本地 DB），必须限时，防止挂起拖垮工作线程。 */
  private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(2);
  private static final java.time.Duration READ_TIMEOUT = java.time.Duration.ofSeconds(3);

  private final RestClient restClient;

  public HttpSyncOrderClient(@Value("${order.sync-base-url}") String syncBaseUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
    requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
    this.restClient = RestClient.builder().baseUrl(syncBaseUrl).requestFactory(requestFactory).build();
  }

  @Override
  public Long createOrder(Long userId, Long goodsId) {
    SyncOrderRequest body = new SyncOrderRequest(userId, goodsId);
    SyncOrderResponse resp =
        restClient
            .post()
            .uri("/internal/orders/sync")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(SyncOrderResponse.class);

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
  static class SyncOrderResponse {
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
