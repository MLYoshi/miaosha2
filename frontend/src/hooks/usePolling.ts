import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 轮询 hook：setTimeout 链实现（避免 setInterval 的请求堆叠），带超时上限与卸载清理。
 *
 * - 每 intervalMs（默认 1.5s）调用一次 query，isFinal(result) 为 true 时终止并回调 onFinal；
 * - 超过 timeoutMs（默认 30s）仍未终态 → 停止并回调 onTimeout；
 * - 单次 query 异常不终止轮询（网络抖动容错），直到超时；
 * - generation 计数丢弃过期轮次的响应，杜绝竞态；卸载时自动 stop，避免内存泄漏。
 */
interface UsePollingOptions<T> {
  /** 单次查询（如 getMiaoshaResult） */
  query: () => Promise<T>;
  /** 终态判定（如 SUCCESS / FAILED） */
  isFinal: (result: T) => boolean;
  /** 轮询间隔，默认 1500ms */
  intervalMs?: number;
  /** 超时上限，默认 30000ms */
  timeoutMs?: number;
  /** 到达终态回调 */
  onFinal?: (result: T) => void;
  /** 超时回调 */
  onTimeout?: () => void;
}

export function usePolling<T>(options: UsePollingOptions<T>) {
  const [polling, setPolling] = useState(false);

  // 经 ref 读取最新配置，start/stop 保持引用稳定
  const optionsRef = useRef(options);
  useEffect(() => {
    optionsRef.current = options;
  });

  const timerRef = useRef<number | null>(null);
  const generationRef = useRef(0);

  const stop = useCallback(() => {
    generationRef.current += 1;
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    setPolling(false);
  }, []);

  const start = useCallback(() => {
    // 先终止既有轮次，保证同一时刻只有一条轮询链
    generationRef.current += 1;
    const generation = generationRef.current;
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    const { intervalMs = 1500, timeoutMs = 30_000 } = optionsRef.current;
    const deadline = Date.now() + timeoutMs;
    setPolling(true);

    const run = async (): Promise<void> => {
      const current = optionsRef.current;
      if (generationRef.current !== generation) return;
      if (Date.now() >= deadline) {
        setPolling(false);
        current.onTimeout?.();
        return;
      }
      let result: T;
      try {
        result = await current.query();
      } catch {
        // 单次失败（网络抖动等）不终止，静默进入下一轮
        if (generationRef.current !== generation) return;
        timerRef.current = window.setTimeout(run, intervalMs);
        return;
      }
      if (generationRef.current !== generation) return;
      if (current.isFinal(result)) {
        setPolling(false);
        current.onFinal?.(result);
        return;
      }
      timerRef.current = window.setTimeout(run, intervalMs);
    };

    void run();
  }, []);

  // 卸载清理
  useEffect(() => stop, [stop]);

  return { polling, start, stop };
}
