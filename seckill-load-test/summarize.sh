#!/usr/bin/env bash
# 汇总 observe.sh 产出的观测数据（results/<label>/）为可读摘要。
# 用法: ./summarize.sh results/<label>
set -uo pipefail

DIR="${1:-}"
if [ -z "$DIR" ] || [ ! -d "$DIR" ]; then
  echo "用法: $0 results/<label>"
  exit 1
fi

echo "==================== 观测摘要: $DIR ===================="

# —— 宿主 ——
if [ -f "$DIR/host.tsv" ]; then
  echo "[宿主]"
  awk -F'\t' '!/^#/{
    s+=$2; if($2>m2)m2=$2; n++
    f=$3; a=$4
  } END{
    printf "  CPU%%(ps汇总): avg=%.1f max=%.1f（注：可>100%%需按核数折算）\n", s/n, m2
    printf "  内存 free(MB)=%d active(MB)=%d\n", f, a
  }' "$DIR/host.tsv"
fi

# —— Docker 容器 ——
if [ -f "$DIR/docker-stats.tsv" ]; then
  echo "[Docker 容器]（每容器: avg/max CPU% , 平均内存）"
  awk -F'\t' '!/^#/{
    cnt[$2]++;
    gsub(/%/,"",$3); cpus[$2]+=$3; if($3>maxcpu[$2])maxcpu[$2]=$3;
    # $4 = "1.2GiB / 7.7GiB"，取数字+首字母单位换算 MB
    v=$4;
    if (match(v,/[0-9.]+/)) {
      n=substr(v,RSTART,RLENGTH)+0; u=substr(v,RSTART+RLENGTH,1);
      if (u=="G") m=n*1024; else if (u=="K") m=n/1024; else m=n;
      mems[$2]+=m;
    }
  } END{
    for(k in cnt){
      printf "  %-16s CPU%% avg=%.1f max=%.1f  mem avg=%.0fMB\n", k, cpus[k]/cnt[k], maxcpu[k], mems[k]/cnt[k]
    }
  }' "$DIR/docker-stats.tsv" | sort
fi

# —— JVM ——
if [ -f "$DIR/jvm.tsv" ]; then
  echo "[JVM]（backend 进程线程数近似 Tomcat 压力；heap 需 jcmd 可用）"
  awk -F'\t' '!/^#/{
    s+=$2; if($2>m)m=$2; n++;
    if($3!="NA"){hs+=$3; hn++}
  } END{
    printf "  线程数: avg=%.0f max=%d（样本%d）\n", s/n, m, n
    if(hn>0) printf "  堆 used(MB): avg=%.1f\n", hs/hn
    else     printf "  堆 used: NA（容器无 jcmd，需 JDK 镜像或开启 JMX）\n"
  }' "$DIR/jvm.tsv"
fi

# —— Kafka ——
if [ -f "$DIR/kafka-topic-end-offset.tsv" ]; then
  # 列: ts GROUP TOPIC PARTITION LOGEND
  echo "[Kafka 生产吞吐]（log-end-offset 差分/时长）"
  awk -F'\t' '!/^#/{
    key=$3 SUBSEP $4;
    if(!(key in first)){first[key]=$5; firstts[key]=$1}
    last[key]=$5; lastts[key]=$1
  } END{
    for(k in last){
      dt=lastts[k]-firstts[k]; if(dt<=0)dt=1
      split(k,a,SUBSEP)
      printf "  %s p%s: 首=%d 末=%d 速率=%.1f msg/s\n", a[1], a[2], first[k], last[k], (last[k]-first[k])/dt
    }
  }' "$DIR/kafka-topic-end-offset.tsv" | sort
fi

if [ -f "$DIR/kafka-consumer-lag.tsv" ]; then
  # 列: ts GROUP TOPIC PARTITION CURRENT LOGEND LAG
  echo "[Kafka 消费滞后]（每分区 max lag / 最新 lag）"
  awk -F'\t' '!/^#/{
    key=$3 SUBSEP $4;
    if($7>max[key])max[key]=$7; last[key]=$7;
  } END{
    for(k in max){
      split(k,a,SUBSEP)
      printf "  %s p%s: max_lag=%d latest_lag=%d\n", a[1], a[2], max[k], last[k]
    }
  }' "$DIR/kafka-consumer-lag.tsv" | sort
fi

# —— Redis ——
if [ -f "$DIR/redis.tsv" ]; then
  echo "[Redis]"
  awk -F'\t' '!/^#/{
    s+=$2; if($2>m)m=$2; cs+=$4; if($4>cm)cm=$4; n++; mu=$3
    # 累计 CPU 差分估算每秒 CPU 秒（≈占用核数）
  } END{
    printf "  ops/s: avg=%.0f max=%d\n", s/n, m
    printf "  connected_clients: avg=%.0f max=%d\n", cs/n, cm
    printf "  used_memory(bytes): latest=%d (%.1fMB)\n", mu, mu/1048576
  }' "$DIR/redis.tsv"
fi

# —— MySQL ——
if [ -f "$DIR/mysql-status.tsv" ]; then
  echo "[MySQL]"
  awk -F'\t' '!/^#/{
    tcs+=$2; if($2>tcm)tcm=$2;
    trs+=$3; if($3>trm)trm=$3; n++
    if(!(1 in f)){f[1]=$4; ft=$1}
    l=$4; lt=$1; sl=$5
  } END{
    dt=lt-ft; if(dt<=0)dt=1
    printf "  QPS(Queries差分)=%.1f  Threads_connected: avg=%.0f max=%d\n", (l-f[1])/dt, tcs/n, tcm
    printf "  Threads_running: avg=%.1f max=%d  Slow_queries累计增量=%d\n", trs/n, trm, sl
  }' "$DIR/mysql-status.tsv"
fi

echo "=========================================================="
echo "原始数据: $DIR/*.tsv（ts 为 epoch 秒）"
echo "下一步: 与 k6 业务指标（accept_latency / accept_status / result_status）对照分析瓶颈。"
