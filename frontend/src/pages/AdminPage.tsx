import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2,
  DatabaseZap,
  Loader2,
  RefreshCw,
  Settings2,
  ShieldCheck,
} from 'lucide-react';

import { listGoods } from '@/api/goods';
import { preheatStock, resetMiaosha } from '@/api/admin';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { cn, formatDateTime } from '@/lib/utils';
import type { GoodsVo, MiaoshaConfig } from '@/types/api';

/**
 * 管理端：预热 Redis 库存 + 重置秒杀时间窗/库存。
 *
 * - Tab 分组「预热库存」与「重置秒杀」，商品下拉复用秒杀会场列表接口；
 * - 预热：POST /admin/preheat?goodsId=（miaosha-service 写 Redis 预扣库存 Key）；
 * - 重置：POST /admin/goods/{goodsId}/miaosha?durationMinutes=&stockCount=，
 *   goods-service 落库新窗口/库存 + 重写 Redis Key，返回新配置回显。
 */

type Tab = 'preheat' | 'reset';

type SubmitState =
  | { status: 'idle' }
  | { status: 'submitting' }
  | { status: 'success'; payload: unknown }
  | { status: 'error'; message: string };

/** 成功提示复用全局 toast 通道（Toaster 常驻监听兜底事件）。 */
function toastSuccess(message: string): void {
  window.dispatchEvent(
    new CustomEvent('miaosha:toast', { detail: { message, variant: 'success' } }),
  );
}

