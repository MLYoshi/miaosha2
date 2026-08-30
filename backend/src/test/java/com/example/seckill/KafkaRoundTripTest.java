package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.cache.RedisKeyBuilder;
import com.example.seckill.message.OrderMessageProducer;
import com.example.seckill.message.SeckillOrderMessage;
import com.example.seckill.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 01 — Kafka 基础设施打通：一条下单请求消息经真实 broker 完成往返。
 *
 * <p>票 03 后消费者执行真实落库编排：本用例预置 user 标记（requestId），消息经 broker
 * 送达消费者 → createOrder 失败（商品不存在）→ 补偿写入 result=FAILED。轮询 Redis 直到
 * FAILED 即证明往返完成，且三字段被语义验证——补偿仅在 requestId 与预置标记一致时写
 * FAILED，而 key 由 goodsId/userId 构成。
 *
 * <p>观测点选 Redis 而非消费者内存队列：受理异步化后队列会残留其它测试的消息，
 * 且多个缓存 Spring 上下文的消费者同组分摊分区，队列归属不确定。
 */
class KafkaRoundTripTest extends AbstractIntegrationTest {

  private static final long GOODS_ID = 42L;
  private static final long USER_ID = 13000000299L;
  private static final String REQUEST_ID = "req-roundtrip-01";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Autowired private OrderMessageProducer producer;

  @Test
  void orderMessage_roundTripsThroughBroker() throws Exception {
    // 预置抢购标记：消费补偿仅在 requestId 与之一致时才写 result=FAILED
    redisTemplate
        .opsForValue()
        .set(RedisKeyBuilder.user(GOODS_ID, USER_ID), REQUEST_ID, Duration.ofHours(1));

    // 生产者可靠发送（acks=all + 幂等），key 随机打散
    producer.send(new SeckillOrderMessage(USER_ID, GOODS_ID, REQUEST_ID));

    // 轮询 Redis 结果：消费编排补偿写入 FAILED 即证明消息经真实 broker 往返完成
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    String result = null;
    while (System.nanoTime() < deadline) {
      result = redisTemplate.opsForValue().get(RedisKeyBuilder.result(GOODS_ID, USER_ID));
      if ("FAILED".equals(result)) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(result).as("应在超时内经真实 broker 完成往返并补偿 FAILED").isEqualTo("FAILED");
  }
}
