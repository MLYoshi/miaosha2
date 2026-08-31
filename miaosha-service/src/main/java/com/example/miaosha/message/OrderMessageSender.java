package com.example.miaosha.message;

/**
 * 下单消息发送接缝（仿 {@code MiaoshaRedisStore} 模式）。
 *
 * <p>受理编排只依赖本接口，不感知 Kafka 细节：生产实现走真实 broker，
 * 测试用 {@code FakeOrderMessageSender} 等价替换（支持注入发送失败）。
 *
 * <p>异常契约：发送失败（broker 不可达 / 超时等）向上抛，由受理方决定是否降级。
 */
public interface OrderMessageSender {

  /**
   * 发送一条下单请求消息。
   *
   * <p>消息 key 由实现方决定：随机打散（热点商品避免打爆单分区），
   * 顺序性由 DB 条件扣库存 + 唯一键兜底。
   *
   * @throws RuntimeException 发送失败时上抛，受理方降级同步落库
   */
  void send(SeckillOrderMessage message);
}
