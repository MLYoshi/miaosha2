# 秒杀系统消息层设计说明（Kafka）

> 本文档描述秒杀系统的消息层设计，用于让 AI 编码助手/Agent 快速理解异步主链路的消息编排。
> 与 `db-design.md` 同源：一份面向 Agent 的决策记录（决策 + 理由 + 注意事项），非入门教程。
> 历史说明：消息层已由 RabbitMQ 迁至 Kafka（KRaft 单节点，无 ZooKeeper），本文档为唯一消息层事实来源。

## 1. 总览

异步主链路把 DB 写入移出请求路径，标准削峰交互：

```
用户提交秒杀
   │
   ▼
受理（Redis Lua 原子预扣库存，标记 PROCESSING）   ← 请求路径终点，立即返回
   │  预扣成功
   ▼
Kafka `seckill-order` 投递（生产者 acks=all + 幂等）
   │
   ▼
消费者落库（幂等快跳 → DB 事务下单 → 回写结果 / 补偿）
   │
   ▼
前端轮询 GET /miaosha/result 拿单（PROCESSING / SUCCESS / FAILED / 无记录）
```

Redis 是第一道库存拦截与防重复屏障；Kafka 负责削峰排队；DB 仍是库存与订单的最终事实来源。

## 2. Topic / 分区 / 副本

| topic | 用途 | 分区 | 副本 | 消费组 |
|---|---|---|---|---|
| `seckill-order` | 下单请求排队、异步下单唯一入口 | 3 | 1 | `seckill` |
| `seckill-order-dlt` | 重试耗尽的毒消息归宿（死信） | 3 | 1 | —（人工介入 / 重放） |

**决策**：分区数 3、副本因子 1。

**理由**：本地为 KRaft 单节点部署，副本因子只能为 1（无多 broker 可复制）；分区 3 提供基本并行消费能力。

**注意事项**：两个 topic 都由 `KafkaConfig` 的 `NewTopic` Bean 随应用启动自动创建；broker 已存在同名 topic 时沿用既有分区/副本，不强制重设。分区数 3 是写死常量，改动需同步改死信 topic 的分区对齐逻辑。

## 3. 消息 Key 策略（不用 goodsId 的热点考量）

**决策**：消息 key 用 `UUID.randomUUID()` 随机打散，**不用 goodsId**。

**理由**：秒杀的核心特征是热点商品。若用 goodsId 作 key，同一热门商品的所有下单请求会被哈希到同一个分区——单分区被打爆、消费者并行度失效、其余分区闲置，削峰能力退化为单队列。随机 key 让请求均匀散到 3 个分区，消费者按分区并行落库。

**代价与兜底**：随机 key 意味着**不保证同 key 有序**，但本业务不需要跨消息顺序性——同一用户同一商品的防重复/防超卖由「DB 条件扣库存 + 唯一键兜底」保证，与消息顺序无关。

## 4. 消息体

`SeckillOrderMessage`（JSON 序列化）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `userId` | Long | 谁下单 |
| `goodsId` | Long | 买什么 |
| `requestId` | String | 哪次请求——全链路去重标识，受理时 `UUID` 生成，补偿时凭它校验归属 |

**注意事项**：消息体只承载下单所需的最小信息，价格 / 库存 / 时间窗等都在消费者落库时从 DB 现取快照，避免消息携带易过期数据。`requestId` 是幂等补偿的关键锚点，缺了它无法区分「我的失败补偿」和「别的请求」。

## 5. 可靠性语义（四条）

消息层可靠性由「发送失败降级 → 业务失败补偿 → 意外异常重试+死信 → 幂等兜底」四层闭环构成，缺一不可。

### 5.1 发送失败降级（受理侧）

**决策**：预扣成功后 `sender.send()` 失败（broker 不可达 / 超时）→ 降级同步落库 `MiaoshaService.createOrder` → 回写 `SUCCESS:{orderId}` → 用户直接拿单。

**理由**：沿用 Redis 降级哲学——用户的一次秒杀请求不能因为 MQ 抖动而失败。MQ 不可用时退回同步落库，牺牲削峰但不牺牲正确性。

