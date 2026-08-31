package com.example.miaosha.client;

import com.example.common.JwtUtil;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 商品数据 HTTP 客户端：GET goods-service {@code /goods/detail/{goodsId}}。
 *
 * <p>仅预热链路使用（受理热路径不查商品，F9：Redis 库存闸门先行返回 500214）。
 * goods-service JWT 全量拦截，故携带 Bearer 服务令牌（common 共享密钥签发的
 * 固定服务身份，按请求生成避免 24h 过期）。
 */
@Component
public class GoodsClient {

  /** 服务身份 userId：仅用于过 JWT 拦截，不对应真实用户。 */
  private static final long SERVICE_USER_ID = 0L;

  /** 跨服务 HTTP 调用限时，防止 goods-service 挂起拖垮管理线程。 */
  private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(2);
  private static final java.time.Duration READ_TIMEOUT = java.time.Duration.ofSeconds(3);

  private final RestClient restClient;

  public GoodsClient(@Value("${goods.base-url}") String goodsBaseUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
    requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
    this.restClient = RestClient.builder().baseUrl(goodsBaseUrl).requestFactory(requestFactory).build();
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
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtUtil.generateToken(SERVICE_USER_ID))
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
}
