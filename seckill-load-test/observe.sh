#!/usr/bin/env bash
# 独立性能观测采集器（职责边界：与 k6 解耦）。
#
# k6 = 产生压力 + 记录业务层指标（延迟/失败/业务 Counter）
# 本脚本 = 记录 JVM / Tomcat / Kafka / Redis / MySQL / 容器资源指标，不参与压测
#
# 用法：
#   ./prepare.sh spike
#   ./observe.sh --label spike-baseline --interval 5 --duration 600 &   # 后台采集（600s）
#   k6 run spike.js
#   wait                       # 等采集结束
#   ./summarize.sh results/spike-baseline
#
# 参数：
#   --label NAME    输出目录名（默认 run-<时间戳>）
#   --interval N    采样间隔秒（默认 5）
#   --duration N    采集时长秒；0 = 直到 Ctrl-C 手动停止（默认 0）
#   --out DIR       输出根目录（默认 results/）
#
# 输出：results/<label>/ 下各组件 tsv（ts 为 epoch 秒，供差分算速率）：
#   host.tsv                  宿主 CPU 汇总 / 内存（vm_stat）
#   docker-stats.tsv          各容器 CPU% / MemUsage / Mem%
#   jvm.tsv                   容器内 java 进程线程数 / 堆 used（jcmd 可用时）
#   kafka-consumer-lag.tsv    consumer group seckill 的 partition/offset/lag
#   kafka-topic-end-offset.tsv topic 各分区 log-end-offset（差分 = 生产吞吐）
#   redis.tsv                 ops/s / used_memory / connected_clients / cpu
#   mysql-status.tsv          Threads_connected / Threads_running / Queries / Slow_queries
#
# 说明：Tomcat 无暴露端点时用「进程线程数」近似；如需精确活跃线程，
#       需在 backend 开启 actuator 或 JMX（见 README「观测采集」）。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

LABEL=""
INTERVAL=5
DURATION=0
OUT_DIR="results"

while [ $# -gt 0 ]; do
  case "$1" in
    --label)    LABEL="$2";    shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --out)      OUT_DIR="$2";  shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

[ -z "$LABEL" ] && LABEL="run-$(date +%Y%m%d-%H%M%S)"
DIR="$OUT_DIR/$LABEL"
mkdir -p "$DIR"

echo "# ts topic partition current_offset log_end_offset lag" > "$DIR/kafka-consumer-lag.tsv"
echo "# ts topic partition log_end_offset" > "$DIR/kafka-topic-end-offset.tsv"
echo "# ts ops_per_sec used_memory_bytes connected_clients cpu_sys cpu_user" > "$DIR/redis.tsv"
echo "# ts threads_connected threads_running queries_total slow_queries_total" > "$DIR/mysql-status.tsv"
echo "# ts threads heap_used_mb" > "$DIR/jvm.tsv"
echo "# ts host_cpu_percent free_mem_mb active_mem_mb" > "$DIR/host.tsv"
echo "# ts container cpu_percent mem_usage mem_percent" > "$DIR/docker-stats.tsv"

# —— 各组件采样函数：失败静默跳过，不中断主循环 ——

sample_host() {
  local ts=$(date +%s)
  local cpu=$(ps -Ao %cpu 2>/dev/null | awk 'NR>1{s+=$1} END{printf "%.1f", s}')
  local page=$(sysctl -n hw.pagesize 2>/dev/null || echo 16384)
  local vm
  vm=$(vm_stat 2>/dev/null || true)
  local free=$(echo "$vm" | awk -F: '/Pages free/{gsub(/[^0-9]/,"",$2); print $2}')
  local active=$(echo "$vm" | awk -F: '/Pages active/{gsub(/[^0-9]/,"",$2); print $2}')
  [ -z "$free" ] && free=0
  [ -z "$active" ] && active=0
  echo -e "$ts\t$cpu\t$((free*page/1024/1024))\t$((active*page/1024/1024))" >> "$DIR/host.tsv"
}

sample_docker_stats() {
  local ts=$(date +%s)
  docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' 2>/dev/null |
    while IFS=$'\t' read -r name cpu mem memp; do
      echo -e "$ts\t$name\t$cpu\t$mem\t$memp" >> "$DIR/docker-stats.tsv"
    done
}

