package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * C1-C5：并发正确性（票 03 受理异步化口径：CODE_SUCCESS = 受理成功，即预扣 + 消息入队）。
 * 齐射后等待消费完成，对账不变式不变：成功受理数 == 库存扣减数 == order_info 行数 ==
 * miaosha_order 行数，无重复 (user_id, goods_id)，且 Redis 库存 == DB 库存。
 */
class MiaoshaConcurrencyTest extends AbstractIntegrationTest {

  private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(60);

  /** 齐射：所有线程在同一个 CyclicBarrier 后同时调用秒杀，返回每个请求的业务 code。 */
  private List<Integer> volley(List<Long> userIds, long goodsId) {
    int n = userIds.size();
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Long uid : userIds) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(15, TimeUnit.SECONDS);
                  return doMiaosha(uid, goodsId).get("code").asInt();
                }));
      }
      List<Integer> codes = new ArrayList<>();
      for (Future<Integer> f : futures) {
        codes.add(f.get(120, TimeUnit.SECONDS));
      }
      return codes;
    } catch (Exception e) {
      throw new AssertionError("齐射执行失败", e);
    } finally {
      pool.shutdownNow();
    }
  }

  private List<Long> insertUsers(long base, int count) {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(insertUser(base + i));
    }
    return ids;
  }

  private void assertLedger(long goodsId, int initialStock, int expectSuccess) {
    assertThat(dbStock(goodsId)).as("DB 库存").isEqualTo(initialStock - expectSuccess);
    assertThat(orderCount()).as("order_info 行数").isEqualTo(expectSuccess);
    assertThat(miaoshaOrderCount()).as("miaosha_order 行数").isEqualTo(expectSuccess);
    assertThat(duplicateMiaoshaOrderCount()).as("重复 (user_id, goods_id)").isZero();
    assertThat(redisStock(goodsId)).as("Redis 库存").isEqualTo(initialStock - expectSuccess);
  }

  @Test // C1 stock=10，50 用户齐射 → 恰好 10 成功，不超卖
  void noOversell_50usersRace10stock() {
    long goodsId = insertGoods("iphoneX", 10);
    List<Long> users = insertUsers(13000001000L, 50);
    preheat(goodsId, users.get(0));

    List<Integer> codes = volley(users, goodsId);

    assertThat(codes.stream().filter(c -> c == CODE_SUCCESS).count()).isEqualTo(10);
    awaitOrderCount(10, CONSUME_TIMEOUT);
    assertLedger(goodsId, 10, 10);
  }

  @Test // C2 同一用户 20 线程齐射 → 恰好 1 成功
  void sameUserRace_onlyOneSuccess() {
    long user = insertUser(13000002000L);
    long goodsId = insertGoods("iphoneX", 9);
    preheat(goodsId, user);

    List<Integer> codes = volley(Collections.nCopies(20, user), goodsId);

    assertThat(codes.stream().filter(c -> c == CODE_SUCCESS).count()).isEqualTo(1);
    awaitOrderCount(1, CONSUME_TIMEOUT);
    assertLedger(goodsId, 9, 1);
  }

  @Test // C3 stock=1，30 用户齐射 → 恰好 1 成功
  void stockOfOne_30usersRace() {
    long goodsId = insertGoods("iphoneX", 1);
    List<Long> users = insertUsers(13000003000L, 30);
    preheat(goodsId, users.get(0));

    List<Integer> codes = volley(users, goodsId);

    assertThat(codes.stream().filter(c -> c == CODE_SUCCESS).count()).isEqualTo(1);
    awaitOrderCount(1, CONSUME_TIMEOUT);
    assertLedger(goodsId, 1, 1);
  }

  @Test // C4 成功者二轮齐射 → 全部 500212，状态不变
  void secondVolley_allRepeat_noStateChange() {
    long goodsId = insertGoods("iphoneX", 10);
    List<Long> users = insertUsers(13000004000L, 10);
    preheat(goodsId, users.get(0));

    List<Integer> first = volley(users, goodsId);
    assertThat(first).allMatch(c -> c == CODE_SUCCESS);
    awaitOrderCount(10, CONSUME_TIMEOUT);

    List<Integer> second = volley(users, goodsId);
    assertThat(second).allMatch(c -> c == CODE_MIAOSHA_REPEAT);
    assertLedger(goodsId, 10, 10);
  }

  @Test // C5 预热 stock=5，10 用户齐射 → 恰好 5 成功（Redis 闸门与 DB 一致）
  void preheatedStockIsHardGate() {
    long goodsId = insertGoods("iphoneX", 5);
    List<Long> users = insertUsers(13000005000L, 10);
    preheat(goodsId, users.get(0));

    List<Integer> codes = volley(users, goodsId);

    assertThat(codes.stream().filter(c -> c == CODE_SUCCESS).count()).isEqualTo(5);
    awaitOrderCount(5, CONSUME_TIMEOUT);
    assertLedger(goodsId, 5, 5);
  }
}
