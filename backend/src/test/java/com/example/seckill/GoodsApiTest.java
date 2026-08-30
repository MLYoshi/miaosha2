package com.example.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.seckill.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** F4：商品列表 / 详情。 */
class GoodsApiTest extends AbstractIntegrationTest {

  @Test // F4 列表与详情数据正确；详情不存在 → 500104
  void listAndDetail() {
    long user = insertUser(13000000010L);
    long goodsId = insertGoods("iphoneX", 9);

    JsonNode list = body(get("/goods/list", user));
    assertThat(list.get("code").asInt()).as(list.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(list.get("data").size()).isEqualTo(1);
    JsonNode g = list.get("data").get(0);
    assertThat(g.get("goodsName").asText()).isEqualTo("iphoneX");
    assertThat(g.get("stockCount").asInt()).isEqualTo(9);
    assertThat(g.get("miaoshaPrice").decimalValue())
        .isEqualByComparingTo(new BigDecimal("0.01"));

    JsonNode detail = body(get("/goods/detail/" + goodsId, user));
    assertThat(detail.get("code").asInt()).as(detail.toString()).isEqualTo(CODE_SUCCESS);
    assertThat(detail.get("data").get("goods").get("goodsName").asText()).isEqualTo("iphoneX");

    JsonNode missing = body(get("/goods/detail/999999", user));
    assertThat(missing.get("code").asInt()).isEqualTo(CODE_GOODS_NOT_EXIST);
  }
}
