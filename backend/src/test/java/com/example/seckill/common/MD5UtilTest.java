package com.example.seckill.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MD5UtilTest {

  @Test
  void md5KnownVector() {
    assertThat(MD5Util.md5("abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
  }

  @Test
  void inputPassToFormPassVector() {
    assertThat(MD5Util.inputPassToFormPass("123456"))
        .isEqualTo("d3b1294a61a07da9b49b6e22b2cbd7f9");
  }

  @Test
  void fullChainMatchesSeedUserHash() {
    // db-design.md §4.3 种子用户（13000000000~13000004999）的库内密码 = 明文 123456 + salt 1a2b3c
    assertThat(MD5Util.inputPassToDbPass("123456", "1a2b3c"))
        .isEqualTo("b7797cce01b4b131b433b6acf4add449");
  }

  @Test
  void differentSaltProducesDifferentHash() {
    assertThat(MD5Util.inputPassToDbPass("123456", "1a2b3c"))
        .isNotEqualTo(MD5Util.inputPassToDbPass("123456", "9z8y7x"));
  }
}
