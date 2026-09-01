package com.example.order.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 加载 classpath:scripts 下的 Lua 脚本，供 {@link RedisOrderResultStore} 原子执行。
 *
 * <p>只加载补偿脚本：预扣 try Lua 仍归 miaosha-service，order-service 不做预扣。
 */
@Configuration
public class RedisScriptConfig {

  @Bean
  public RedisScript<Long> miaoshaCompensateScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("scripts/miaosha_compensate.lua")));
    script.setResultType(Long.class);
    return script;
  }
}
