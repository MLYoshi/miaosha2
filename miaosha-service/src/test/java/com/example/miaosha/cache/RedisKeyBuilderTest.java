package com.example.miaosha.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisKeyBuilderTest {

  @Test
  void keyFormats() {
    assertThat(RedisKeyBuilder.stock(1L)).isEqualTo("miaosha:stock:1");
    assertThat(RedisKeyBuilder.user(1L, 2L)).isEqualTo("miaosha:user:1:2");
    assertThat(RedisKeyBuilder.result(1L, 2L)).isEqualTo("miaosha:result:1:2");
  }
}
