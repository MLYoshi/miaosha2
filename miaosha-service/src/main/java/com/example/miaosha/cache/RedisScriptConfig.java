package com.example.miaosha.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 加载 classpath:scripts 下的 Lua 脚本，供 {@link RedisMiaoshaStore} 原子执行。
 */
@Configuration
public class RedisScriptConfig {

  @Bean
  public RedisScript<Long> miaoshaTryScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("scripts/miaosha_try.lua")));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  public RedisScript<Long> miaoshaCompensateScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("scripts/miaosha_compensate.lua")));
    script.setResultType(Long.class);
    return script;
  }
}
