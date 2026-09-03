package com.example.miaosha.client;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;

/**
 * 商品数据 HTTP 客户端：GET goods-service {@code /goods/detail/{goodsId}}。
 *
 * <p>仅预热链路使用（受理热路径不查商品，F9：Redis 库存闸门先行返回 500214）。
 * goods-service 全量鉴权拦截，故携带 X-User-Id: 0 服务身份头（不对应真实用户）。
 */
@Component
public class GoodsClient {

  /** 服务身份 userId：仅用于过鉴权拦截，不对应真实用户。 */
  private static final long SERVICE_USER_ID = 0L;

  /** 跨服务 HTTP 调用限时，防止 goods-service 挂起拖垮管理线程。 */
  private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(2);
  private static final java.time.Duration READ_TIMEOUT = java.time.Duration.ofSeconds(3);

  private final RestClient restClient;

  public GoodsClient(@LoadBalanced RestClient.Builder builder,
                     @Value("${goods.base-url}") String goodsBaseUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
    requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
    this.restClient = builder.baseUrl(goodsBaseUrl).requestFactory(requestFactory).build();
  }

  /**
   * 取商品的库存与秒杀结束时间（预热所需的最小字段集）。
   *
   * @return 商品快照；商品或秒杀配置缺失（detail 对应 data 为空）返回 {@code null}
   * @throws RestClientException 连接类异常向上抛，由调用方决定处理方式
   */
  public GoodsSnapshot getGoodsSnapshot(Long goodsId) {
    GoodsDetailResponse resp =
        restClient
            .get()
            .uri("/goods/detail/{goodsId}", goodsId)
            .header("X-User-Id", String.valueOf(SERVICE_USER_ID))
            .retrieve()
            .body(GoodsDetailResponse.class);

    if (resp == null || resp.getData() == null || resp.getData().getGoods() == null) {
      return null;
    }
    GoodsVoSnapshot goods = resp.getData().getGoods();
    return new GoodsSnapshot(goods.getStockCount(), goods.getEndDate());
  }

  /** 预热所需的商品快照。 */
  public record GoodsSnapshot(Integer stockCount, LocalDateTime endDate) {}

  /** detail 响应 data 内的商品字段（只声明预热用到的字段，避免跨服务依赖 GoodsVo）。 */
  static class GoodsVoSnapshot {
    private Integer stockCount;
    private LocalDateTime endDate;

    public Integer getStockCount() {
      return stockCount;
    }

    public void setStockCount(Integer stockCount) {
      this.stockCount = stockCount;
    }

    public LocalDateTime getEndDate() {
      return endDate;
    }

    public void setEndDate(LocalDateTime endDate) {
      this.endDate = endDate;
    }
  }

  /** detail 响应 data。 */
  static class GoodsDetailData {
    private GoodsVoSnapshot goods;

    public GoodsVoSnapshot getGoods() {
      return goods;
    }

    public void setGoods(GoodsVoSnapshot goods) {
      this.goods = goods;
    }
  }

  /** goods-service 统一响应壳（Result 同构，避免跨服务依赖）。 */
  static class GoodsDetailResponse {
    private int code;
    private String msg;
    private GoodsDetailData data;

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

    public GoodsDetailData getData() {
      return data;
    }

    public void setData(GoodsDetailData data) {
      this.data = data;
    }
  }

    /**
   * 重置秒杀配置（管理接口专用）：调 goods-service 内部接口落库时间窗/库存。
   * 携带 X-User-Id: 0 服务身份头，与 getGoodsSnapshot 一致。
   *
   * @throws RestClientException 连接类异常向上抛
   * @throws MiaoshaException goods-service 返回业务错误（商品不存在/参数非法）时还原
   */
  public MiaoshaConfig updateMiaoshaConfig(Long goodsId, long durationMinutes, Integer stockCount) {
    MiaoshaConfigResponse resp =
        restClient
            .post()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/internal/goods/{goodsId}/miaosha-config")
                        .queryParam("durationMinutes", durationMinutes)
                        .queryParamIfPresent("stockCount", Optional.ofNullable(stockCount))
                        .build(goodsId))
            .header("X-User-Id", String.valueOf(SERVICE_USER_ID))
            .retrieve()
            .body(MiaoshaConfigResponse.class);

    if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
      // goods-service 的全局异常处理器把 MiaoshaException 转成了 Result 错误码，这里还原成本地异常
      throw new MiaoshaException(CodeMsg.SERVER_ERROR);
    }
    return resp.getData();
  }

  /** 重置后的秒杀配置（与 goods-service MiaoshaConfigVo 的 JSON 字段对齐，不直接依赖对方类）。 */
  public record MiaoshaConfig(
      Long goodsId, LocalDateTime startDate, LocalDateTime endDate, Integer stockCount) {}

  /** 内部接口响应壳（Result 同构）。 */
  static class MiaoshaConfigResponse {
    private int code;
    private String msg;
    private MiaoshaConfig data;

    public int getCode() {
      return code;
    }

    public String getMsg() {
      return msg;
    }

    public MiaoshaConfig getData() {
      return data;
    }

    public void setCode(int code) {
      this.code = code;
    }

    public void setMsg(String msg) {
      this.msg = msg;
    }

    public void setData(MiaoshaConfig data) {
      this.data = data;
    }
  }

}
