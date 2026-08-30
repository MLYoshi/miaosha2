#!/usr/bin/env bash
# 压测前准备：对齐 goodsId 时间窗 + 设置库存 + 清理历史订单 + 清空 Redis 秒杀 key +
# 丢弃 Kafka 积压（reset offset + 重启 backend 让消费者从最新位点订阅）。
#
# 用法：
#   ./prepare.sh spike        # 库存 10000（spike.js 用）
#   ./prepare.sh correctness  # 库存 100（correctness.js 用，须与 CORRECTNESS_STOCK 一致）
#
# 环境变量：GOODS_ID（默认 1）、STOCK（覆盖该档默认库存）
set -euo pipefail

GOODS_ID="${GOODS_ID:-1}"
MODE="${1:-}"

case "$MODE" in
  spike)       STOCK="${STOCK:-10000}" ;;
  correctness) STOCK="${STOCK:-100}" ;;
  *)
    echo "用法: $0 [spike|correctness]  （环境变量: GOODS_ID / STOCK）"
    exit 1
    ;;
esac

# 等 backend 恢复健康（actuator 仅暴露 health,info）
wait_backend_healthy() {
  echo -n "==> 等待 backend 恢复健康"
  for _ in $(seq 1 90); do
    if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
      echo " OK"
      return 0
    fi
    sleep 1
  done
  echo " FAILED（backend 未在 90s 内恢复）"
  return 1
}

MYSQL=(docker exec -i seckill-mysql mysql -uroot -proot --default-character-set=utf8mb4 miaosha)

echo "==> [1/4] 清理 goodsId=${GOODS_ID} 历史订单（避免唯一键触发 REPEAT）"
"${MYSQL[@]}" <<SQL
SET time_zone = '+08:00';
DELETE FROM miaosha_order WHERE goods_id = ${GOODS_ID};
DELETE FROM order_info   WHERE goods_id = ${GOODS_ID};
SQL

echo "==> [2/4] 对齐时间窗（now-1h ~ now+1d）并设置库存 stock=${STOCK}"
"${MYSQL[@]}" <<SQL
SET time_zone = '+08:00';
UPDATE miaosha_goods
   SET stock_count = ${STOCK},
       start_date  = NOW() - INTERVAL 1 HOUR,
       end_date    = NOW() + INTERVAL 1 DAY
 WHERE goods_id = ${GOODS_ID};
SELECT goods_id, stock_count, start_date, end_date
  FROM miaosha_goods WHERE goods_id = ${GOODS_ID};
SQL

echo "==> [3/4] 清空 Redis 秒杀 key（miaosha:*，含用户抢购标记，防旧标记导致 REPEAT）"
docker exec seckill-redis sh -c \
  "redis-cli -a 123456 --no-auth-warning --scan --pattern 'miaosha:*' | xargs -r redis-cli -a 123456 --no-auth-warning DEL"

echo "==> [4/4] 丢弃 Kafka 积压（seckill-order / seckill-order-dlt）并重启 backend"
# 上一轮压测可能残留未消费的消息：若不清，消费者会继续落库/补偿，
# 污染本轮库存（上一轮 spike 遗留消息扣光 DB 库存并回灌 Redis 即此原因）。
# 方案：消费组 offset 重置到 latest（跳过积压）+ 重启 backend 使消费者重新订阅最新位点。
docker exec seckill-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group seckill \
  --topic seckill-order --topic seckill-order-dlt \
  --reset-offsets --to-latest --execute >/dev/null 2>&1 \
  || echo "  （消费组不存在或位点已最新，跳过 reset）"
docker restart seckill-backend >/dev/null && wait_backend_healthy

echo "==> 完成。Redis 库存由 k6 脚本 setup 内调用 /admin/preheat 预热，直接运行：k6 run ${MODE}.js"
