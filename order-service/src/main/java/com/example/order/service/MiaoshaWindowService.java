package com.example.order.service;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 秒杀时间窗校验（从 goods-service/单体 {@code MiaoshaWindowService} 复制的最小实现）。
 *
 * <p>跨服务禁止 import，服务自治下复制是有意取舍；边界语义与全系统唯一定义处逐行一致：
 * <ul>
 *   <li>{@code startDate == null} 表示立即开始（不进入未开始分支）</li>
 *   <li>{@code endDate == null} 表示永不过期（不进入已结束分支）</li>
 *   <li>{@code now == startDate} 视为已开始（起始边界含端点）</li>
 *   <li>{@code now == endDate} 视为已结束（结束边界含端点）</li>
 * </ul>
 *
 * <p>order-service 只做下单校验，不承担详情页展示，故仅保留 {@link #checkInWindow}。
 */
@Service
public class MiaoshaWindowService {

  private final Clock clock;

  public MiaoshaWindowService(Clock clock) {
    this.clock = clock;
  }

  /** 下单用：不在窗口内时抛出对应业务码异常。 */
  public void checkInWindow(LocalDateTime startDate, LocalDateTime endDate) {
    LocalDateTime now = LocalDateTime.now(clock);
    if (startDate != null && now.isBefore(startDate)) {
      throw new MiaoshaException(CodeMsg.MIAOSHA_NOT_START);
    }
    if (endDate != null && !now.isBefore(endDate)) {
      throw new MiaoshaException(CodeMsg.MIAOSHA_OVER);
    }
  }
}
