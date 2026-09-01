package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.example.common.MiaoshaException;
import com.example.order.domain.OrderInfo;
import com.example.order.support.AbstractOrderIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 落库核心集成测试（Testcontainers MySQL，真实 MyBatis 两表 INSERT）：
 *
 * <ul>
 *   <li>正常下单：快照语义（goods_name/goods_price 存下单时快照）+ 两表落库</li>
 *   <li>重复下单（场景 2）：幂等预检在扣库存之前拦下，不重复扣减、无补偿</li>
 *   <li>库存不足（场景 3）：MIAOSHA_STOCK_EMPTY，不建单、无补偿</li>
 *   <li>建单失败（场景 4）：先回补库存再上抛，补偿恰好一次</li>
 *   <li>唯一键兜底：UNIQUE(user_id, goods_id) 冲突以 DuplicateKeyException 形态抛出</li>
 *   <li>时间窗边界语义与全系统唯一定义处一致（含端点规则）</li>
 * </ul>
 */
class OrderServiceIntegrationTest extends AbstractOrderIntegrationTest {

  private static final long USER_ID = 11001L;

  @Autowired private OrderService orderService;

  @Test
  void createOrder_persistsSnapshotOrderInTwoTables() {
    long goodsId = 91001L;
    stubGoodsClient(snapshot(goodsId), 10);

    OrderInfo order = orderService.createOrder(USER_ID, goodsId);

    assertThat(order.getId()).as("应拿到 order_info 自增 id").isNotNull();

    Map<String, Object> row = orderInfoRow(order.getId());
    assertThat(row.get("user_id")).isEqualTo(USER_ID);
    assertThat(row.get("goods_id")).isEqualTo((Object) goodsId);
    assertThat(row.get("goods_name")).isEqualTo("测试商品-" + goodsId);
    assertThat(row.get("goods_count")).isEqualTo((Object) 1);
    assertThat(((BigDecimal) row.get("goods_price")).compareTo(new BigDecimal("99.00"))).isZero();
    assertThat(row.get("order_channel")).isEqualTo((Object) 1);
    assertThat(row.get("status")).isEqualTo((Object) 0);
    assertThat(row.get("create_date")).as("create_date 应落库").isNotNull();

    assertThat(orderIdOf(USER_ID, goodsId)).isEqualTo(order.getId());
    assertThat(deductCalls.get()).isEqualTo(1);
    assertThat(restoreCalls.get()).isZero();
  }

