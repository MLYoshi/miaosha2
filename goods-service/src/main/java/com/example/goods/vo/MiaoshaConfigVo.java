package com.example.goods.vo;

import java.time.LocalDateTime;

/** 秒杀配置重置后的回显（管理端接口 + 内部接口共用）。 */
public record MiaoshaConfigVo(
    Long goodsId, LocalDateTime startDate, LocalDateTime endDate, Integer stockCount) {}