sample_jvm() {
  local ts=$(date +%s)
  local pid threads heap=""
  pid=$(docker exec seckill-backend sh -c "pgrep -f 'java' | head -1" 2>/dev/null || true)
  [ -z "$pid" ] && return 0
  threads=$(docker exec seckill-backend sh -c "grep '^Threads:' /proc/$pid/status 2>/dev/null | awk '{print \$2}'" 2>/dev/null || true)
  [ -z "$threads" ] && return 0
  # jcmd 可用（JDK 镜像）时取堆 used；JRE 镜像则留 NA
  if docker exec seckill-backend sh -c "jcmd $pid GC.heap_info" >/dev/null 2>&1; then
    heap=$(docker exec seckill-backend sh -c "jcmd $pid GC.heap_info 2>/dev/null" | awk -F: '/used /{gsub(/[^0-9.]/,"",$2); printf "%.1f", $2/1048576; exit}')
  fi
  echo -e "$ts\t$threads\t${heap:-NA}" >> "$DIR/jvm.tsv"
}

sample_kafka() {
  local ts=$(date +%s)
  docker exec seckill-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --group seckill --describe 2>/dev/null |
    awk -v ts="$ts" -v d="$DIR" '
      $2 != "TOPIC" && NF >= 6 && $1 != "" && $1 != "Consumer" {
        # 源列: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG ...
        # lag 文件列: ts GROUP TOPIC PARTITION CURRENT LOGEND LAG
        printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts, $1, $2, $3, $4, $5, $6 >> (d "/kafka-consumer-lag.tsv")
        # end-offset 文件列: ts GROUP TOPIC PARTITION LOGEND
        printf "%s\t%s\t%s\t%s\t%s\n", ts, $1, $2, $3, $5 >> (d "/kafka-topic-end-offset.tsv")
      }'
}

sample_redis() {
  local ts=$(date +%s)
  local ops mem clients csys cusr
  ops=$(docker exec seckill-redis redis-cli -a 123456 --no-auth-warning INFO stats 2>/dev/null | awk -F: '/^instantaneous_ops_per_sec:/{gsub(/[^0-9]/,"",$2); print $2}')
  mem=$(docker exec seckill-redis redis-cli -a 123456 --no-auth-warning INFO memory 2>/dev/null | awk -F: '/^used_memory:/{gsub(/[^0-9]/,"",$2); print $2}')
  clients=$(docker exec seckill-redis redis-cli -a 123456 --no-auth-warning INFO clients 2>/dev/null | awk -F: '/^connected_clients:/{gsub(/[^0-9]/,"",$2); print $2}')
  csys=$(docker exec seckill-redis redis-cli -a 123456 --no-auth-warning INFO cpu 2>/dev/null | awk -F: '/^used_cpu_sys:/{gsub(/[^0-9.]/,"",$2); print $2}')
  cusr=$(docker exec seckill-redis redis-cli -a 123456 --no-auth-warning INFO cpu 2>/dev/null | awk -F: '/^used_cpu_user:/{gsub(/[^0-9.]/,"",$2); print $2}')
  echo -e "$ts\t$ops\t$mem\t$clients\t$csys\t$cusr" >> "$DIR/redis.tsv"
}

sample_mysql() {
  local ts=$(date +%s)
  local s tc tr q sl
  s=$(docker exec seckill-mysql mysql -uroot -proot -N -e \
      "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Queries','Slow_queries');" 2>/dev/null || true)
  tc=$(echo "$s" | awk '$1=="Threads_connected"{print $2}')
  tr=$(echo "$s" | awk '$1=="Threads_running"{print $2}')
  q=$(echo "$s" | awk '$1=="Queries"{print $2}')
  sl=$(echo "$s" | awk '$1=="Slow_queries"{print $2}')
  echo -e "$ts\t${tc:-0}\t${tr:-0}\t${q:-0}\t${sl:-0}" >> "$DIR/mysql-status.tsv"
}

# —— 主循环 ——
trap 'echo "采集被手动停止（Ctrl-C）。输出: $DIR"; exit 0' INT TERM

echo "==> 开始观测采集: label=$LABEL interval=${INTERVAL}s duration=${DURATION}s（0=手动停止）"
echo "==> 输出目录: $DIR"
echo "==> 提示: 现在并行运行 k6，如: k6 run spike.js"

n=0
start=$(date +%s)
while :; do
  now=$(date +%s)
  if [ "$DURATION" -gt 0 ] && [ $((now - start)) -ge "$DURATION" ]; then
    break
  fi
  sample_host & sample_docker_stats & sample_jvm & sample_kafka & sample_redis & sample_mysql &
  wait
  n=$((n + 1))
  [ "$DURATION" -gt 0 ] && echo "  已采样 ${n} 次 / 目标 ${DURATION}s"
  sleep "$INTERVAL"
done

echo "==> 采集结束: 共 ${n} 次采样。输出: $DIR"
echo "==> 查看摘要: ./summarize.sh $DIR"
