package com.example.seckill.service;

import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.common.MiaoshaStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 秒杀时间窗口的唯一规则实现：详情页与下单共用同一份边界语义与同一处时钟（注入 {@link Clock}）。
 *
 * <p>边界语义（全系统唯一定义处）：
 * <ul>
 *   <li>{@code startDate == null} 表示立即开始（不进入未开始分支）</li>
 *   <li>{@code endDate == null} 表示永不过期（不进入已结束分支，进行中时剩余秒数为 0）</li>
 *   <li>{@code now == startDate} 视为已开始（起始边界含端点）</li>
 *   <li>{@code now == endDate} 视为已结束（结束边界含端点，两个消费者语义一致）</li>
 * </ul>
 *
 * <p>按消费者需要提供两个薄接口，不强合成一个：详情页要「状态 + 剩余秒」，下单要「在窗内 / 业务码」。
 */
@Service
public class MiaoshaWindowService {

  private final Clock clock;

  public MiaoshaWindowService(Clock clock) {
    this.clock = clock;
  }

  /** 详情页用：解析秒杀状态与剩余秒数。 */
  public WindowStatus resolveStatus(LocalDateTime startDate, LocalDateTime endDate) {
    LocalDateTime now = LocalDateTime.now(clock);
    if (startDate != null && now.isBefore(startDate)) {
      return new WindowStatus(MiaoshaStatus.NOT_START, secondsBetween(now, startDate));
    }
    if (endDate != null && !now.isBefore(endDate)) {
      return new WindowStatus(MiaoshaStatus.OVER, 0);
    }
    int remainSeconds = endDate == null ? 0 : secondsBetween(now, endDate);
    return new WindowStatus(MiaoshaStatus.IN_PROGRESS, remainSeconds);
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

  /** 计算两个时间点之间的秒数，向下截断为 0，并防止 long→int 溢出。 */
  private int secondsBetween(LocalDateTime from, LocalDateTime to) {
    long seconds = Duration.between(from, to).getSeconds();
    if (seconds <= 0) {
      return 0;
    }
    return (int) Math.min(seconds, Integer.MAX_VALUE);
  }

  /** 窗口状态 + 剩余秒数（详情页展示用）。 */
  public record WindowStatus(int status, int remainSeconds) {}
}
