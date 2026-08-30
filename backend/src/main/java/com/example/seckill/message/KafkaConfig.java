package com.example.seckill.message;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka 消息层配置：下单请求 topic 与死信 topic 声明、消费侧错误处理（票 04）。
 *
 * <p>已定决策：topic 名 {@value #ORDER_TOPIC}，分区 3、副本因子 1（KRaft 单节点）。
 * 生产/消费两侧的可靠性与 ack 语义见 {@code application.yaml} 的 {@code spring.kafka} 段。
 *
 * <p>消费侧可靠性（票 04）：意外异常指数退避有限重试（{@link #RETRY_ATTEMPTS} 次）→
 * 仍失败发布到死信 topic {@value #ORDER_DLT_TOPIC}，消费位点继续推进——毒消息不卡死
 * 消费主循环。业务失败（{@code MiaoshaException}）在消费编排内已消化，不会进入重试。
 */
@Configuration
public class KafkaConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

  /** 下单请求 topic：秒杀请求排队、异步下单削峰的唯一入口 topic。 */
  public static final String ORDER_TOPIC = "seckill-order";

  /** 下单请求死信 topic：重试耗尽的毒消息归宿，人工介入后可重放。 */
  public static final String ORDER_DLT_TOPIC = "seckill-order-dlt";

  private static final int PARTITIONS = 3;
  private static final short REPLICATION_FACTOR = 1;

  /** 意外异常的指数退避重试次数（不含首次消费），已定决策 3 次。 */
  private static final int RETRY_ATTEMPTS = 3;

  /** 退避初始间隔（毫秒），后续按倍数指数增长：1s → 2s → 4s。 */
  private static final long RETRY_INITIAL_INTERVAL_MS = 1000L;

  private static final double RETRY_MULTIPLIER = 2.0;

  /**
   * 自动创建 {@code seckill-order} topic。
   *
   * <p>仅创建 topic 声明本身；若 broker 已存在同名 topic 则沿用既有分区/副本配置，
   * 不强制重设。副本因子 1 对应单 broker 部署（见 docker-compose 的 KRaft 单节点）。
   */
  @Bean
  public NewTopic seckillOrderTopic() {
    return new NewTopic(ORDER_TOPIC, PARTITIONS, REPLICATION_FACTOR);
  }

  /** 自动创建 {@code seckill-order-dlt} 死信 topic，分区数与源 topic 对齐。 */
  @Bean
  public NewTopic seckillOrderDltTopic() {
    return new NewTopic(ORDER_DLT_TOPIC, PARTITIONS, REPLICATION_FACTOR);
  }

  /**
   * 消费侧错误处理（票 04）：意外异常指数退避重试 {@link #RETRY_ATTEMPTS} 次，
   * 耗尽后发布到 {@value #ORDER_DLT_TOPIC} 并记录可定位日志，随后位点推进。
   *
   * <p>Bean 会被 Boot 自动配置的 listener container factory 采用（唯一的
   * {@link org.springframework.kafka.listener.CommonErrorHandler} bean）。
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
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

