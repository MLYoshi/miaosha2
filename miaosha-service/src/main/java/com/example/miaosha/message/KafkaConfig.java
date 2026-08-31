package com.example.miaosha.message;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka 消息层配置：下单请求 topic 与死信 topic 声明（miaosha-service 仅生产侧）。
 *
 * <p>已定决策：topic 名 {@value #ORDER_TOPIC}，分区 3、副本因子 1（KRaft 单节点）。
 * 生产侧的可靠性与 ack 语义见 {@code application.yml} 的 {@code spring.kafka} 段。
 *
 * <p>消费侧错误处理（DefaultErrorHandler / 指数退避重试 / 死信发布）不迁移，
 * Step 5 归 order-service。
 */
@Configuration
public class KafkaConfig {

  /** 下单请求 topic：秒杀请求排队、异步下单削峰的唯一入口 topic。 */
  public static final String ORDER_TOPIC = "seckill-order";

  /** 下单请求死信 topic：重试耗尽的毒消息归宿，人工介入后可重放。 */
  public static final String ORDER_DLT_TOPIC = "seckill-order-dlt";

  private static final int PARTITIONS = 3;
  private static final short REPLICATION_FACTOR = 1;

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
}
