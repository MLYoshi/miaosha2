package com.example.seckill.message;

import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 下单请求消息生产者（{@link OrderMessageSender} 的 Kafka 实现）：
 * 把 {@link SeckillOrderMessage} 发往 {@code seckill-order} topic。
 *
 * <p>可靠发送依赖 {@code application.yaml} 的 {@code acks=all} + 幂等生产者
 * （{@code enable.idempotence=true}），此处只负责投递语义。
 *
 * <p>已定决策：消息 key 不用 goodsId（热点商品会打爆单分区），随机打散；
 * 顺序性由 DB 条件扣库存 + 唯一键兜底。
 */
@Component
public class OrderMessageProducer implements OrderMessageSender {

  private final KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate;

  public OrderMessageProducer(KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  /**
   * 发送一条下单请求消息，阻塞等待 broker 确认（acks=all + 幂等）。
   *
   * @throws RuntimeException 发送失败（broker 不可达 / 超时等）时上抛，受理方降级同步落库
   */
  @Override
  public void send(SeckillOrderMessage message) {
    String key = UUID.randomUUID().toString();
    kafkaTemplate.send(KafkaConfig.ORDER_TOPIC, key, message).join();
  }
}
