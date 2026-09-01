package com.example.order.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.cache.RedisKeyBuilder;
import com.example.order.support.AbstractOrderIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 并发验收（对齐 Step 5 计划 §验收）：1000 用户 / 100 库存齐射下单消息，
 * 消费全链路（fulfill → createOrder → 两表落库 → markSuccess）后的最终一致性。
 *
 * <p>验收口径（落库侧）：
 *
 * <ul>
 *   <li>最终订单数 ≤ 100（1000 用户互不相同、库存 100 → 恰好 100 单）</li>
 *   <li>库存 ≥ 0（CAS 条件扣减，成功扣减恰好 100 次）、无重复成功订单</li>
 *   <li>Redis SUCCESS 结果数 == 100；孤儿订单为 0</li>
 *   <li>order-service 不私改预扣库存：无 requestId 归属的 compensate 不得 INCR 库存</li>
 *   <li>幂等重放：100 条成功消息重复投递后，订单数与成功扣减次数均不变</li>
 * </ul>
 *
 * <p>消费完成判定：首批每条消息都经过 getGoodsVo（无结果标记，不可能快跳），
 * snapshotCalls == 1000 即全部消费完；重放批次只重放 DB 中真实的 100 个成功用户
 * （全部走 Redis 快跳），同分区内末尾追加一个哨兵消息（唯一调用 getGoodsVo 的消息），
 * 快照计数到达 1001 即整个重放批次按分区内顺序消费完毕。
 */
class OrderConcurrencyAcceptanceTest extends AbstractOrderIntegrationTest {

  private static final long GOODS_ID = 98001L;
  private static final int USERS = 1000;
  private static final int STOCK = 100;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void volley1000users100stock_finalConsistencyAndIdempotentReplay() {
    stubGoodsClient(snapshot(GOODS_ID), STOCK);
    // 预置 Redis 预扣库存（模拟 miaosha-service 已预扣），验证消费侧不私改预扣库存
    redisTemplate.opsForValue().set(RedisKeyBuilder.stock(GOODS_ID), "100", Duration.ofHours(1));

    List<Long> userIds = new ArrayList<>();
    for (int i = 0; i < USERS; i++) {
      userIds.add(40000100000L + i);
    }
    for (int i = 0; i < USERS; i++) {
      sendOrderMessage(userIds.get(i), GOODS_ID, "acc-" + i);
    }

    awaitUntil(
        "1000 条消息应全部消费完毕",
        () -> snapshotCalls.get() == USERS,
        Duration.ofMinutes(5));

    // 防超卖：成功扣减恰好 100 次（库存归零），库存不足全部走业务失败路径
    assertThat(deductSuccessCalls.get()).as("成功扣减次数应等于库存").isEqualTo(STOCK);
    assertThat(deductCalls.get()).as("每条消息至多尝试扣减一次").isEqualTo(USERS);
    assertThat(restoreCalls.get()).as("库存不足是业务失败，不得触发回补").isZero();

    // 最终订单 ≤ 100 且恰好 100：无超卖、无重复
    assertThat(countAllMiaoshaOrders()).as("最终订单数应等于库存").isEqualTo(STOCK);
    assertThat(countMiaoshaOrder(GOODS_ID)).isEqualTo(STOCK);
    assertThat(countOrderInfo(GOODS_ID)).isEqualTo(STOCK);
    assertThat(countDistinctUserGoods())
        .as("无重复成功订单（DISTINCT user_id, goods_id）")
        .isEqualTo(STOCK);
    assertThat(countOrphanOrders())
        .as("miaosha_order.order_id 必须全部对应 order_info")
        .isZero();

    // Redis 结果回写：恰好 100 个 SUCCESS:{orderId}
    assertThat(countRedisSuccessResults(GOODS_ID))
        .as("markSuccess 应回写 100 个成功结果")
        .isEqualTo(STOCK);

    // order-service 不私改预扣库存：无归属标记的 compensate 不得动库存
    assertThat(redisStock(GOODS_ID)).as("预扣库存应保持原值").isEqualTo(100);

    // ---------- 幂等重放：重放 DB 中真实的 100 个成功用户（同分区有序 + 哨兵观测） ----------
    List<Long> winners =
        jdbc.queryForList(
            "SELECT user_id FROM miaosha_order WHERE goods_id = ?", Long.class, GOODS_ID);
    assertThat(winners).hasSize(STOCK);

    String replayKey = "replay-" + GOODS_ID;
    for (Long uid : winners) {
      sendOrderMessage(uid, GOODS_ID, "acc-replay-" + uid, replayKey);
    }
    // 哨兵：新用户（无结果标记）走完整消费链路，是该分区内唯一调用 getGoodsVo 的消息
    long sentinelUser = userIds.get(USERS - 1) + 1;
    sendOrderMessage(sentinelUser, GOODS_ID, "acc-sentinel", replayKey);
    awaitUntil(
        "重放批次（含哨兵）应全部消费完毕",
        () -> snapshotCalls.get() == USERS + 1,
        Duration.ofMinutes(2));

    assertThat(countAllMiaoshaOrders()).as("重放后订单数不变").isEqualTo(STOCK);
    assertThat(deductSuccessCalls.get()).as("重放不得重复扣库存").isEqualTo(STOCK);
    assertThat(countRedisSuccessResults(GOODS_ID)).isEqualTo(STOCK);
  }
}
