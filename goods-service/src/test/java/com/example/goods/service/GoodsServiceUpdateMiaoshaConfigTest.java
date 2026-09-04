package com.example.goods.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.goods.dao.GoodsMapper;
import com.example.goods.vo.GoodsVo;
import com.example.goods.vo.MiaoshaConfigVo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 重置秒杀配置单测（管理端接口 POST /admin/goods/{goodsId}/miaosha 的落库环节，
 * 由 miaosha-service 经 POST /internal/goods/{goodsId}/miaosha-config 回调本服务）：
 * 正常重置（含/不含库存）、参数非法、商品不存在。时钟用固定 Clock，窗口断言精确到秒。
 */
class GoodsServiceUpdateMiaoshaConfigTest {

  /** 固定时钟：2026-09-03T06:00:00Z = 北京时间 14:00:00（同时验证 Clock 的时区语义）。 */
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 14, 0, 0);

  private GoodsMapper goodsMapper;
  private GoodsService goodsService;

  @BeforeEach
  void setUp() {
    goodsMapper = mock(GoodsMapper.class);
    Clock fixed = Clock.fixed(Instant.parse("2026-09-03T06:00:00Z"), ZoneId.of("Asia/Shanghai"));
    goodsService =
        new GoodsService(
            goodsMapper, mock(MiaoshaWindowService.class), mock(StringRedisTemplate.class), fixed);
  }

  private GoodsVo existingGoods(int stock) {
    GoodsVo goods = new GoodsVo();
    goods.setStockCount(stock);
    return goods;
  }

  @Test // 正常重置：窗口精确对齐 + 库存重置一并落库
  void resetWithStock_updatesWindowAndStock() {
    BDDMockito.given(goodsMapper.getGoodsVo(2L)).willReturn(existingGoods(50));
    BDDMockito.given(goodsMapper.updateMiaoshaConfig(2L, NOW, NOW.plusMinutes(60), 100))
        .willReturn(1);

    MiaoshaConfigVo vo = goodsService.updateMiaoshaConfig(2L, 60, 100);

    assertThat(vo.goodsId()).isEqualTo(2L);
    assertThat(vo.startDate()).as("start 必须精确等于时钟当前时刻").isEqualTo(NOW);
    assertThat(vo.endDate()).as("end = start + durationMinutes").isEqualTo(NOW.plusMinutes(60));
    assertThat(vo.stockCount()).isEqualTo(100);
    verify(goodsMapper).updateMiaoshaConfig(2L, NOW, NOW.plusMinutes(60), 100);
  }

  @Test // 缺省 stockCount：只对齐时间窗，库存列不更新，回显现有库存
  void resetWithoutStock_updatesWindowOnly() {
    BDDMockito.given(goodsMapper.getGoodsVo(2L)).willReturn(existingGoods(50));
    BDDMockito.given(goodsMapper.updateMiaoshaConfig(2L, NOW, NOW.plusMinutes(30), null))
        .willReturn(1);

    MiaoshaConfigVo vo = goodsService.updateMiaoshaConfig(2L, 30, null);

    assertThat(vo.stockCount()).as("未传 stockCount 时回显现有库存").isEqualTo(50);
    verify(goodsMapper).updateMiaoshaConfig(eq(2L), eq(NOW), eq(NOW.plusMinutes(30)), isNull());
  }

  @Test // durationMinutes = 0：非法，不触碰 DB
  void zeroDuration_paramError() {
    assertThatThrownBy(() -> goodsService.updateMiaoshaConfig(2L, 0, 100))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CodeMsg.PARAM_ERROR.getCode()));
    verify(goodsMapper, never()).updateMiaoshaConfig(any(), any(), any(), any());
  }

  @Test // stockCount 为负：非法，不触碰 DB
  void negativeStock_paramError() {
    assertThatThrownBy(() -> goodsService.updateMiaoshaConfig(2L, 60, -1))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CodeMsg.PARAM_ERROR.getCode()));
    verify(goodsMapper, never()).updateMiaoshaConfig(any(), any(), any(), any());
  }

  @Test // 商品不存在：GOODS_NOT_EXIST
  void goodsMissing_goodsNotExist() {
    BDDMockito.given(goodsMapper.getGoodsVo(2L)).willReturn(null);

    assertThatThrownBy(() -> goodsService.updateMiaoshaConfig(2L, 60, 100))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CodeMsg.GOODS_NOT_EXIST.getCode()));
    verify(goodsMapper, never()).updateMiaoshaConfig(any(), any(), any(), any());
  }

  @Test // 查询与更新间隙被删除（影响行数 0）：并发兜底 GOODS_NOT_EXIST
  void updateAffectsNoRows_goodsNotExist() {
    BDDMockito.given(goodsMapper.getGoodsVo(2L)).willReturn(existingGoods(50));
    BDDMockito.given(goodsMapper.updateMiaoshaConfig(2L, NOW, NOW.plusMinutes(60), 100))
        .willReturn(0);

    assertThatThrownBy(() -> goodsService.updateMiaoshaConfig(2L, 60, 100))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CodeMsg.GOODS_NOT_EXIST.getCode()));
  }
}
