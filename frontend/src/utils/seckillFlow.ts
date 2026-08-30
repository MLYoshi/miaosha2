/**
 * 秒杀下单流转纯函数（issue 06）：
 *
 * - 受理（POST /miaosha/do_miaosha）返回两路：SUCCESS 直接终态；PROCESSING 进入结果轮询
 * - 轮询（GET /miaosha/result）以 SUCCESS / FAILED / NONE 为终态，PROCESSING 继续
 * - 轮询最多 30 秒，超时停止并兜底提示
 * - 业务失败中库存不足/已结束会让按钮回到禁用终局，其余（如重复秒杀）仅提示
 */
import type { MiaoshaAcceptVo, MiaoshaResultStatus } from '../api/types'

/** 秒杀流转状态机：IDLE → SUBMITTING → PROCESSING → SUCCESS|FAILED|NONE|TIMEOUT（或直接 SUCCESS） */
export const SECKILL_FLOW_PHASE = {
  /** 空闲：可发起抢购 */
  IDLE: 'IDLE',
  /** 受理请求已发出（防连点） */
  SUBMITTING: 'SUBMITTING',
  /** 受理成功排队中：正在轮询结果 */
  PROCESSING: 'PROCESSING',
  /** 抢购成功（终态，携带订单号） */
  SUCCESS: 'SUCCESS',
  /** 抢购失败（终态） */
  FAILED: 'FAILED',
  /** 未参与本次抢购（终态） */
  NONE: 'NONE',
  /** 轮询超时停止（终态，结果未知，兜底提示） */
  TIMEOUT: 'TIMEOUT',
} as const

export type SeckillFlowPhase = (typeof SECKILL_FLOW_PHASE)[keyof typeof SECKILL_FLOW_PHASE]

export interface SeckillFlowState {
  phase: SeckillFlowPhase
  orderId: number | null
}

/** 轮询间隔：每 1 秒 */
export const POLL_INTERVAL_MS = 1_000

/** 轮询最长持续时间：30 秒 */
export const POLL_TIMEOUT_MS = 30_000

/** 轮询超时兜底提示 */
export const POLL_TIMEOUT_MESSAGE = '排队中，请稍后刷新查看'

/** 后端业务错误码：MIAOSHA_STOCK_EMPTY（含未预热） */
export const STOCK_EMPTY_CODE = 500214

/** 后端业务错误码：MIAOSHA_OVER */
export const MIAOSHA_OVER_CODE = 500216

/** 后端业务错误码：MIAOSHA_REPEAT */
export const MIAOSHA_REPEAT_CODE = 500212

/** 受理结果 → 流转状态：SUCCESS 直接终态；PROCESSING 进入轮询 */
export function resolveAcceptFlow(accept: MiaoshaAcceptVo): SeckillFlowState {
  if (accept.status === 'SUCCESS') {
    return { phase: SECKILL_FLOW_PHASE.SUCCESS, orderId: accept.orderId }
  }
  return { phase: SECKILL_FLOW_PHASE.PROCESSING, orderId: null }
}

/** 轮询结果是否为终态（SUCCESS/FAILED/NONE）；PROCESSING 继续轮询 */
export function isTerminalPollStatus(status: MiaoshaResultStatus): boolean {
  return status !== 'PROCESSING'
}

/** 轮询是否到达 30 秒截止时间 */
export function isPollTimeout(pollStartedAt: number, now: number): boolean {
  return now - pollStartedAt >= POLL_TIMEOUT_MS
}

/** 提交失败后按钮是否应回到禁用终局：库存不足 / 秒杀已结束 */
export function shouldDisableAfterSubmitError(code: number): boolean {
  return code === STOCK_EMPTY_CODE || code === MIAOSHA_OVER_CODE
}
