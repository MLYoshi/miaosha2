package com.example.order.client;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.common.Result;
import com.example.order.vo.GoodsSnapshotVo;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link GoodsClient} 的 HTTP 实现：调 goods-service {@code /internal/goods/**}。
 *
 * <p>对齐 miaosha-service {@code HttpSyncOrderClient} 的超时与响应壳模式：
 * connect 2s / read 3s 限时，防止 goods-service 挂起拖垮消费线程；
 * 业务码非 0 按 common {@link CodeMsg} 还原异常语义（如 500104 商品不存在）；
 * 500100（goods-service 兜底系统异常）在 {@code getGoodsVo} 上按意外异常（非
 * {@link MiaoshaException}）上抛——无副作用可安全重试（重试 → DLT），
 * {@code deductStock} 上保持 {@link MiaoshaException} fail-fast（扣减有二义性，补偿语义优先）；
 * 端点不存在 / 连接失败直接上抛连接类异常（消费路径走重试 → DLT）。
 */
@Component
public class HttpGoodsClient implements GoodsClient {

  /** 消费热路径上的跨服务 HTTP 调用，必须限时。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

  private static final Logger log = LoggerFactory.getLogger(HttpGoodsClient.class);

  private final RestClient restClient;

  public HttpGoodsClient(@Value("${goods.base-url}") String goodsBaseUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
    requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
    this.restClient =
        RestClient.builder().baseUrl(goodsBaseUrl).requestFactory(requestFactory).build();
  }

  @Override
  public GoodsSnapshotVo getGoodsVo(Long goodsId) {
    GoodsResult resp =
        restClient
            .get()
            .uri("/internal/goods/{goodsId}", goodsId)
            .retrieve()
            .body(GoodsResult.class);

    if (resp == null || resp.getCode() != Result.SUCCESS_CODE) {
      int code = resp == null ? CodeMsg.SERVER_ERROR.getCode() : resp.getCode();
      if (code == CodeMsg.GOODS_NOT_EXIST.getCode()) {
        // 商品不存在：按接缝契约返回 null，由 OrderService 抛 GOODS_NOT_EXIST
        return null;
      }
      String msg = resp == null ? "goods-service 无响应" : resp.getMsg();
      if (resp == null || code == CodeMsg.SERVER_ERROR.getCode()) {
        // 500100（goods-service 兜底系统异常）：getGoodsVo 无副作用，重试绝对安全，
        // 必须按「意外异常」上抛（消费路径重试 → DLT），
        // 绝不能还原为 MiaoshaException 业务失败——否则瞬时故障期间在途消息被补偿 + ack 一次性永久化
        throw new IllegalStateException(
            "goods-service 系统异常，消费路径应重试: code=" + code + ", msg=" + msg);
      }
      throw new MiaoshaException(resolveCodeMsg(code, msg));
    }
    if (resp.getData() == null) {
      // code=0 但 data=null：响应壳成功而载荷缺失，属序列化/契约故障。
      // 绝不能按接缝契约返回 null——会被 OrderService 误判为「商品不存在」，
      // 掩盖真实故障。该场景无副作用，按意外异常上抛可安全重试（重试 → DLT）。
      throw new IllegalStateException(
          "goods-service 成功响应但商品快照缺失（契约故障）: goodsId=" + goodsId);
    }
    return resp.getData();
  }

  @Override
  public int deductStock(Long goodsId, String requestId) {
    RowsResult resp =
        restClient
            .post()
            // requestId 作为 query param 下发（可为 null）：goods-service 以其做短期幂等，
            // 响应丢失 → Kafka 重放同一 requestId 时不重复扣减（review report Issue 1）
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/internal/goods/{goodsId}/deduct-stock")
                        .queryParamIfPresent("requestId", Optional.ofNullable(requestId))
                        .build(goodsId))
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(RowsResult.class);

    if (resp == null || resp.getCode() != Result.SUCCESS_CODE) {
      int code = resp == null ? CodeMsg.SERVER_ERROR.getCode() : resp.getCode();
      String msg = resp == null ? "goods-service 无响应" : resp.getMsg();
      throw new MiaoshaException(resolveCodeMsg(code, msg));
    }
    Integer rows = resp.getData();
    return rows == null ? 0 : rows;
  }

  @Override
  public void restoreStock(Long goodsId) {
    RowsResult resp =
        restClient
            .post()
            .uri("/internal/goods/{goodsId}/restore-stock", goodsId)
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(RowsResult.class);

    if (resp == null || resp.getCode() != Result.SUCCESS_CODE) {
      int code = resp == null ? CodeMsg.SERVER_ERROR.getCode() : resp.getCode();
      String msg = resp == null ? "goods-service 无响应" : resp.getMsg();
      throw new MiaoshaException(resolveCodeMsg(code, msg));
    }
  }

  /** 把 goods-service 返回的业务码还原为 common CodeMsg（未知码按服务端异常处理）。 */
  private CodeMsg resolveCodeMsg(int code, String msg) {
    for (CodeMsg candidate : CodeMsg.values()) {
      if (candidate.getCode() == code) {
        return candidate;
      }
    }
    // 未知业务码：还原为 SERVER_ERROR 之前必须保真记录原始 code + msg，
    // 否则 goods-service 侧真实错误信息（如 SQL 异常摘要）被静态文案吞掉，无法跨服务排障。
    log.warn("goods-service 返回未知业务码，按 SERVER_ERROR 处理: code={}, msg={}", code, msg);
    return CodeMsg.SERVER_ERROR;
  }

  /** goods-service 统一响应壳（Result 同构，避免跨服务依赖），data 为商品快照。 */
  static class GoodsResult {
    private int code;
    private String msg;
    private GoodsSnapshotVo data;

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

    public GoodsSnapshotVo getData() {
      return data;
    }

    public void setData(GoodsSnapshotVo data) {
      this.data = data;
    }
  }

  /** goods-service 统一响应壳，data 为影响行数（deduct-stock）/ 无值（restore-stock）。 */
  static class RowsResult {
    private int code;
    private String msg;
    private Integer data;

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

    public Integer getData() {
      return data;
    }

    public void setData(Integer data) {
      this.data = data;
    }
  }
}
