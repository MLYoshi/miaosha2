package com.example.user.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.common.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * 复刻 backend JwtUtilTest 语义，另加与旧单体的一致性断言：
 * common.JwtUtil 与 backend.JwtUtil 共用同一 SECRET 常量与 claims 结构（subject=userId、24h 过期），
 * 因此两边的 token 必须互相可解析 —— 用 backend 的 secret 常量直接解析此处生成的 token 验证。
 */
class JwtUtilTest {

  /** 与 backend/src/main/java/.../common/JwtUtil.java 中 SECRET 常量逐字相同。 */
  private static final String BACKEND_SECRET =
      "your-256-bit-secret-key-here-must-be-long-enough";

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

  @Test // claims 一致性：subject=userId、24h 过期（与旧单体契约相同，见 step2-plan §8）
  void claimsMatchLegacyMonolithContract() {
    long before = System.currentTimeMillis();
    String token = JwtUtil.generateToken(13000000001L);
    long after = System.currentTimeMillis();

    Claims claims = parser().parseSignedClaims(token).getPayload();
    assertThat(claims.getSubject()).isEqualTo("13000000001");

    long exp = claims.getExpiration().getTime();
    assertThat(exp).isBetween(before + 23 * 60 * 60 * 1000L, after + 24 * 60 * 60 * 1000L + 5000);
    assertThat(claims.getIssuedAt()).isNotNull();
  }

  @Test // secret 一致性：用旧单体的 secret 生成的 Key 能解析新模块签发的 token（token 互认）
  void tokenVerifiableWithLegacyMonolithSecret() {
    String token = JwtUtil.generateToken(13000000002L);

    Claims claims =
        Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(BACKEND_SECRET.getBytes(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(token)
            .getPayload();

    assertThat(claims.getSubject()).isEqualTo("13000000002");
  }

  @Test // 反向一致性：过期 token 必须判为无效
  void expiredTokenRejected() {
    String expired =
        Jwts.builder()
            .subject("1")
            .expiration(new Date(System.currentTimeMillis() - 1000))
            .signWith(parserKey())
            .compact();
    assertThat(JwtUtil.isValid(expired)).isFalse();
  }

  private static SecretKey parserKey() {
    return Keys.hmacShaKeyFor(BACKEND_SECRET.getBytes(StandardCharsets.UTF_8));
  }

  private static io.jsonwebtoken.JwtParser parser() {
    return Jwts.parser().verifyWith(parserKey()).build();
  }
}
