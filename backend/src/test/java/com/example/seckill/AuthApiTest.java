package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** F1-F5：注册/登录链路与 JWT 拦截。 */
class AuthApiTest extends AbstractIntegrationTest {

  @Test // F1 登录成功 → token 可用，profile 脱敏
  void loginSuccess_thenAccessProfile() {
    long mobile = insertUser(13000000001L);

    JsonNode login =
        body(postJson("/user/login", "{\"mobile\":\"13000000001\",\"password\":\"123456\"}"));
    assertThat(login.get("code").asInt()).as(login.toString()).isEqualTo(CODE_SUCCESS);
    String token = login.get("data").asText();
    assertThat(token).isNotBlank();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    JsonNode profile =
        body(rest.exchange("/user/profile", HttpMethod.GET, new HttpEntity<>(headers), String.class));
    assertThat(profile.get("code").asInt()).as(profile.toString()).isEqualTo(CODE_SUCCESS);
    JsonNode data = profile.get("data");
    assertThat(data.get("id").asLong()).isEqualTo(mobile);
    assertThat(data.get("nickname").asText()).isEqualTo("user13000000001");
    assertThat(data.get("password").isNull()).as("password 应脱敏").isTrue();
    assertThat(data.get("salt").isNull()).as("salt 应脱敏").isTrue();
  }

  @Test // F2 登录失败两分支
  void loginFailures() {
    insertUser(13000000002L);

    JsonNode noUser =
        body(postJson("/user/login", "{\"mobile\":\"13700000000\",\"password\":\"123456\"}"));
    assertThat(noUser.get("code").asInt()).isEqualTo(CODE_MOBILE_NOT_EXIST);

    JsonNode badPass =
        body(postJson("/user/login", "{\"mobile\":\"13000000002\",\"password\":\"654321\"}"));
    assertThat(badPass.get("code").asInt()).isEqualTo(CODE_PASSWORD_ERROR);
  }

  @Test // F4 注册成功 → 直接返回 token，且同凭据可登录（验证加密兼容）
  void registerSuccess_returnsTokenAndCredentialsWorkForLogin() {
    JsonNode register =
        body(postJson("/user/register", "{\"mobile\":\"13000000003\",\"password\":\"123456\"}"));
    assertThat(register.get("code").asInt()).as(register.toString()).isEqualTo(CODE_SUCCESS);
    String token = register.get("data").asText();
    assertThat(token).isNotBlank();

    // 注册返回的 token 与登录 token 同构，可直接访问受保护接口
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    JsonNode profile =
        body(rest.exchange("/user/profile", HttpMethod.GET, new HttpEntity<>(headers), String.class));
    assertThat(profile.get("code").asInt()).as(profile.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(profile.get("data").get("id").asLong()).isEqualTo(13000000003L);

    // 用同一手机号+明文密码走登录链路，验证注册时密码加密方式与登录校验一致
    JsonNode login =
        body(postJson("/user/login", "{\"mobile\":\"13000000003\",\"password\":\"123456\"}"));
    assertThat(login.get("code").asInt()).as(login.toString()).isEqualTo(CODE_SUCCESS);
  }

  @Test // F5 重复注册 → 500503，且不影响已注册用户登录
  void registerDuplicateMobileRejected() {
    JsonNode first =
        body(postJson("/user/register", "{\"mobile\":\"13000000004\",\"password\":\"123456\"}"));
    assertThat(first.get("code").asInt()).as(first.toString()).isEqualTo(CODE_SUCCESS);

    JsonNode dup =
        body(postJson("/user/register", "{\"mobile\":\"13000000004\",\"password\":\"654321\"}"));
    assertThat(dup.get("code").asInt()).as(dup.toString()).isEqualTo(CODE_MOBILE_ALREADY_EXIST);

    // 重复注册失败不应覆盖原账号凭据
    JsonNode login =
        body(postJson("/user/login", "{\"mobile\":\"13000000004\",\"password\":\"123456\"}"));
    assertThat(login.get("code").asInt()).as(login.toString()).isEqualTo(CODE_SUCCESS);
  }

  @Test // F3 无 token / 假 token → 401
  void protectedEndpointsRequireValidToken() {
    assertThat(get("/user/profile", null).getStatusCode().value()).isEqualTo(401);
    assertThat(get("/goods/list", null).getStatusCode().value()).isEqualTo(401);

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("not.a.real.token");
    assertThat(
            rest.exchange("/user/profile", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }
}
