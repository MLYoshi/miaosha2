package com.example.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.order.vo.GoodsSnapshotVo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link HttpGoodsClient} 错误码分类回归测试（review report Issue 2）：
 * 用 JDK 内置 {@link HttpServer} 模拟 goods-service「HTTP 200 + 业务码壳」的真实响应模式，
 * 验证 getGoodsVo / deductStock / restoreStock 对各类业务码的异常还原语义。
 *
 * <p>Issue 2 契约（对齐基线「本地 SQL 异常 → 意外异常 → 重试 → DLT」）：
 * <ul>
 *   <li>500100（goods-service 兜底 handler 对任何系统异常返回）在 {@code getGoodsVo}
 *       上必须还原为<b>非</b> {@link MiaoshaException}（意外异常语义）——否则消费路径
 *       按「业务失败」补偿 + ack，瞬时故障期间在途消息被一次性永久化为 FAILED；
 *       getGoodsVo 无副作用，重试绝对安全</li>
 *   <li>500100 在 {@code deductStock} 上保持 {@link MiaoshaException} fail-fast——
 *       扣减有二义性，补偿语义优先，不与 Issue 1 叠加（报告明示保持现状）</li>
 *   <li>500104 商品不存在 → 返回 null（接缝契约）；未知码 → SERVER_ERROR 的
 *       {@link MiaoshaException}</li>
 * </ul>
 */
class HttpGoodsClientTest {

  private static final int SERVER_ERROR_CODE = CodeMsg.SERVER_ERROR.getCode(); // 500100
  private static final int GOODS_NOT_EXIST_CODE = CodeMsg.GOODS_NOT_EXIST.getCode(); // 500104

  private static HttpServer server;
  /** 每个用例自设响应（HTTP 状态码 + 响应体），默认 200 + 成功壳。 */
  private static final AtomicReference<StubResponse> stub =
      new AtomicReference<>(StubResponse.ok("{}"));

  /** 每个请求的 URI 记录（验证 deductStock 是否把 requestId 下发给 goods-service）。 */
  private static final AtomicReference<String> lastRequestUri = new AtomicReference<>();

  private HttpGoodsClient client;

  @BeforeAll
  static void startStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          lastRequestUri.set(exchange.getRequestURI().toString());
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
    lastRequestUri.set(null);
    client = new HttpGoodsClient("http://localhost:" + server.getAddress().getPort());
  }

  /** 核心回归（Issue 2）：getGoodsVo 收到 500100 → 意外异常（可重试），绝非 MiaoshaException。 */
  @Test
  void getGoodsVo_serverError500100_rethrowsUnexpectedExceptionNotMiaosha() {
    stub.set(StubResponse.ok(resultShell(SERVER_ERROR_CODE, "服务端异常")));

    assertThatThrownBy(() -> client.getGoodsVo(2L))
        .as("goods-service 系统异常必须走意外异常语义（重试→DLT），而不是业务失败（补偿+ack）")
        .isNotInstanceOf(MiaoshaException.class)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("500100");
  }

  /** 空响应体（resp==null）同属系统异常语义，与 500100 同路处理。 */
  @Test
  void getGoodsVo_emptyBody_rethrowsUnexpectedExceptionNotMiaosha() {
    stub.set(new StubResponse(200, ""));

    assertThatThrownBy(() -> client.getGoodsVo(2L))
        .as("无响应壳 = goods-service 异常，必须可重试")
        .isNotInstanceOf(MiaoshaException.class)
        .isInstanceOf(RuntimeException.class);
  }

  /** 商品不存在（500104）：按接缝契约返回 null，由 OrderService 转 GOODS_NOT_EXIST。 */
  @Test
  void getGoodsVo_goodsNotExist_returnsNull() {
    stub.set(StubResponse.ok(resultShell(GOODS_NOT_EXIST_CODE, "商品不存在")));

    assertThat(client.getGoodsVo(999L)).isNull();
  }

  /** 未知业务码：还原为 SERVER_ERROR 的 MiaoshaException（现状语义，Issue 4 另行处理）。 */
  @Test
  void getGoodsVo_unknownCode_throwsMiaoshaServerError() {
    stub.set(StubResponse.ok(resultShell(599999, "神秘错误")));

    assertThatThrownBy(() -> client.getGoodsVo(2L))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.SERVER_ERROR);
  }

  /** 成功壳：正常还原快照字段。 */
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
    assertThat(vo.getMiaoshaPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.00));
  }

  /**
   * deductStock 收到 500100：保持 fail-fast 的 {@link MiaoshaException}（报告明示不改）——
   * 扣减存在二义性，500100 意味着 UPDATE 未提交，fail-fast 走补偿语义。
   */
  @Test
  void deductStock_serverError500100_failsFastAsMiaoshaException() {
    stub.set(StubResponse.ok(resultShell(SERVER_ERROR_CODE, "服务端异常")));

    assertThatThrownBy(() -> client.deductStock(2L, null))
        .as("deductStock 系统异常保持业务失败 fail-fast，防止与扣减二义性叠加")
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.SERVER_ERROR);
  }

  /** restoreStock 补偿回补的业务码异常同样还原为 MiaoshaException（调用方吞异常记日志）。 */
  @Test
  void restoreStock_error_throwsMiaoshaException() {
    stub.set(StubResponse.ok(resultShell(SERVER_ERROR_CODE, "服务端异常")));

    assertThatThrownBy(() -> client.restoreStock(2L))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.SERVER_ERROR);
  }

  /**
   * Issue 1 直接来源：deductStock 连接中断/被拒 → 原样上抛连接类异常（非 {@link MiaoshaException}），
   * 调用方按意外异常处理（不 ack → 重试 → DLT）；扣减结果二义由调用方不回补来体现。
   */
  @Test
  void deductStock_connectionFailure_rethrowsConnectionExceptionNotMiaosha() {
    HttpGoodsClient deadClient = new HttpGoodsClient("http://localhost:1");

    assertThatThrownBy(() -> deadClient.deductStock(2L, "req-dead-1"))
        .as("连接失败必须原样上抛连接类异常（意外异常语义），不得还原为业务失败 MiaoshaException")
        .isNotInstanceOf(MiaoshaException.class)
        .isInstanceOf(ResourceAccessException.class);
  }

  /**
   * Issue 1 契约（客户端侧）：requestId 必须以 query param 下发到 deduct-stock 端点，
   * goods-service 据此做 SETNX 短期幂等；requestId 为 null（同步降级路径）时不携带参数。
   */
  @Test
  void deductStock_sendsRequestIdAsQueryParam() {
    stub.set(StubResponse.ok("{\"code\":0,\"msg\":\"success\",\"data\":1}"));

    client.deductStock(2L, "req-abc-1");

    assertThat(lastRequestUri.get())
        .as("requestId 应作为 query param 下发，供 goods-service 幂等去重")
        .isEqualTo("/internal/goods/2/deduct-stock?requestId=req-abc-1");
  }

  /** requestId 为 null（同步降级路径，单次尝试无重放）→ 不携带 query param。 */
  @Test
  void deductStock_nullRequestId_omitsQueryParam() {
    stub.set(StubResponse.ok("{\"code\":0,\"msg\":\"success\",\"data\":1}"));

    client.deductStock(2L, null);

    assertThat(lastRequestUri.get())
        .as("requestId 为 null 时不得出现空参数")
        .isEqualTo("/internal/goods/2/deduct-stock");
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
