package com.example.miaosha.client;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import java.time.LocalDateTime;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 商品数据 OpenFeign 客户端（替换原手写 RestClient 实现）：
 * GET goods-service {@code /goods/detail/{goodsId}} 与
 * POST {@code /internal/goods/{goodsId}/miaosha-config}。
 *
 * <p>静态 url 复用既有 {@code goods.base-url} 配置；超时（connect 2s / read 3s）由
 * application.yml 的 {@code spring.cloud.openfeign.client.config.goods-service} 配置。
 * goods-service 全量鉴权拦截，detail 端点携带 X-User-Id: 0 服务身份头（不对应真实用户）；
 * {@code /internal/**} 无需鉴权头（沿用原实现，带同一头无副作用）。
 *
 * <p>响应壳为手写的 Result 同构 DTO，禁止 import 其他服务的 Entity/VO。
 */
@FeignClient(name = "goods-service", url = "${goods.base-url}", contextId = "goodsClient")
public interface GoodsClient {

  /** 服务身份 userId：仅用于过鉴权拦截，不对应真实用户。 */
  long SERVICE_USER_ID = 0L;

  /** Feign 声明式端点：detail 响应壳。 */
  @GetMapping("/goods/detail/{goodsId}")
  GoodsDetailResponse getGoodsDetailInternal(
      @PathVariable("goodsId") Long goodsId, @RequestHeader("X-User-Id") long userId);

  /**
   * 取商品的库存与秒杀结束时间（预热所需的最小字段集）。
   *
   * @return 商品快照；商品或秒杀配置缺失（detail 对应 data 为空）返回 {@code null}
   * @throws RuntimeException 连接类异常向上抛（FeignException），由调用方决定处理方式
   */
  default GoodsSnapshot getGoodsSnapshot(Long goodsId) {
    GoodsDetailResponse resp = getGoodsDetailInternal(goodsId, SERVICE_USER_ID);

    if (resp == null || resp.getData() == null || resp.getData().getGoods() == null) {
      return null;
    }
    GoodsVoSnapshot goods = resp.getData().getGoods();
    return new GoodsSnapshot(goods.getStockCount(), goods.getEndDate());
  }

  /** Feign 声明式端点：重置秒杀配置（stockCount 可选，仅非空时携带）。 */
  @PostMapping("/internal/goods/{goodsId}/miaosha-config")
  MiaoshaConfigResponse updateMiaoshaConfigInternal(
      @PathVariable("goodsId") Long goodsId,
      @RequestParam("durationMinutes") long durationMinutes,
      @RequestParam(value = "stockCount", required = false) Integer stockCount,
      @RequestHeader("X-User-Id") long userId);

  /**
   * 重置秒杀配置（管理接口专用）：调 goods-service 内部接口落库时间窗/库存。
   *
   * @throws RuntimeException 连接类异常向上抛
   * @throws MiaoshaException goods-service 返回业务错误（商品不存在/参数非法）时转成本地异常
   */
  default MiaoshaConfig updateMiaoshaConfig(Long goodsId, long durationMinutes, Integer stockCount) {
    MiaoshaConfigResponse resp =
        updateMiaoshaConfigInternal(goodsId, durationMinutes, stockCount, SERVICE_USER_ID);

    if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
      // goods-service 的全局异常处理器把 MiaoshaException 转成了 Result 错误码，这里还原成本地异常
      throw new MiaoshaException(CodeMsg.SERVER_ERROR);
    }
    return resp.getData();
  }

  /** 预热所需的商品快照。 */
  record GoodsSnapshot(Integer stockCount, LocalDateTime endDate) {}

  /** 重置后的秒杀配置（与 goods-service MiaoshaConfigVo 的 JSON 字段对齐，不直接依赖对方类）。 */
  record MiaoshaConfig(
      Long goodsId, LocalDateTime startDate, LocalDateTime endDate, Integer stockCount) {}

  /** detail 响应 data 内的商品字段（只声明预热用到的字段，避免跨服务依赖 GoodsVo）。 */
  class GoodsVoSnapshot {
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
  class GoodsDetailData {
    private GoodsVoSnapshot goods;

    public GoodsVoSnapshot getGoods() {
      return goods;
    }

    public void setGoods(GoodsVoSnapshot goods) {
      this.goods = goods;
    }
  }

  /** goods-service 统一响应壳（Result 同构，避免跨服务依赖）。 */
  class GoodsDetailResponse {
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

  /** 内部接口响应壳（Result 同构）。 */
  class MiaoshaConfigResponse {
    private int code;
    private String msg;
    private MiaoshaConfig data;

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

    public MiaoshaConfig getData() {
      return data;
    }

    public void setData(MiaoshaConfig data) {
      this.data = data;
    }
  }
}
