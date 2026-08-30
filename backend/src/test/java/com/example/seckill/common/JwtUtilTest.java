package com.example.seckill.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtUtilTest {

  @Test
  void generateAndParseRoundtrip() {
    String token = JwtUtil.generateToken(13000000001L);
    assertThat(JwtUtil.parseUserId(token)).isEqualTo(13000000001L);
    assertThat(JwtUtil.isValid(token)).isTrue();
  }

  @Test
  void invalidTokensRejected() {
    assertThat(JwtUtil.isValid("garbage")).isFalse();
    assertThat(JwtUtil.isValid(JwtUtil.generateToken(1L) + "tampered")).isFalse();
  }
}
