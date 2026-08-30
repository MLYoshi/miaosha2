package com.example.seckill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.seckill.common.CodeMsg;
import com.example.seckill.common.MiaoshaException;
import com.example.seckill.common.MiaoshaStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * 秒杀时间窗口边界单测：固定时钟，覆盖 start / end 两个边界点。
 *
 * <p>边界语义（与 {@link MiaoshaWindowService} javadoc 一致）：
 * {@code now == startDate} 已开始、{@code now == endDate} 已结束，两个消费者共用。
 */
class MiaoshaWindowServiceTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 12, 0, 0);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final MiaoshaWindowService window =
      new MiaoshaWindowService(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));

  // ---------- resolveStatus：详情页「状态 + 剩余秒」 ----------

  @Test
  void beforeStart_notStart_withRemainSeconds() {
    MiaoshaWindowService.WindowStatus s =
        window.resolveStatus(NOW.plusSeconds(90), NOW.plusHours(1));
    assertThat(s.status()).isEqualTo(MiaoshaStatus.NOT_START);
    assertThat(s.remainSeconds()).isEqualTo(90);
  }

  @Test
  void exactlyAtStart_inProgress() { // start 边界：含端点，等于即开始
    MiaoshaWindowService.WindowStatus s = window.resolveStatus(NOW, NOW.plusMinutes(5));
    assertThat(s.status()).isEqualTo(MiaoshaStatus.IN_PROGRESS);
    assertThat(s.remainSeconds()).isEqualTo(300);
  }

  @Test
  void exactlyAtEnd_over() { // end 边界：含端点，等于即结束
    MiaoshaWindowService.WindowStatus s = window.resolveStatus(NOW.minusHours(1), NOW);
    assertThat(s.status()).isEqualTo(MiaoshaStatus.OVER);
    assertThat(s.remainSeconds()).isZero();
  }

  @Test
  void afterEnd_over() {
    MiaoshaWindowService.WindowStatus s =
        window.resolveStatus(NOW.minusDays(2), NOW.minusDays(1));
    assertThat(s.status()).isEqualTo(MiaoshaStatus.OVER);
    assertThat(s.remainSeconds()).isZero();
  }

  @Test
  void nullStart_startsImmediately() {
    MiaoshaWindowService.WindowStatus s = window.resolveStatus(null, NOW.plusMinutes(10));
    assertThat(s.status()).isEqualTo(MiaoshaStatus.IN_PROGRESS);
    assertThat(s.remainSeconds()).isEqualTo(600);
  }

  @Test
  void nullEnd_neverOver_zeroRemain() {
    MiaoshaWindowService.WindowStatus s = window.resolveStatus(NOW.minusMinutes(1), null);
    assertThat(s.status()).isEqualTo(MiaoshaStatus.IN_PROGRESS);
    assertThat(s.remainSeconds()).isZero();
  }

  // ---------- checkInWindow：下单「在窗内 / 业务码」 ----------

  @Test
  void check_beforeStart_throwsNotStart() {
    assertThatThrownBy(() -> window.checkInWindow(NOW.plusDays(1), NOW.plusDays(2)))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.MIAOSHA_NOT_START);
  }

  @Test
  void check_exactlyAtStart_passes() { // 与详情页同语义：start 边界放行
    assertThatCode(() -> window.checkInWindow(NOW, NOW.plusMinutes(5)))
        .doesNotThrowAnyException();
  }

  @Test
  void check_exactlyAtEnd_throwsOver() { // 与详情页同语义：end 边界拒绝
    assertThatThrownBy(() -> window.checkInWindow(NOW.minusHours(1), NOW))
        .isInstanceOf(MiaoshaException.class)
        .extracting(e -> ((MiaoshaException) e).getCodeMsg())
        .isEqualTo(CodeMsg.MIAOSHA_OVER);
  }

  @Test
  void check_inWindow_passes() {
    assertThatCode(() -> window.checkInWindow(NOW.minusMinutes(1), NOW.plusMinutes(1)))
        .doesNotThrowAnyException();
  }

  @Test
  void check_nullBounds_passes() {
    assertThatCode(() -> window.checkInWindow(null, null)).doesNotThrowAnyException();
  }
}
