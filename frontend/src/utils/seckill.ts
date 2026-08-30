/**
 * 秒杀状态机纯函数：
 *
 * - 后端 miaoshaStatus 是唯一权威来源，本地时钟只用于倒计时展示
 * - NOT_START 状态下本地倒计时归零 → CALIBRATING（等待重拉详情校准），
 *   绝不允许仅凭本地时钟归零就直接切换为可抢购形态
 */
import { MIAOSHA_STATUS, type MiaoshaStatus } from '../api/types'

/** 详情页秒杀按钮的四种视图形态 */
export const SECKILL_PHASE = {
  /** 未开始：本地倒计时进行中，按钮禁用并显示剩余时间 */
  COUNTDOWN: 'COUNTDOWN',
  /** 未开始但本地倒计时已归零：等待后端权威状态校准，按钮保持禁用 */
  CALIBRATING: 'CALIBRATING',
  /** 进行中：可点击的抢购态 */
  READY: 'READY',
  /** 已结束：按钮禁用 */
  OVER: 'OVER',
} as const

export type SeckillPhase = (typeof SECKILL_PHASE)[keyof typeof SECKILL_PHASE]

/**
 * 由「后端状态 + 本地剩余秒数」解析视图形态。
 *
 * @param miaoshaStatus    后端 GoodsDetailVo.miaoshaStatus（权威）
 * @param localRemainSeconds 本地每秒递减的剩余秒数（仅 NOT_START 时有意义）
 */
export function resolveSeckillPhase(miaoshaStatus: MiaoshaStatus, localRemainSeconds: number): SeckillPhase {
  if (miaoshaStatus === MIAOSHA_STATUS.IN_PROGRESS) {
    return SECKILL_PHASE.READY
  }
  if (miaoshaStatus === MIAOSHA_STATUS.OVER) {
    return SECKILL_PHASE.OVER
  }
  // NOT_START：本地归零只意味着该去拉后端权威状态，而不是切换为可抢购
  return localRemainSeconds > 0 ? SECKILL_PHASE.COUNTDOWN : SECKILL_PHASE.CALIBRATING
}

/** 倒计时格式化：不足一天为 HH:MM:SS，一天及以上带「N天 」前缀；负数/小数向下归一 */
export function formatCountdown(totalSeconds: number): string {
  const total = Math.max(0, Math.floor(totalSeconds))
  const days = Math.floor(total / 86_400)
  const hours = Math.floor((total % 86_400) / 3_600)
  const minutes = Math.floor((total % 3_600) / 60)
  const seconds = total % 60
  const hms = [hours, minutes, seconds].map((n) => String(n).padStart(2, '0')).join(':')
  return days > 0 ? `${days}天 ${hms}` : hms
}
