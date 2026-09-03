import { useEffect, useRef, useState } from 'react';

import type { MiaoshaStatus } from '@/types/api';

/**
 * 倒计时域 hook 集。
 *
 * - useNow：全局统一时钟（单个 setInterval 驱动），供列表页多卡片共享，避免每卡一个定时器；
 * - computeMiaoshaStatus：纯函数，由 startDate/endDate 判定秒杀状态；
 * - formatCountdown：剩余秒数 → 「HH:MM:SS」/「X天 HH:MM:SS」；
 * - useCountdown：详情页专用，基于后端 miaoshaStatus / remainSeconds 驱动，
 *   未开始倒计时归零自动进入「进行中」，进行中按 endTime 倒计时归零自动「已结束」。
 */

/** 全局统一时钟：每 intervalMs 更新一次 Date.now()。 */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}

/** 纯函数：由时间窗判定秒杀状态（解析失败视为已结束，避免误开放抢购）。 */
export function computeMiaoshaStatus(now: number, startDate: string, endDate: string): MiaoshaStatus {
  const start = new Date(startDate).getTime();
  const end = new Date(endDate).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 2;
  if (now < start) return 0;
  if (now <= end) return 1;
  return 2;
}

/** 剩余秒数格式化为高对比倒计时文案。 */
export function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const days = Math.floor(s / 86400);
  const hh = String(Math.floor((s % 86400) / 3600)).padStart(2, '0');
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  return days > 0 ? `${days}天 ${hh}:${mm}:${ss}` : `${hh}:${mm}:${ss}`;
}

/** 解析 ISO 时间为时间戳，非法返回 null。 */
function parseTime(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? null : ms;
}

export interface CountdownState {
  status: MiaoshaStatus;
  /** 当前阶段剩余秒数（已结束为 0） */
  remainSeconds: number;
  /** 倒计时文案（如 01:23:45；无倒计时时为空串） */
  label: string;
}

interface UseCountdownOptions {
  /** 进行中阶段的结束时间（ISO），用于「距结束」倒计时 */
  endTime?: string | null;
  /** 状态本地流转（未开始→进行中 / 进行中→已结束）时回调，页面可借此刷新详情 */
  onStatusChange?: (status: MiaoshaStatus) => void;
}

interface InternalState extends CountdownState {
  /** 当前阶段截止时间戳（ms），null 表示无倒计时 */
  deadline: number | null;
}

function buildInitial(status: MiaoshaStatus, remainSeconds: number, endTime?: string | null): InternalState {
  const now = Date.now();
  if (status === 0) {
    const remain = Math.max(0, remainSeconds);
    return { status, remainSeconds: remain, label: formatCountdown(remain), deadline: now + remain * 1000 };
  }
  if (status === 1) {
    const end = parseTime(endTime);
    const remain = end == null ? 0 : Math.max(0, Math.ceil((end - now) / 1000));
    return { status, remainSeconds: remain, label: formatCountdown(remain), deadline: end };
  }
  return { status: 2, remainSeconds: 0, label: '', deadline: null };
}

/**
 * 详情页倒计时：由后端 miaoshaStatus / remainSeconds 初始化，
 * 单个 setInterval 每秒推进，归零时自动做状态流转（0→1→2）。
 */
export function useCountdown(
  status: MiaoshaStatus,
  remainSeconds: number,
  options: UseCountdownOptions = {},
): CountdownState {
  const endTime = options.endTime;
  const [state, setState] = useState<InternalState>(() => buildInitial(status, remainSeconds, endTime));
  const optionsRef = useRef(options);

  // 服务端数据变化时重新初始化
  useEffect(() => {
    setState(buildInitial(status, remainSeconds, endTime));
  }, [status, remainSeconds, endTime]);

  useEffect(() => {
    optionsRef.current = options;
  });

  useEffect(() => {
    const timer = window.setInterval(() => {
      setState((prev) => {
        if (prev.deadline == null) return prev;
        const remain = Math.max(0, Math.ceil((prev.deadline - Date.now()) / 1000));
        if (remain > 0) {
          return prev.remainSeconds === remain ? prev : { ...prev, remainSeconds: remain, label: formatCountdown(remain) };
        }
        // 当前阶段归零：未开始 → 进行中；进行中 → 已结束
        if (prev.status === 0) {
          return buildInitial(1, 0, optionsRef.current.endTime);
        }
        if (prev.status === 1) {
          return { status: 2, remainSeconds: 0, label: '', deadline: null };
        }
        return prev;
      });
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  // 状态流转时通知外部（借助 ref 触发，避免渲染期副作用）
  const prevStatusRef = useRef<MiaoshaStatus>(status);
  useEffect(() => {
    if (state.status !== prevStatusRef.current) {
      prevStatusRef.current = state.status;
      optionsRef.current.onStatusChange?.(state.status);
    }
  }, [state.status]);

  return { status: state.status, remainSeconds: state.remainSeconds, label: state.label };
}

/** 状态 → 文案/配色语义映射，列表卡片与详情页共用。 */
export function statusMeta(status: MiaoshaStatus): { text: string; tone: 'upcoming' | 'active' | 'ended' } {
  switch (status) {
    case 0:
      return { text: '未开始', tone: 'upcoming' };
    case 1:
      return { text: '抢购中', tone: 'active' };
    default:
      return { text: '已结束', tone: 'ended' };
  }
}
