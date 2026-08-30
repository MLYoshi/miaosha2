package com.example.seckill.message;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试假发送器：与 Kafka 生产实现等价替换，让受理编排的发送失败降级路径脱离 Kafka 直测。
 *
 * <p>提供发送失败注入与已发送消息观测，供断言使用。
 */
public class FakeOrderMessageSender implements OrderMessageSender {

  private final List<SeckillOrderMessage> sent = new ArrayList<>();

  private RuntimeException failure;

  /** 注入后每次 {@link #send} 都抛出该异常，模拟 Kafka 发送失败。 */
  public void failSendWith(RuntimeException e) {
    this.failure = e;
  }

  @Override
  public void send(SeckillOrderMessage message) {
    if (failure != null) {
      throw failure;
    }
    sent.add(message);
  }

  /** 已成功发出的消息（按发送顺序）。 */
  public List<SeckillOrderMessage> sent() {
    return sent;
  }
}
