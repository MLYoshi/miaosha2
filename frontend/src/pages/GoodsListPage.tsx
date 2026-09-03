import { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';

import { listGoods } from '@/api/goods';
import { GoodsCard, GoodsCardSkeleton } from '@/components/goods/GoodsCard';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useNow } from '@/hooks/useCountdown';
import type { GoodsVo } from '@/types/api';

/**
 * 秒杀会场列表：GET /goods/list 一次性加载，卡片栅格流。
 * 全页共用单个 setInterval 时钟（useNow）驱动所有卡片的倒计时。
 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; goodsList: GoodsVo[] }
  | { status: 'error' };

export default function GoodsListPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const now = useNow(1000);

  const load = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const goodsList = await listGoods();
      setState({ status: 'ready', goodsList });
    } catch {
      // 业务错误（含会话失效跳登录）已由请求拦截器统一 toast
      setState({ status: 'error' });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="space-y-6">
      {/* 活动横幅 */}
      <div className="rounded-xl bg-gradient-to-r from-orange-500 to-red-500 px-6 py-8 text-white shadow-lg">
        <h1 className="text-2xl font-bold">限时秒杀会场</h1>
        <p className="mt-1 text-sm opacity-90">好货低价，先到先得</p>
      </div>

      {state.status === 'loading' && (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }, (_, i) => (
            <GoodsCardSkeleton key={i} />
          ))}
        </div>
      )}

      {state.status === 'error' && (
        <Card className="border-white/60 shadow-lg shadow-orange-100/50">
          <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
            <p className="text-sm text-muted-foreground">商品加载失败，请稍后重试</p>
            <Button size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重新加载
            </Button>
          </CardContent>
        </Card>
      )}

      {state.status === 'ready' &&
        (state.goodsList.length === 0 ? (
          <Card className="border-white/60 shadow-lg shadow-orange-100/50">
            <CardContent className="py-10 text-center text-sm text-muted-foreground">
              会场暂无秒杀商品，去看看别的吧～
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {state.goodsList.map((goods) => (
              <GoodsCard key={goods.id} goods={goods} now={now} />
            ))}
          </div>
        ))}
    </div>
  );
}
