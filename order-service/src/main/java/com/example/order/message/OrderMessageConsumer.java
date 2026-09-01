package com.example.order.message;

import com.example.order.service.OrderFulfillmentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 下单请求消息消费者（迁移自单体）：订阅 {@code seckill-order} topic，手动 ack，
 * 落库编排见 {@link OrderFulfillmentService}。
 *
 * <p>ack 语义（与基线一致）：
 * <ul>
 *   <li>编排正常返回（落库成功 / 业务失败已补偿 / 已成功的重复投递快跳）→ ack
 *   <li>意外异常上抛、不 ack，由容器错误处理（{@link KafkaConsumerConfig#kafkaErrorHandler}）
 *       指数退避重试 1s/2s/4s，耗尽后进死信 topic，位点继续推进
 * </ul>
 */
@Component
public class OrderMessageConsumer {

  private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);

  private final OrderFulfillmentService fulfillmentService;

  public OrderMessageConsumer(OrderFulfillmentService fulfillmentService) {
    this.fulfillmentService = fulfillmentService;
  }

  @KafkaListener(topics = KafkaConsumerConfig.ORDER_TOPIC, groupId = "seckill")
  public void onMessage(ConsumerRecord<String, SeckillOrderMessage> record, Acknowledgment ack) {
    SeckillOrderMessage message = record.value();
    log.info(
        "收到下单请求 topic={} partition={} offset={} userId={} goodsId={} requestId={}",
        record.topic(),
        record.partition(),
        record.offset(),
        message.getUserId(),
        message.getGoodsId(),
        message.getRequestId());
    try {
      fulfillmentService.fulfill(message);
    } catch (Exception e) {
      // 意外异常：不 ack 直接上抛（容器错误处理负责重试与死信）
      log.error(
          "下单消息处理意外失败，不 ack topic={} partition={} offset={} requestId={}",
          record.topic(),
          record.partition(),
          record.offset(),
          message.getRequestId(),
          e);
      throw e;
    }
    ack.acknowledge();
  }
}
