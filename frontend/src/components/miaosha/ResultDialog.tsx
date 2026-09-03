import { CheckCircle2, Loader2, XCircle } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/** 结果弹层：秒杀提交后的「排队中 / 成功 / 失败」三态展示。 */

export type ResultDialogState =
  | { kind: 'queueing' }
  | { kind: 'success'; orderId: number }
  | { kind: 'failed'; reason?: string };

interface ResultDialogProps {
  open: boolean;
  state: ResultDialogState | null;
  /** 关闭回调（排队中关闭即放弃本轮等待，内部会停止轮询） */
  onClose: () => void;
}

export function ResultDialog({ open, state, onClose }: ResultDialogProps) {
  if (!open || state == null) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={(e) => {
        // 点击遮罩关闭（排队中也允许放弃等待）
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="w-full max-w-sm animate-in zoom-in-95 fade-in duration-200">
        <div className="overflow-hidden rounded-2xl bg-white shadow-2xl">
          <div className="h-1.5 bg-gradient-to-r from-orange-500 to-red-500" />
          <div className="flex flex-col items-center gap-4 px-6 py-8 text-center">
            {state.kind === 'queueing' && (
              <>
                <Loader2 className="h-14 w-14 animate-spin text-primary" />
                <h2 className="text-lg font-bold">排队中…</h2>
                <p className="text-sm leading-6 text-muted-foreground">
                  秒杀请求已受理，正在高速排队中
                  <br />
                  请稍候，结果马上揭晓
                </p>
                <Button variant="ghost" size="sm" className="mt-2 text-muted-foreground" onClick={onClose}>
                  不等了，关闭
                </Button>
              </>
            )}

            {state.kind === 'success' && (
              <>
                <CheckCircle2 className="h-14 w-14 text-green-500" />
                <h2 className="text-lg font-bold">秒杀成功！</h2>
                <p className="text-sm text-muted-foreground">手速惊人，订单已生成</p>
                <div className="w-full rounded-lg bg-muted/60 px-4 py-3">
                  <span className="text-xs text-muted-foreground">订单号</span>
                  <p className="mt-0.5 font-mono text-base font-bold tabular-nums">{state.orderId}</p>
                </div>
                <Button
                  className="mt-2 w-full bg-gradient-to-r from-orange-500 to-red-500 text-white transition-transform hover:-translate-y-0.5"
                  onClick={onClose}
                >
                  太好了
                </Button>
              </>
            )}

            {state.kind === 'failed' && (
              <>
                <XCircle className="h-14 w-14 text-destructive" />
                <h2 className="text-lg font-bold">很遗憾，未抢到</h2>
                <p className={cn('text-sm leading-6 text-muted-foreground')}>
                  {state.reason ?? '本场商品已被抢空，下次活动再接再厉'}
                </p>
                <Button
                  variant="outline"
                  className="mt-2 w-full"
                  onClick={onClose}
                >
                  知道了
                </Button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
