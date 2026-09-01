package com.example.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.order.vo.GoodsSnapshotVo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link HttpGoodsClient} 错误信息保真回归测试（review report Issue 4）：
 * 依旧用 JDK 内置 {@link HttpServer} 模拟 goods-service「HTTP 200 + 业务码壳」的真实响应模式。
 *
 * <p>Issue 4 契约（报告 Fix Recommendation）：
 * <ul>
 *   <li>{@code resolveCodeMsg} 遇未知业务码时必须以 warn 级别记录<b>原始 code 与 msg</b>
 *       ——否则 goods-service 侧真实错误信息（如 SQL 异常摘要）被静默丢弃为
 *       SERVER_ERROR 的静态文案，跨服务排障只能翻两个服务的日志对时间戳</li>
 *   <li>{@code getGoodsVo} 在 {@code code=0 && data=null} 时必须视为系统异常上抛
 *       非 {@link MiaoshaException}——若按接缝契约返回 null，会被 OrderService
 *       误判为「商品不存在」（GOODS_NOT_EXIST），掩盖真实的序列化/契约故障；
 *       且该场景无副作用，按意外异常处理可安全重试（重试 → DLT）</li>
 *   <li>已知业务码到 {@link CodeMsg} 的还原映射不受影响（回归保护）</li>
 * </ul>
 */
class HttpGoodsClientIssue4Test {

  private static final int GOODS_NOT_EXIST_CODE = CodeMsg.GOODS_NOT_EXIST.getCode(); // 500104
  private static final int UNKNOWN_CODE = 599999; // CodeMsg 中不存在的业务码

  private static HttpServer server;
  /** 每个用例自设响应（HTTP 状态码 + 响应体），默认 200 + 成功壳。 */
  private static final AtomicReference<StubResponse> stub =
      new AtomicReference<>(StubResponse.ok("{}"));

  private HttpGoodsClient client;
  private ListAppender<ILoggingEvent> logWatcher;

  @BeforeAll
  static void startStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          StubResponse resp = stub.get();
          byte[] body = resp.body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(resp.httpStatus, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          } else {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterAll
  static void stopStubServer() {
    server.stop(0);
  }

  @BeforeEach
  void setUp() {
    stub.set(StubResponse.ok("{}"));
    client = new HttpGoodsClient("http://localhost:" + server.getAddress().getPort());

    Logger clientLogger = (Logger) LoggerFactory.getLogger(HttpGoodsClient.class);
    logWatcher = new ListAppender<>();
    logWatcher.start();
    clientLogger.addAppender(logWatcher);
    clientLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detachLogWatcher() {
    ((Logger) LoggerFactory.getLogger(HttpGoodsClient.class)).detachAppender(logWatcher);
  }

  /**
   * 核心契约（Issue 4）：未知业务码除还原为 SERVER_ERROR 外，必须以 warn 记录
   * 原始 code + msg，保住 goods-service 侧真实错误信息用于排障。
   */
  @Test
  void getGoodsVo_unknownCode_logsWarnWithOriginalCodeAndMsg() {
    stub.set(StubResponse.ok(resultShell(UNKNOWN_CODE, "神秘错误")));

    assertThatThrownBy(() -> client.getGoodsVo(2L))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.SERVER_ERROR);

    assertThat(logWatcher.list)
        .as("未知业务码必须以 warn 级别记录原始 code 与 msg，否则无法排障")
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(String.valueOf(UNKNOWN_CODE))
                  .contains("神秘错误");
            });
  }

  /**
   * deductStock 路径的未知码同样要保真记录原始错误信息（resolveCodeMsg 是三端点共用）。
   */
  @Test
  void deductStock_unknownCode_logsWarnWithOriginalCodeAndMsg() {
    stub.set(StubResponse.ok(resultShell(UNKNOWN_CODE, "扣减路径神秘错误")));

    assertThatThrownBy(() -> client.deductStock(2L, null))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.SERVER_ERROR);

    assertThat(logWatcher.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(String.valueOf(UNKNOWN_CODE))
                  .contains("扣减路径神秘错误");
            });
  }

  /**
   * 核心契约（Issue 4）：code=0 && data=null 是响应壳成功但载荷缺失——
   * 真实的序列化/契约故障。绝不能返回 null（会被 OrderService 误判为商品不存在），
   * 必须按意外异常（非 {@link MiaoshaException}）上抛，消费路径可安全重试。
   */
  @Test
  void getGoodsVo_successShellWithNullData_throwsUnexpectedExceptionNotGoodsNotExist() {
    stub.set(StubResponse.ok("{\"code\":0,\"msg\":\"success\",\"data\":null}"));

    assertThatThrownBy(() -> client.getGoodsVo(2L))
        .as("code=0 且 data=null 属契约故障，必须按意外异常上抛，不能伪装成商品不存在")
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(MiaoshaException.class)
        .hasMessageContaining("2");
  }

  /** 回归保护：已知业务码的映射还原不受 Issue 4 改动影响（500104 → GOODS_NOT_EXIST）。 */
  @Test
  void deductStock_knownBusinessCode_mapsToMatchingCodeMsg() {
    stub.set(StubResponse.ok(resultShell(GOODS_NOT_EXIST_CODE, "商品不存在")));

    assertThatThrownBy(() -> client.deductStock(2L, null))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.GOODS_NOT_EXIST);
  }

  /** 回归保护：正常成功壳不受改动影响（快照字段照常还原）。 */
  @Test
  void getGoodsVo_success_returnsSnapshot() {
    stub.set(
        StubResponse.ok(
            "{\"code\":0,\"msg\":\"success\",\"data\":{\"id\":2,\"goodsName\":\"iPhoneX\","
                + "\"miaoshaPrice\":100.00}}"));

    GoodsSnapshotVo vo = client.getGoodsVo(2L);

    assertThat(vo).isNotNull();
    assertThat(vo.getId()).isEqualTo(2L);
    assertThat(vo.getGoodsName()).isEqualTo("iPhoneX");
  }

  private static String resultShell(int code, String msg) {
    return "{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null}";
  }

  /** 桩响应：HTTP 状态码 + JSON 体。 */
  private record StubResponse(int httpStatus, String body) {
    static StubResponse ok(String body) {
      return new StubResponse(200, body);
    }
  }
}
