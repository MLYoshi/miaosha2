package com.example.miaosha;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.miaosha.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 管理端重置秒杀接口集成测试（Testcontainers Redis + Kafka，真实 HTTP）。
 *
 * <p>测试边界与既有基座一致：goods-service 未启动（goods.base-url 指向死端口），
 * 因此只覆盖「下游不可达」的降级路径——编排入口必须把失败转成结构化错误
 * （HTTP 200 + code=500100），且绝不触碰 Redis 预扣 Key（落库失败 → 不预热）。
 *
 * <p>正常编排路径（落库 → 预热）由 AdminControllerResetMiaoshaTest 单测覆盖。
 */
class AdminMiaoshaConfigApiTest extends AbstractIntegrationTest {

  @Test // goods-service 不可达：结构化服务端错误，Redis 无任何秒杀 Key 写入
  void goodsServiceDown_returnsStructuredError_withoutTouchingRedis() {
    long goodsId = 8800L;

    JsonNode resp =
        body(post("/admin/goods/" + goodsId + "/miaosha?durationMinutes=60&stockCount=100", 0L));

    assertThat(resp.get("code").asInt())
        .as("下游落库失败应返回统一服务端错误（HTTP 200 + 业务码）")
        .isEqualTo(CODE_SERVER_ERROR);
    assertThat(redisStock(goodsId))
        .as("落库失败不得预热 Redis（库存 Key 应不存在，-1 表示无 Key）")
        .isEqualTo(-1);
  }

  @Test // 缺省参数（durationMinutes 默认 60、stockCount 可选）：请求合法，失败同样发生在下游
  void defaultParams_stillReachableAndStructuredError() {
    long goodsId = 8810L;

    JsonNode resp = body(post("/admin/goods/" + goodsId + "/miaosha", 0L));

    assertThat(resp.get("code").asInt()).isEqualTo(CODE_SERVER_ERROR);
    assertThat(redisStock(goodsId)).isEqualTo(-1);
  }
}
