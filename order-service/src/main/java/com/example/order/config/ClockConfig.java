package com.example.order.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供应用与测试上下文共享的 {@link Clock} bean（对齐 goods/miaosha-service）。
 *
 * <p>时钟消费者（秒杀时间窗校验等）均依赖注入 Clock，
 * 若上下文无此 bean，启动时会抛 {@code NoSuchBeanDefinitionException}。
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