  @Test // 场景 2：重复消息在扣库存之前被幂等预检拦下
  void duplicateOrder_precheckBlocksBeforeDeduct() {
    long goodsId = 91002L;
    stubGoodsClient(snapshot(goodsId), 10);

    orderService.createOrder(USER_ID, goodsId);
    assertThatThrownBy(() -> orderService.createOrder(USER_ID, goodsId))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CODE_MIAOSHA_REPEAT));

    assertThat(deductCalls.get()).as("重复下单不得再次扣库存").isEqualTo(1);
    assertThat(restoreCalls.get()).as("预检拦截不触发库存回补").isZero();
    assertThat(countMiaoshaOrder(goodsId)).isEqualTo(1);
    assertThat(countOrderInfo(goodsId)).isEqualTo(1);
  }

  @Test // 场景 3：库存不足（条件扣减影响行数 0）→ MIAOSHA_STOCK_EMPTY，不建单
  void stockEmpty_noOrderNoCompensation() {
    long goodsId = 91003L;
    stubGoodsClient(snapshot(goodsId), 0);

    assertThatThrownBy(() -> orderService.createOrder(USER_ID, goodsId))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CODE_MIAOSHA_STOCK_EMPTY));

    assertThat(deductCalls.get()).isEqualTo(1);
    assertThat(restoreCalls.get()).as("库存不足不是建单失败，不回补").isZero();
    assertThat(countOrderInfo(goodsId)).isZero();
    assertThat(countMiaoshaOrder(goodsId)).isZero();
  }

  @Test // 场景 4：建单失败（goods_name 超长触发 DB 约束）→ 先回补库存再上抛，补偿恰好一次
  void insertFailure_compensatesStockExactlyOnceAndRethrows() {
    long goodsId = 91004L;
    // goods_name VARCHAR(16)，20 个字符触发约束异常 → 走「其他异常先补偿再上抛」分支
    stubGoodsClient(snapshot(goodsId, "A".repeat(20), null, null), 10);

    assertThatThrownBy(() -> orderService.createOrder(USER_ID, goodsId))
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(MiaoshaException.class)
        .as("非唯一键冲突的建单失败应按意外异常上抛（走重试 → DLT）");

    assertThat(deductCalls.get()).isEqualTo(1);
    assertThat(restoreCalls.get()).as("Saga 补偿：建单失败必须回补库存且恰好一次").isEqualTo(1);
    assertThat(countOrderInfo(goodsId)).as("建单失败不得留下订单").isZero();
    assertThat(countMiaoshaOrder(goodsId)).isZero();
  }

  @Test // 唯一键兜底：预检错过（并发竞态）时 UNIQUE(user_id, goods_id) 以 DuplicateKeyException 抛出
  void uniqueKeyFallback_throwsDuplicateKeyException() {
    long goodsId = 91005L;
    stubGoodsClient(snapshot(goodsId), 10);

    OrderInfo first = orderService.createOrder(USER_ID, goodsId);
    assertThat(first.getId()).isNotNull();

    // 模拟预检与 INSERT 之间其他节点已建单：直接二次走两表 INSERT 事务
    assertThatThrownBy(() -> orderService.insertOrderTx(USER_ID, goodsId, snapshot(goodsId)))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(countMiaoshaOrder(goodsId)).as("唯一键兜底：仍只有一条秒杀单").isEqualTo(1);
    assertThat(countOrderInfo(goodsId)).as("冲突事务应整体回滚，不残留 order_info").isEqualTo(1);
  }

  @Test // 时间窗边界语义：now == endDate 视为已结束（结束边界含端点）
  void windowBoundary_endDateInclusive_isOver() {
    long goodsId = 91006L;
    stubGoodsClient(
        snapshot(goodsId, "测试商品-" + goodsId, LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusNanos(1)),
        10);

    assertThatThrownBy(() -> orderService.createOrder(USER_ID, goodsId))
        .isInstanceOfSatisfying(
            MiaoshaException.class,
            e -> assertThat(e.getCodeMsg().getCode()).isEqualTo(CODE_MIAOSHA_OVER));

    assertThat(deductCalls.get()).as("窗外下单不触碰库存").isZero();
    assertThat(countMiaoshaOrder(goodsId)).isZero();
  }

  @Test // 时间窗边界语义：startDate == null 立即开始、endDate == null 永不过期
  void windowBoundary_nullBounds_alwaysInWindow() {
    long goodsId = 91007L;
    stubGoodsClient(snapshot(goodsId, "测试商品-" + goodsId, null, null), 10);

    OrderInfo order = orderService.createOrder(USER_ID, goodsId);
    assertThat(order.getId()).isNotNull();
  }

  @Test // Issue 1 基础语义：deductStock 连接类异常 → 意外异常上抛、不回补（二义性）、无订单
  void deductStockConnectionFailure_rethrowsWithoutRestoreOrOrder() {
    long goodsId = 91008L;
    stubGoodsClientThrowing(snapshot(goodsId), new ResourceAccessException("read timeout"));

    assertThatThrownBy(() -> orderService.createOrder(USER_ID, goodsId))
        .as("扣减二义性：连接异常必须按意外异常上抛（消费路径不 ack → 重试 → DLT）")
        .isInstanceOf(ResourceAccessException.class);

    assertThat(deductCalls.get()).isEqualTo(1);
    assertThat(restoreCalls.get())
        .as("响应丢失时 goods-service 可能已扣减，回补会超卖 → 必须不回补")
        .isZero();
    assertThat(countOrderInfo(goodsId)).as("扣减结果二义时不建单").isZero();
    assertThat(countMiaoshaOrder(goodsId)).isZero();
  }


  @Test // Issue 1 修复验证：扣减响应丢失 → 消息重放携带同一 requestId → 1 个订单恰好 1 次 DB 扣减
  void deductAmbiguity_oneOrderExactlyOneDeduct_expectedBehavior() {
    long goodsId = 91010L;
    String requestId = "req-issue1-" + goodsId;
    snapshotCalls = new AtomicInteger();
    deductCalls = new AtomicInteger();
    deductSuccessCalls = new AtomicInteger();
    restoreCalls = new AtomicInteger();
    given(goodsClient.getGoodsVo(any())).willReturn(snapshot(goodsId));
    AtomicInteger attempts = new AtomicInteger();
    // 模拟带 SETNX 短期幂等的 goods-service（TTL 60s 缓存影响行数）：
    // 同一 requestId 的重放命中幂等缓存 → 返回上次影响行数，不再真实扣减（deductCalls 不增加）。
    // 若 OrderService 未把 requestId 传到扣减请求（传 null / 每次换新），此处退化为真实扣减 → 失败。
    Set<String> seenRequestIds = ConcurrentHashMap.newKeySet();
    given(goodsClient.deductStock(any(), any()))
        .willAnswer(
            inv -> {
              String reqId = inv.getArgument(1);
              if (reqId != null && !seenRequestIds.add(reqId)) {
                return 1;
              }
              deductCalls.incrementAndGet();
              if (attempts.getAndIncrement() == 0) {
                throw new ResourceAccessException("read timeout");
              }
              return 1;
            });

    // 第一次消费：扣减请求已到 goods-service（DB 已扣）但响应丢失 → 二义性异常上抛（不回补）
    assertThatThrownBy(() -> orderService.createOrder(USER_ID, goodsId, requestId))
        .isInstanceOf(ResourceAccessException.class);
    // Kafka 1s 后重放同一消息（同一 requestId）→ 幂等命中 → 建单成功
    OrderInfo order = orderService.createOrder(USER_ID, goodsId, requestId);
    assertThat(order.getId()).isNotNull();
    assertThat(countMiaoshaOrder(goodsId)).isEqualTo(1);

    assertThat(deductCalls.get())
        .as("Issue 1 已修复：1 个订单 ↔ 1 次 DB 扣减（requestId + goods-service SETNX 幂等）")
        .isEqualTo(1);
  }
}
