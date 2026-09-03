import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ImageOff, Zap } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { computeMiaoshaStatus, formatCountdown, statusMeta } from '@/hooks/useCountdown';
import { cn } from '@/lib/utils';
import type { GoodsVo, MiaoshaStatus } from '@/types/api';

/** 商品卡片：主图、价格对比、库存余量、状态标签与倒计时角标。 */

const toneBadgeClass: Record<'upcoming' | 'active' | 'ended', string> = {
  upcoming: 'bg-amber-100 text-amber-700',
  active: 'bg-gradient-to-r from-orange-500 to-red-500 text-white',
  ended: 'bg-muted text-muted-foreground',
};

export function GoodsCard({ goods, now }: { goods: GoodsVo; now: number }) {
  const [imgError, setImgError] = useState(false);
  const status: MiaoshaStatus = computeMiaoshaStatus(now, goods.startDate, goods.endDate);
  const meta = statusMeta(status);

  const start = new Date(goods.startDate).getTime();
  const end = new Date(goods.endDate).getTime();
  const remainSeconds =
    status === 0
      ? Math.max(0, Math.ceil((start - now) / 1000))
      : status === 1
        ? Math.max(0, Math.ceil((end - now) / 1000))
        : 0;
  const countdownPrefix = status === 0 ? '距开始' : '距结束';

  const soldOut = goods.stockCount <= 0;
  const discount =
    goods.goodsPrice > 0 ? Math.max(1, Math.round((goods.miaoshaPrice / goods.goodsPrice) * 10)) : 10;

  return (
    <Link to={`/goods/${goods.id}`} className="group block" aria-label={goods.goodsName}>
      <Card
        className={cn(
          'h-full overflow-hidden border-white/60 transition-all duration-200',
          'shadow-md shadow-orange-100/40 hover:-translate-y-1 hover:shadow-xl hover:shadow-orange-200/60',
          status === 2 && 'opacity-75',
        )}
      >
        <div className="relative aspect-[4/3] overflow-hidden bg-gradient-to-br from-orange-50 to-red-50">
          {!imgError && goods.goodsImg ? (
            <img
              src={goods.goodsImg}
              alt={goods.goodsName}
              loading="lazy"
              onError={() => setImgError(true)}
              className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
            />
          ) : (
            <div className="flex h-full w-full flex-col items-center justify-center gap-2 text-orange-300">
              <ImageOff className="h-10 w-10" />
              <span className="text-xs">图片暂不可用</span>
            </div>
          )}
          <Badge className={cn('absolute left-3 top-3 border-none shadow-sm', toneBadgeClass[meta.tone])}>
            {status === 1 && <Zap className="mr-0.5 h-3 w-3" />}
            {meta.text}
          </Badge>
          {discount < 10 && (
            <Badge className="absolute right-3 top-3 border-none bg-black/60 text-white shadow-sm">
              {discount} 折
            </Badge>
          )}
          {status !== 2 && (
            <div className="absolute inset-x-0 bottom-0 bg-black/55 px-3 py-1.5 text-center backdrop-blur-sm">
              <span className="text-xs text-white/80">{countdownPrefix}</span>
              <span className="ml-2 font-mono text-sm font-bold tabular-nums tracking-wider text-amber-300">
                {formatCountdown(remainSeconds)}
              </span>
            </div>
          )}
        </div>

        <CardContent className="space-y-2.5 p-4">
          <h3 className="line-clamp-1 font-semibold">{goods.goodsName}</h3>
          <p className="line-clamp-1 text-xs text-muted-foreground">{goods.goodsTitle}</p>

          <div className="flex items-baseline gap-2">
            <span className="text-lg font-bold text-primary">
              <span className="text-xs">¥</span>
              {goods.miaoshaPrice}
            </span>
            <span className="text-xs text-muted-foreground line-through">¥{goods.goodsPrice}</span>
          </div>

          <div className="space-y-1">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span>{soldOut ? '已被抢光' : `仅剩 ${goods.stockCount} 件`}</span>
              <span>已抢 {Math.max(0, Math.min(100, Math.round(((goods.goodsStock - goods.stockCount) / Math.max(1, goods.goodsStock)) * 100)))}%</span>
            </div>
            <div className="h-1.5 overflow-hidden rounded-full bg-muted">
              <div
                className="h-full rounded-full bg-gradient-to-r from-orange-500 to-red-500 transition-all"
                style={{
                  width: `${Math.max(2, Math.min(100, Math.round((goods.stockCount / Math.max(1, goods.goodsStock)) * 100)))}%`,
                }}
              />
            </div>
          </div>

          <Button
            size="sm"
            className="w-full bg-gradient-to-r from-orange-500 to-red-500 text-white shadow transition-transform hover:-translate-y-0.5"
            disabled={status !== 1 || soldOut}
          >
            {status === 0 ? '未开始' : status === 2 ? '已结束' : soldOut ? '已抢光' : '立即抢购'}
          </Button>
        </CardContent>
      </Card>
    </Link>
  );
}

/** 加载骨架卡片。 */
export function GoodsCardSkeleton() {
  return (
    <Card className="h-full overflow-hidden border-white/60 shadow-md shadow-orange-100/40">
      <div className="aspect-[4/3] animate-pulse bg-muted" />
      <CardContent className="space-y-2.5 p-4">
        <div className="h-5 w-3/4 animate-pulse rounded bg-muted" />
        <div className="h-3 w-1/2 animate-pulse rounded bg-muted" />
        <div className="h-6 w-1/3 animate-pulse rounded bg-muted" />
        <div className="h-9 animate-pulse rounded bg-muted" />
      </CardContent>
    </Card>
  );
}
