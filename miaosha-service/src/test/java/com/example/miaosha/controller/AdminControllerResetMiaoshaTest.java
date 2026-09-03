package com.example.miaosha.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.common.Result;
import com.example.miaosha.client.GoodsClient;
import com.example.miaosha.service.MiaoshaPreheatService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.InOrder;

/**
 * 管理端重置秒杀接口的编排单测（POST /admin/goods/{goodsId}/miaosha）：
 *
 * <ul>
 *   <li>正常编排：先经 GoodsClient 让 goods-service 落库，再预热 Redis 库存
 *       （顺序不可颠倒——preheat 读取的是落库后的新配置），响应回显新配置</li>
 *   <li>落库失败：异常向上抛（由全局异常处理器转结构化错误），且不得触碰 Redis</li>
 * </ul>
 */
class AdminControllerResetMiaoshaTest {

  private GoodsClient goodsClient;
  private MiaoshaPreheatService preheatService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    goodsClient = mock(GoodsClient.class);
    preheatService = mock(MiaoshaPreheatService.class);
    controller = new AdminController(preheatService, goodsClient);
  }

  @Test // 正常编排：落库 → 预热（顺序断言）→ 回显新配置
  void resetMiaosha_dbFirstThenPreheat_returnsConfig() {
    LocalDateTime start = LocalDateTime.of(2026, 9, 3, 14, 0);
    LocalDateTime end = start.plusMinutes(60);
    GoodsClient.MiaoshaConfig config = new GoodsClient.MiaoshaConfig(1L, start, end, 100);
    BDDMockito.given(goodsClient.updateMiaoshaConfig(1L, 60, 100)).willReturn(config);

    Result<GoodsClient.MiaoshaConfig> result = controller.resetMiaosha(1L, 60, 100);

    assertThat(result.getCode()).as("编排成功返回 code=0").isZero();
    assertThat(result.getData()).as("回显 goods-service 落库后的新配置").isSameAs(config);

    InOrder inOrder = inOrder(goodsClient, preheatService);
    inOrder.verify(goodsClient).updateMiaoshaConfig(1L, 60, 100);
    inOrder.verify(preheatService).preheatStock(1L);
  }

  @Test // goods-service 落库失败：异常上抛，且不得预热 Redis（fail-fast）
  void dbUpdateFails_propagatesWithoutPreheat() {
    BDDMockito.given(goodsClient.updateMiaoshaConfig(1L, 60, 100))
        .willThrow(new MiaoshaException(CodeMsg.GOODS_NOT_EXIST));

    assertThatThrownBy(() -> controller.resetMiaosha(1L, 60, 100))
        .isInstanceOf(MiaoshaException.class)
        .hasMessage(CodeMsg.GOODS_NOT_EXIST.getMsg());

    verify(preheatService, never()).preheatStock(anyLong());
  }
}