**注意事项**：降级落库本身也可能失败（如 DB 也抖），此时须 `store.compensate` 回补 Redis 库存、清标记，再上抛错误，避免「预扣了库存却没下单」的库存泄漏。降级成功后，原消息可能「迟到」重新送达（发送其实已成功只是确认超时），见 5.4 幂等兜底。

### 5.2 业务失败补偿（消费侧）

**决策**：消费落库抛 `MiaoshaException`（确定性业务失败：重复下单 / 库存空 / 时间窗外 / 商品不存在）→ `store.compensate`（回补库存 INCR、清 user 标记、result=FAILED）→ 正常返回 → listener ack，**不重试**。

**理由**：业务失败是确定性的，重试 N 次结果不变，只会空耗；补偿把 Redis 状态从 PROCESSING 拉回 FAILED，用户轮询得到明确失败而非一直排队。

**注意事项**：补偿前先查结果标记，若已 `SUCCESS`（降级已落库，本条是迟到重复消息）则跳过补偿，避免把已成功的库存又回补出去。补偿必须用 `requestId` 校验归属（Lua 内比对），防止误伤其他请求。

### 5.3 意外异常重试 + 死信（消费侧）

**决策**：非 `MiaoshaException` 的意外异常（DB 连接抖动、临时故障）→ listener 不 ack 直接上抛 → 容器错误处理指数退避重试 3 次（1s → 2s → 4s）→ 耗尽后发布到 `seckill-order-dlt` → 记日志（userId / goodsId / requestId）→ 位点继续推进。

**理由**：毒消息不能卡死消费主循环——一条坏消息反复失败会让同分区后续所有消息停摆。有限重试兜住临时抖动，死信把「无法自动处理」的消息显式隔离出来，可观测、可人工重放。

**注意事项**：死信分区与源 topic 分区对齐（`DeadLetterPublishingRecoverer` 映射到 `record.partition()`）。死信语义为「人工介入后可重放」，不要写自动消费回灌逻辑，否则会掩盖根因。

### 5.4 幂等兜底（贯穿全程）

Kafka 是**至少一次**投递 + 重试，重复消息不可避免，靠多道防线层层兜底：

1. **幂等快跳**：消费前先查结果标记，已 `SUCCESS` → 直接 ack 跳过，不再碰 DB。
2. **DB 唯一键**：`miaosha_order (user_id, goods_id)` 唯一索引是结果标记丢失时的最终兜底——并发下也靠它拦下重复订单。
3. **迟到重复消息识别**：降级同步落库后迟到的重复消息，DB 唯一键拦下后识别「已成功」，跳过补偿，不产生重复订单、库存不泄漏。

**理由**：结果标记在 Redis（有 TTL、可能丢），DB 才是最终事实来源，所以幂等不能只靠 Redis 一层。

## 6. 关键配置速查（application.yaml）

```yaml
spring.kafka:
  bootstrap-servers: localhost:9092
  producer:
    acks: all                                 # 可靠发送：等所有副本确认
    properties.enable.idempotence: true       # 幂等生产者
    key-serializer: StringSerializer
    value-serializer: JsonSerializer
  consumer:
    group-id: seckill
    enable-auto-commit: false                 # 手动 ack
    key-deserializer: StringDeserializer
    value-deserializer: JsonDeserializer
    properties.spring.json.trusted.packages: com.example.seckill.*
  listener:
    ack-mode: manual                          # 显式 Acknowledgment.acknowledge()
```

## 7. Agent 注意事项（写代码时容易踩的坑）

1. **消息 key 不要改回 goodsId**——热点商品会打爆单分区，随机 key 是既定决策。
2. **业务失败用 `MiaoshaException` 抛**，不要包成 `RuntimeException`；否则会被当作意外异常重试 3 次再进死信，白耗且误导。
3. **消费必须手动 ack**：成功与业务失败两条路径都要 ack，只有意外异常才上抛不 ack（交给容器错误处理）。
4. **回写是尽力而为**：`markSuccess` / `compensate` 不得向调用方抛异常，回写失败不能破坏已完成的 DB 事实。
5. **发送方阻塞等待确认**：`kafkaTemplate.send(...).join()`，失败会抛异常由受理方降级，不要吞掉。
6. **分区数是常量**：`KafkaConfig` 中 3 分区、副本 1 与死信 topic 对齐，改动要一起改。
