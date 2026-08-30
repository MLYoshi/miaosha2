import { describe, expect, it } from 'vitest'
import { MIAOSHA_STATUS } from '../api/types'
import { SECKILL_PHASE, formatCountdown, resolveSeckillPhase } from './seckill'

/**
 * 秒杀状态机纯函数接缝测试（对应 issue 05 验收项）：
 * - 后端 miaoshaStatus 是唯一权威：IN_PROGRESS → 可抢购，OVER → 已结束
 * - NOT_START 时由本地倒计时驱动；本地归零后进入 CALIBRATING（等待后端校准），
 *   绝不允许仅凭本地时钟归零就直接切换为可抢购形态
 */

describe('resolveSeckillPhase', () => {
  it('后端状态为进行中：无论本地倒计时为何值，均为可抢购', () => {
    expect(resolveSeckillPhase(MIAOSHA_STATUS.IN_PROGRESS, 100)).toBe(SECKILL_PHASE.READY)
    expect(resolveSeckillPhase(MIAOSHA_STATUS.IN_PROGRESS, 0)).toBe(SECKILL_PHASE.READY)
  })

  it('后端状态为已结束：一律为已结束', () => {
    expect(resolveSeckillPhase(MIAOSHA_STATUS.OVER, 0)).toBe(SECKILL_PHASE.OVER)
    expect(resolveSeckillPhase(MIAOSHA_STATUS.OVER, 999)).toBe(SECKILL_PHASE.OVER)
  })

  it('未开始且本地倒计时未归零：处于倒计时阶段', () => {
    expect(resolveSeckillPhase(MIAOSHA_STATUS.NOT_START, 1)).toBe(SECKILL_PHASE.COUNTDOWN)
    expect(resolveSeckillPhase(MIAOSHA_STATUS.NOT_START, 3661)).toBe(SECKILL_PHASE.COUNTDOWN)
  })

  it('未开始且本地倒计时已归零：进入校准态而非可抢购（核心规则）', () => {
    expect(resolveSeckillPhase(MIAOSHA_STATUS.NOT_START, 0)).toBe(SECKILL_PHASE.CALIBRATING)
    expect(resolveSeckillPhase(MIAOSHA_STATUS.NOT_START, -5)).toBe(SECKILL_PHASE.CALIBRATING)
  })
})

describe('formatCountdown', () => {
  it('零与负数归一为 00:00:00', () => {
    expect(formatCountdown(0)).toBe('00:00:00')
    expect(formatCountdown(-1)).toBe('00:00:00')
  })

  it('不足一小时：分秒两位补零', () => {
    expect(formatCountdown(59)).toBe('00:00:59')
    expect(formatCountdown(60)).toBe('00:01:00')
    expect(formatCountdown(3599)).toBe('00:59:59')
  })

  it('一小时以上：HH:MM:SS', () => {
    expect(formatCountdown(3600)).toBe('01:00:00')
    expect(formatCountdown(3661)).toBe('01:01:01')
    expect(formatCountdown(86399)).toBe('23:59:59')
  })

  it('一天及以上：携带天数前缀', () => {
    expect(formatCountdown(86400)).toBe('1天 00:00:00')
    expect(formatCountdown(90061)).toBe('1天 01:01:01')
    expect(formatCountdown(2 * 86400 + 125)).toBe('2天 00:02:05')
  })

  it('小数向下取整', () => {
    expect(formatCountdown(0.9)).toBe('00:00:00')
    expect(formatCountdown(1.5)).toBe('00:00:01')
  })
})
