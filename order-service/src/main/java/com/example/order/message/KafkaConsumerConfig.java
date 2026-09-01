package com.example.order.message;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka 消费侧配置：意外异常的重试与死信（迁移自单体 {@code KafkaConfig} 消费侧）。
 *
 * <p>topic 声明（seckill-order / seckill-order-dlt 的 NewTopic）由生产侧
 * miaosha-service 负责创建，本服务只做消费，不重复声明。
 *
 * <p>可靠性语义（与基线一致）：意外异常指数退避有限重试 3 次（1s → 2s → 4s）→
 * 仍失败发布到死信 topic {@value #ORDER_DLT_TOPIC}，消费位点继续推进——毒消息
 * 不卡死消费主循环。业务失败（{@code MiaoshaException}）在消费编排内已消化，
 * 不会进入重试。
 */
@Configuration
public class KafkaConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  /** 下单请求 topic：与生产侧（miaosha-service）契约一致。 */
  public static final String ORDER_TOPIC = "seckill-order";

  /** 下单请求死信 topic：重试耗尽的毒消息归宿，人工介入后可重放。 */
  public static final String ORDER_DLT_TOPIC = "seckill-order-dlt";

  /** 意外异常的指数退避重试次数（不含首次消费），与基线一致 3 次。 */
  private static final int RETRY_ATTEMPTS = 3;

  /** 退避初始间隔（毫秒），后续按倍数指数增长：1s → 2s → 4s。 */
  private static final long RETRY_INITIAL_INTERVAL_MS = 1000L;

  private static final double RETRY_MULTIPLIER = 2.0;

  /**
   * 死信发布专用模板：value 用 JsonSerializer 且关闭类型头，与生产侧
   * （miaosha-service 纯 JSON、无类型头）消息契约一致。
   *
   * <p>不能用 Boot 自动配置的 {@code KafkaTemplate}：其 value serializer 默认为
   * StringSerializer，无法序列化 {@link SeckillOrderMessage}——死信发布会以
   * SerializationException 失败，毒消息将无限重试、永不进入死信。
   */
  @Bean
  public KafkaTemplate<String, SeckillOrderMessage> dltKafkaTemplate(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
    JsonSerializer<SeckillOrderMessage> valueSerializer = new JsonSerializer<>();
    valueSerializer.setAddTypeInfo(false);
    DefaultKafkaProducerFactory<String, SeckillOrderMessage> producerFactory =
        new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    return new KafkaTemplate<>(producerFactory);
  }

  /**
   * 消费侧错误处理：意外异常指数退避重试 {@link #RETRY_ATTEMPTS} 次，耗尽后发布到
   * {@value #ORDER_DLT_TOPIC} 并记录可定位日志，随后位点推进。
   *
   * <p>Bean 会被 Boot 自动配置的 listener container factory 采用（唯一的
   * {@link org.springframework.kafka.listener.CommonErrorHandler} bean）。
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      @org.springframework.beans.factory.annotation.Qualifier("dltKafkaTemplate")
      KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate) {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRY_ATTEMPTS);
    backOff.setInitialInterval(RETRY_INITIAL_INTERVAL_MS);
    backOff.setMultiplier(RETRY_MULTIPLIER);

    DeadLetterPublishingRecoverer publisher =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> new TopicPartition(ORDER_DLT_TOPIC, record.partition()));

    ConsumerRecordRecoverer recoverer =
        (record, ex) -> {
          if (record.value() instanceof SeckillOrderMessage message) {
            log.error(
                "下单消息重试耗尽进入死信，人工介入后可重放 topic={} partition={} offset={}"
                    + " userId={} goodsId={} requestId={} 原因={}",
                record.topic(),
                record.partition(),
                record.offset(),
                message.getUserId(),
                message.getGoodsId(),
                message.getRequestId(),
                ex.getMessage(),
                ex);
          } else {
            log.error(
                "消息重试耗尽进入死信 topic={} partition={} offset={} 原因={}",
                record.topic(),
                record.partition(),
                record.offset(),
                ex.getMessage(),
                ex);
          }
          publisher.accept(record, ex);
        };
    return new DefaultErrorHandler(recoverer, backOff);
  }
}
