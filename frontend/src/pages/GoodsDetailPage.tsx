import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ImageOff, Timer, Zap } from 'lucide-react';

import { getGoodsDetail } from '@/api/goods';
import { doMiaosha, getMiaoshaResult } from '@/api/miaosha';
import { ResultDialog, type ResultDialogState } from '@/components/miaosha/ResultDialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { statusMeta, useCountdown } from '@/hooks/useCountdown';
import { usePolling } from '@/hooks/usePolling';
import { formatDateTime } from '@/lib/utils';
import type { GoodsDetailVo, MiaoshaResultVo } from '@/types/api';

/**
 * 商品详情 + 抢购：详情（GET /goods/detail/:id）→ 倒计时（useCountdown）→
 * 抢购（POST /miaosha/do_miaosha）→ PROCESSING 后弹层排队 + 轮询拿单
 * （usePolling 每 1.5s 轮询 GET /miaosha/result，30s 超时上限）。
 *
 * 倒计时状态本地流转（未开始→进行中 / 进行中→已结束）时做「静默刷新」，
 * 仅同步服务端数据，不重挂载组件，保证进行中的轮询与弹层不被打断。
 */

const POLL_INTERVAL_MS = 1500;
const POLL_TIMEOUT_MS = 30_000;

export default function GoodsDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const goodsId = Number(id);

  const [detail, setDetail] = useState<GoodsDetailVo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [imgError, setImgError] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [dialog, setDialog] = useState<ResultDialogState | null>(null);

  const load = useCallback(
    async (silent = false) => {
      if (Number.isNaN(goodsId)) {
        setError(true);
        setLoading(false);
        return;
      }
      if (!silent) {
        setLoading(true);
        setError(false);
      }
      try {
        const fresh = await getGoodsDetail(goodsId);
        setDetail(fresh);
        setImgError(false);
      } catch {
        // 业务错误（含会话失效跳登录）已由请求拦截器统一 toast；静默刷新失败保持现状
        if (!silent) setError(true);
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [goodsId],
  );

  useEffect(() => {
    void load();
  }, [load]);

  // 倒计时：由服务端 miaoshaStatus / remainSeconds 初始化，本地每秒流转；
  // 状态流转时静默重拉详情对齐服务端（不重挂载，不打断轮询）。
  const countdown = useCountdown(detail?.miaoshaStatus ?? 0, detail?.remainSeconds ?? 0, {
    endTime: detail?.goods.endDate,
    onStatusChange: () => {
      if (detail != null) void load(true);
    },
  });

  // ---- 结果轮询：SUCCESS / FAILED 终止，超时按失败收场 ----
  const dialogRef = useRef<ResultDialogState | null>(null);
  dialogRef.current = dialog;

  const { polling, start: startPolling, stop: stopPolling } = usePolling<MiaoshaResultVo>({
    query: () => getMiaoshaResult(goodsId),
    isFinal: (r) => r.status === 'SUCCESS' || r.status === 'FAILED',
    intervalMs: POLL_INTERVAL_MS,
    timeoutMs: POLL_TIMEOUT_MS,
    onFinal: (r) => {
      if (r.status === 'SUCCESS') {
        setDialog({ kind: 'success', orderId: r.orderId });
      } else {
        setDialog({ kind: 'failed', reason: '本场商品已被抢空，下次活动再接再厉' });
      }
      // 出单后库存/状态可能变化，静默同步一次
      if (detail != null) void load(true);
    },
    onTimeout: () => {
      setDialog({ kind: 'failed', reason: '排队超时，系统繁忙，请稍后重试' });
    },
  });

  // ---- 抢购 ----
  const handleBuy = async () => {
    if (submitting || polling) return;
    setSubmitting(true);
    try {
      const accept = await doMiaosha(goodsId);
      if (accept.status === 'SUCCESS') {
        // 同步降级落库成功，直接展示订单号
        setDialog({ kind: 'success', orderId: accept.orderId });
        void load(true);
      } else {
        // PROCESSING：Redis 预扣成功、消息已入队，弹层排队并轮询结果
        setDialog({ kind: 'queueing' });
        startPolling();
      }
    } catch {
      // 业务错误（未开始/已结束/重复秒杀/库存不足等）由拦截器统一 toast
    } finally {
      setSubmitting(false);
    }
  };

  const closeDialog = () => {
    // 排队中关闭 = 放弃本轮等待，停止轮询
    if (dialogRef.current?.kind === 'queueing') stopPolling();
    setDialog(null);
  };

  // ---- 渲染 ----
  if (loading) {
    return (
      <div className="mx-auto max-w-4xl py-4">
        <Card className="overflow-hidden border-white/60 shadow-lg shadow-orange-100/50">
          <CardContent className="flex flex-col gap-6 p-6 md:flex-row">
            <div className="aspect-square w-full animate-pulse rounded-lg bg-muted md:w-2/5" />
            <div className="flex-1 space-y-4 py-2">
              <div className="h-7 w-3/4 animate-pulse rounded bg-muted" />
              <div className="h-5 w-1/2 animate-pulse rounded bg-muted" />
              <div className="h-10 w-full animate-pulse rounded bg-muted" />
              <div className="h-11 w-40 animate-pulse rounded bg-muted" />
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (error || detail == null) {
    return (
      <div className="mx-auto max-w-4xl py-4">
        <Card className="border-white/60 shadow-lg shadow-orange-100/50">
          <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
            <p className="text-sm text-muted-foreground">商品加载失败或不存在，请稍后重试</p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={() => navigate('/goods')}>
                <ArrowLeft className="h-4 w-4" />
                返回会场
              </Button>
              {!Number.isNaN(goodsId) && (
                <Button size="sm" onClick={() => void load()}>
                  重新加载
                </Button>
              )}
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  const goods = detail.goods;
  const status = countdown.status;
  const meta = statusMeta(status);
  const soldOut = goods.stockCount <= 0;
  const canBuy = status === 1 && !soldOut && !submitting && !polling;

  const buyText = submitting
    ? '提交中…'
    : polling
      ? '排队中…'
      : status === 0
        ? '未开始'
        : status === 2
          ? '已结束'
          : soldOut
            ? '已抢光'
            : '立即抢购';

  const discount =
    goods.goodsPrice > 0 ? Math.max(1, Math.round((goods.miaoshaPrice / goods.goodsPrice) * 10)) : 10;

  return (
    <div className="mx-auto max-w-4xl space-y-4 py-4">
      <Button variant="ghost" size="sm" className="-ml-2 text-muted-foreground" onClick={() => navigate('/goods')}>
        <ArrowLeft className="h-4 w-4" />
        返回会场
      </Button>

      <Card className="overflow-hidden border-white/60 shadow-lg shadow-orange-100/50">
        <CardContent className="flex flex-col gap-6 p-6 md:flex-row">
          {/* 左：大图 */}
          <div className="relative aspect-square w-full shrink-0 overflow-hidden rounded-xl bg-gradient-to-br from-orange-50 to-red-50 md:w-2/5">
            {!imgError && goods.goodsImg ? (
              <img
                src={goods.goodsImg}
                alt={goods.goodsName}
                onError={() => setImgError(true)}
                className="h-full w-full object-cover"
              />
            ) : (
              <div className="flex h-full w-full flex-col items-center justify-center gap-2 text-orange-300">
                <ImageOff className="h-12 w-12" />
                <span className="text-xs">图片暂不可用</span>
              </div>
            )}
            <Badge
              className={
                meta.tone === 'active'
                  ? 'absolute left-3 top-3 border-none bg-gradient-to-r from-orange-500 to-red-500 text-white shadow'
                  : meta.tone === 'upcoming'
                    ? 'absolute left-3 top-3 border-none bg-amber-100 text-amber-700 shadow'
                    : 'absolute left-3 top-3 border-none bg-black/60 text-white shadow'
              }
            >
              {status === 1 && <Zap className="mr-0.5 h-3 w-3" />}
              {meta.text}
            </Badge>
          </div>

          {/* 右：信息面板 */}
          <div className="flex min-w-0 flex-1 flex-col gap-4">
            <div>
              <h1 className="text-xl font-bold leading-snug">{goods.goodsName}</h1>
              <p className="mt-1 text-sm text-muted-foreground">{goods.goodsTitle}</p>
            </div>

            {/* 价格对比 */}
            <div className="flex items-end gap-3 rounded-lg bg-gradient-to-r from-orange-50 to-red-50 px-4 py-3">
              <span className="text-3xl font-bold text-primary">
                <span className="text-base">¥</span>
                {goods.miaoshaPrice}
              </span>
              <span className="pb-1 text-sm text-muted-foreground line-through">¥{goods.goodsPrice}</span>
              {discount < 10 && (
                <Badge className="mb-1 ml-auto border-none bg-black/60 text-white">{discount} 折</Badge>
              )}
            </div>

            {/* 倒计时 / 时间窗 */}
            <div className="space-y-2 rounded-lg border border-dashed border-orange-200 bg-white/70 px-4 py-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Timer className="h-4 w-4 text-orange-500" />
                {status === 0 && <span>距开始还有</span>}
                {status === 1 && <span>距结束还有</span>}
                {status === 2 && <span>活动已结束</span>}
              </div>
              {status !== 2 && (
                <div className="font-mono text-2xl font-bold tabular-nums tracking-wider text-primary">
                  {countdown.label}
                </div>
              )}
              <p className="text-xs text-muted-foreground">
                活动时间：{formatDateTime(goods.startDate)} ~ {formatDateTime(goods.endDate)}
              </p>
            </div>

            {/* 库存 */}
            <div className="flex items-center justify-between text-sm">
              <span className="text-muted-foreground">秒杀库存</span>
              <span className={soldOut ? 'font-medium text-destructive' : 'font-medium'}>
                {soldOut ? '已被抢光' : `仅剩 ${goods.stockCount} 件`}
              </span>
            </div>

            {/* 商品详情（纯文本摘要） */}
            {goods.goodsDetail && (
              <p className="text-sm leading-6 text-muted-foreground">
                {goods.goodsDetail.length > 120 ? `${goods.goodsDetail.slice(0, 120)}…` : goods.goodsDetail}
              </p>
            )}

            {/* 抢购按钮 */}
            <Button
              size="lg"
              disabled={!canBuy}
              onClick={() => void handleBuy()}
              className={
                canBuy
                  ? 'bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-lg shadow-orange-200 transition-all hover:-translate-y-0.5 hover:shadow-xl'
                  : ''
              }
            >
              {canBuy && <Zap className="h-4 w-4" />}
              {buyText}
            </Button>
            {status === 1 && !soldOut && (
              <p className="text-xs text-muted-foreground">
                提交后进入排队状态，出单结果将自动揭晓，请勿重复提交
              </p>
            )}
          </div>
        </CardContent>
      </Card>

      <ResultDialog open={dialog != null} state={dialog} onClose={closeDialog} />
    </div>
  );
}