export default function AdminPage() {
  const [tab, setTab] = useState<Tab>('preheat');
  const [goods, setGoods] = useState<GoodsVo[]>([]);
  const [goodsLoading, setGoodsLoading] = useState(true);
  const [goodsId, setGoodsId] = useState<string>('');
  const [durationMinutes, setDurationMinutes] = useState('60');
  const [resetStock, setResetStock] = useState(false);
  const [stockCount, setStockCount] = useState('100');
  const [preheatState, setPreheatState] = useState<SubmitState>({ status: 'idle' });
  const [resetState, setResetState] = useState<SubmitState>({ status: 'idle' });

  const loadGoods = useCallback(async () => {
    setGoodsLoading(true);
    try {
      const list = await listGoods();
      setGoods(list);
      setGoodsId((prev) => {
        if (prev && list.some((g) => String(g.id) === prev)) return prev;
        return list.length > 0 ? String(list[0].id) : '';
      });
    } catch {
      // 业务错误（含会话失效跳登录）已由请求拦截器统一处理
    } finally {
      setGoodsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadGoods();
  }, [loadGoods]);

  const selected = useMemo(
    () => goods.find((g) => String(g.id) === goodsId) ?? null,
    [goods, goodsId],
  );

  const numericGoodsId = goodsId === '' ? null : Number(goodsId);

  const handlePreheat = async () => {
    if (numericGoodsId == null || Number.isNaN(numericGoodsId)) return;
    setPreheatState({ status: 'submitting' });
    try {
      const data = await preheatStock(numericGoodsId);
      setPreheatState({ status: 'success', payload: data });
      toastSuccess('库存预热成功，Redis 预扣 Key 已就绪');
    } catch (e) {
      setPreheatState({
        status: 'error',
        message: e instanceof Error ? e.message : '预热失败，请稍后重试',
      });
    }
  };

  const handleReset = async () => {
    if (numericGoodsId == null || Number.isNaN(numericGoodsId)) return;
    const minutes = Number(durationMinutes);
    if (!Number.isFinite(minutes) || minutes < 1) {
      setResetState({ status: 'error', message: '时长必须为不小于 1 的分钟数' });
      return;
    }
    let stock: number | undefined;
    if (resetStock) {
      const parsed = Number(stockCount);
      if (!Number.isInteger(parsed) || parsed < 0) {
        setResetState({ status: 'error', message: '库存必须为不小于 0 的整数' });
        return;
      }
      stock = parsed;
    }
    setResetState({ status: 'submitting' });
    try {
      const config = await resetMiaosha(numericGoodsId, minutes, stock);
      setResetState({ status: 'success', payload: config });
      toastSuccess('秒杀配置已重置，时间窗与 Redis 库存已更新');
    } catch (e) {
      setResetState({
        status: 'error',
        message: e instanceof Error ? e.message : '重置失败，请稍后重试',
      });
    }
  };

  const tabs: { key: Tab; label: string; icon: typeof DatabaseZap }[] = [
    { key: 'preheat', label: '预热库存', icon: DatabaseZap },
    { key: 'reset', label: '重置秒杀', icon: Settings2 },
  ];

  return (
    <div className="mx-auto max-w-2xl space-y-6 py-8">
      <div className="flex items-center gap-3">
        <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-orange-500 to-red-500 text-white shadow-md shadow-orange-200">
          <ShieldCheck className="h-6 w-6" />
        </div>
        <div>
          <h1 className="text-xl font-bold">运营管理后台</h1>
          <p className="text-sm text-muted-foreground">
            活动开始前预热库存、调整秒杀时间窗与库存
          </p>
        </div>
      </div>

      {/* 商品选择（两个 Tab 共用） */}
      <Card className="border-white/60 shadow-lg shadow-orange-100/50">
        <CardContent className="space-y-3 pt-6">
          <div className="flex items-center justify-between">
            <label htmlFor="admin-goods" className="text-sm font-medium">
              目标商品
            </label>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => void loadGoods()}
              disabled={goodsLoading}
            >
              <RefreshCw className={cn('h-4 w-4', goodsLoading && 'animate-spin')} />
              刷新
            </Button>
          </div>
          <select
            id="admin-goods"
            value={goodsId}
            onChange={(e) => setGoodsId(e.target.value)}
            disabled={goodsLoading || goods.length === 0}
            className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {goods.length === 0 && <option value="">{goodsLoading ? '加载中…' : '暂无商品'}</option>}
            {goods.map((g) => (
              <option key={g.id} value={g.id}>
                #{g.id} {g.goodsName}（秒杀库存 {g.stockCount}）
              </option>
            ))}
          </select>
          {selected && (
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <Badge className="bg-gradient-to-r from-orange-500 to-red-500 text-white">
                ¥{selected.miaoshaPrice}
              </Badge>
              <span>原价 ¥{selected.goodsPrice}</span>
              <span>·</span>
              <span>秒杀库存 {selected.stockCount}</span>
              <span>·</span>
              <span>
                {formatDateTime(selected.startDate)} ~ {formatDateTime(selected.endDate)}
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Tab 分组 */}
      <div className="flex gap-2 rounded-xl bg-muted p-1">
        {tabs.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={cn(
              'flex flex-1 items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition-all',
              tab === key
                ? 'bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-md shadow-orange-200'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      {tab === 'preheat' ? (
        <Card className="border-white/60 shadow-lg shadow-orange-100/50">
          <CardHeader>
            <CardTitle className="text-base">预热 Redis 库存</CardTitle>
            <CardDescription>
              活动开始前将 DB 库存写入 Redis 预扣 Key（miaosha:stock:*），预扣链路依赖此 Key。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Button
              className="w-full bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-md shadow-orange-200 transition-transform hover:-translate-y-0.5 hover:bg-orange-600"
              onClick={() => void handlePreheat()}
              disabled={preheatState.status === 'submitting' || numericGoodsId == null}
            >
              {preheatState.status === 'submitting' ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <DatabaseZap className="h-4 w-4" />
              )}
              立即预热
            </Button>
            {preheatState.status === 'error' && (
              <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
                {preheatState.message}
              </p>
            )}
            {preheatState.status === 'success' && (
              <ResultCard
                title="预热结果"
                payload={preheatState.payload}
              />
            )}
          </CardContent>
        </Card>
      ) : (
        <Card className="border-white/60 shadow-lg shadow-orange-100/50">
          <CardHeader>
            <CardTitle className="text-base">重置秒杀时间窗 / 库存</CardTitle>
            <CardDescription>
              时间窗对齐为「now → now + N 分钟」，可选重置库存；完成后自动重写 Redis 预扣 Key。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-2">
              <label htmlFor="duration-minutes" className="text-sm font-medium">
                活动时长（分钟）
              </label>
              <Input
                id="duration-minutes"
                type="number"
                min={1}
                value={durationMinutes}
                onChange={(e) => setDurationMinutes(e.target.value)}
                placeholder="60"
              />
            </div>
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-sm font-medium">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-orange-500"
                  checked={resetStock}
                  onChange={(e) => setResetStock(e.target.checked)}
                />
                同时重置秒杀库存
              </label>
              {resetStock && (
                <Input
                  type="number"
                  min={0}
                  value={stockCount}
                  onChange={(e) => setStockCount(e.target.value)}
                  placeholder="新库存数量"
                />
              )}
            </div>
            <Button
              className="w-full bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-md shadow-orange-200 transition-transform hover:-translate-y-0.5 hover:bg-orange-600"
              onClick={() => void handleReset()}
              disabled={resetState.status === 'submitting' || numericGoodsId == null}
            >
              {resetState.status === 'submitting' ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Settings2 className="h-4 w-4" />
              )}
              确认重置
            </Button>
            {resetState.status === 'error' && (
              <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
                {resetState.message}
              </p>
            )}
            {resetState.status === 'success' && (
              <ResultCard title="新秒杀配置" payload={resetState.payload} />
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/** 返回结果 JSON 卡片展示（含时间窗友好格式化）。 */
function ResultCard({ title, payload }: { title: string; payload: unknown }) {
  const config = payload as MiaoshaConfig | null;
  return (
    <div className="space-y-2 rounded-lg border border-green-200 bg-green-50/60 p-3">
      <p className="flex items-center gap-1.5 text-sm font-medium text-green-700">
        <CheckCircle2 className="h-4 w-4" />
        {title}
      </p>
      {config && config.startDate && config.endDate ? (
        <div className="space-y-1 text-sm text-foreground/80">
          <p>
            时间窗：
            {formatDateTime(config.startDate)} ~ {formatDateTime(config.endDate)}
          </p>
          {config.stockCount != null && <p>秒杀库存：{config.stockCount}</p>}
        </div>
      ) : null}
      <pre className="overflow-x-auto rounded bg-muted p-2 text-xs leading-5 text-muted-foreground">
        {JSON.stringify(payload, null, 2)}
      </pre>
    </div>
  );
}
