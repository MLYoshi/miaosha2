import { useEffect, useRef, useState } from 'react';
import { XCircle } from 'lucide-react';

import { registerToastHandler } from '@/lib/errors';
import { cn } from '@/lib/utils';

/**
 * 轻量全局 toast：业务错误统一提示出口。
 *
 * 挂载时向 lib/errors 注册处理器；同时监听兜底事件
 * miaosha:toast（处理器未注册时 errors.ts 会派发该事件）。
 */
interface ToastItem {
  id: number;
  message: string;
}

const TOAST_DURATION_MS = 3200;

export function Toaster() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const idRef = useRef(0);

  useEffect(() => {
    const remove = (id: number) => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    };
    const push = (message: string) => {
      const id = ++idRef.current;
      setToasts((prev) => [...prev.slice(-3), { id, message }]);
      window.setTimeout(() => remove(id), TOAST_DURATION_MS);
    };

    const unregister = registerToastHandler(push);
    const onFallback = (e: Event) => {
      const detail = (e as CustomEvent<{ message?: string }>).detail;
      if (detail?.message) push(detail.message);
    };
    window.addEventListener('miaosha:toast', onFallback);
    return () => {
      unregister();
      window.removeEventListener('miaosha:toast', onFallback);
    };
  }, []);

  if (toasts.length === 0) return null;

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[100] flex w-full max-w-xs flex-col gap-2">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          role="alert"
          className={cn(
            'pointer-events-auto flex items-start gap-2 rounded-lg border border-destructive/30',
            'bg-destructive/95 px-3 py-2.5 text-sm text-destructive-foreground shadow-lg',
            'animate-in slide-in-from-bottom-2 fade-in',
          )}
        >
          <XCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p className="flex-1 leading-5">{toast.message}</p>
        </div>
      ))}
    </div>
  );
}
