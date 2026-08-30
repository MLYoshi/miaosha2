package com.example.seckill.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public final class JwtUtil {

    private static final String SECRET = "your-256-bit-secret-key-here-must-be-long-enough";
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24小时

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
        SECRET.getBytes(StandardCharsets.UTF_8)
    );

    private JwtUtil() {}

    // 生成 Token
    public static String generateToken(Long userId) {
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
            .signWith(KEY)
            .compact();
    }

    // 解析 Token，拿到 userId
    public static Long parseUserId(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(KEY)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return Long.valueOf(claims.getSubject());
    }

    // 验证 Token 是否有效（过期或签名错误都返回 false）
    public static boolean isValid(String token) {
        try {
            parseUserId(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}