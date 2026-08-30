package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** 管理端接缝契约：预热是写操作，仅 POST，且领域错误显式映射（不兜底 500100）。 */
class AdminApiTest extends AbstractIntegrationTest {

  @Test // 契约：预热不存在的商品 → 领域错误 500104，而非兜底 500100
  void preheatNonExistentGoods_returnsDomainError() {
    long operator = insertUser(13000000600L);

    JsonNode resp = body(post("/admin/preheat?goodsId=999999", operator));
    assertThat(resp.get("code").asInt()).as(resp.toString()).isEqualTo(CODE_GOODS_NOT_EXIST);
  }

  @Test // 契约：预热是写操作，GET 一律 405
  void preheat_rejectsGet() {
    long operator = insertUser(13000000601L);

    assertThat(get("/admin/preheat?goodsId=1", operator).getStatusCode().value())
        .as("GET preheat 应返回 405")
        .isEqualTo(405);
  }

  @Test // 契约：预热成功写 Redis 库存
  void preheatWritesRedisStock() {
    long operator = insertUser(13000000602L);
    long goodsId = insertGoods("iphoneX", 9);

    JsonNode resp = body(post("/admin/preheat?goodsId=" + goodsId, operator));
    assertThat(resp.get("code").asInt()).as(resp.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(redisStock(goodsId)).isEqualTo(9);
  }
}
