package com.example.miaosha;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.miaosha.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * C6：并发正确性（受理异步化口径：CODE_SUCCESS = 受理成功，即预扣 + 消息入队）。
 *
 * <p>1000 用户 / 100 库存齐射：成功受理数 == 库存扣减数 == 消息入队数 == 100，
 * 无超卖、无重复用户、requestId 互不重复；无 DB（miaosha-service 不落库），
 * 对账以 Redis 库存 + Kafka 消息为准。
 */
class MiaoshaConcurrencyTest extends AbstractIntegrationTest {

  private static final long GOODS_ID = 9100L;
  private static final int USERS = 1000;
  private static final int STOCK = 100;
  private static final Duration KAFKA_WAIT = Duration.ofSeconds(60);

  @Test
  void volley1000users100stock_noOversellNoRepeat() throws Exception {
    preheat(GOODS_ID, STOCK);

    List<Long> userIds = new ArrayList<>();
    for (int i = 0; i < USERS; i++) {
      userIds.add(20000100000L + i);
    }

    List<Integer> codes = volley(userIds, GOODS_ID);

    // 恰好 100 成功，其余全部 500214（用户互不相同，不应出现其它业务码）
    long success = codes.stream().filter(c -> c == CODE_SUCCESS).count();
    long stockEmpty = codes.stream().filter(c -> c == CODE_MIAOSHA_STOCK_EMPTY).count();
    assertThat(success).as("成功受理数应等于库存").isEqualTo(STOCK);
    assertThat(stockEmpty).as("其余用户应全部库存不足").isEqualTo(USERS - STOCK);

    // 不超卖：Redis 库存恰好归零
    assertThat(redisStock(GOODS_ID)).as("Redis 库存应恰好归零").isZero();

    // 消息入队数 == 100，userId / requestId 互不重复
    var messages = awaitKafkaMessages(GOODS_ID, STOCK, KAFKA_WAIT);
    assertThat(messages).as("消息入队数应等于成功受理数").hasSize(STOCK);
    Set<Long> userIdInMessages = new HashSet<>();
    for (var msg : messages) {
      userIdInMessages.add(msg.path("userId").asLong());
    }
    assertThat(userIdInMessages).as("无重复用户").hasSize(STOCK);
    assertDistinctRequestIds(messages);

    // 成功用户留有 PROCESSING 结果，且 user 标记与消息 requestId 一致（抽查首位成功者）
    java.util.Map<Long, String> requestIdByUser = new java.util.HashMap<>();
    for (var msg : messages) {
      requestIdByUser.put(msg.path("userId").asLong(), msg.path("requestId").asText());
    }
    long firstSuccess = userIds.get(codes.indexOf(CODE_SUCCESS));
    assertThat(redisUserMark(GOODS_ID, firstSuccess))
        .as("user 标记应与消息 requestId 一致")
        .isEqualTo(requestIdByUser.get(firstSuccess))
        .isNotNull();
    assertThat(redisResult(GOODS_ID, firstSuccess)).isEqualTo("PROCESSING");
  }

  /** 齐射：所有线程在同一个 CyclicBarrier 后同时调用秒杀，返回每个请求的业务 code。 */
  private List<Integer> volley(List<Long> userIds, long goodsId) throws Exception {
    int n = userIds.size();
    CyclicBarrier barrier = new CyclicBarrier(n);
    // 线程数必须等于齐射规模：屏障等待会占满线程，池小于 n 时排队的任务无法抵达屏障
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Long uid : userIds) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  return doMiaosha(uid, goodsId).get("code").asInt();
                }));
      }
      List<Integer> codes = new ArrayList<>();
      for (Future<Integer> f : futures) {
        codes.add(f.get(120, TimeUnit.SECONDS));
      }
      return codes;
    } finally {
      pool.shutdownNow();
    }
  }
}
