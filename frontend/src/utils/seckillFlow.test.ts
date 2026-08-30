import { describe, expect, it } from 'vitest'
import type { MiaoshaAcceptVo, MiaoshaResultVo } from '../api/types'
import {
  MIAOSHA_OVER_CODE,
  MIAOSHA_REPEAT_CODE,
  POLL_INTERVAL_MS,
  POLL_TIMEOUT_MESSAGE,
  POLL_TIMEOUT_MS,
  SECKILL_FLOW_PHASE,
  STOCK_EMPTY_CODE,
  isPollTimeout,
  isTerminalPollStatus,
  resolveAcceptFlow,
  shouldDisableAfterSubmitError,
} from './seckillFlow'

/**
 * 秒杀流转纯函数契约测试（对应 issue 06 验收项）：
 * - 受理 SUCCESS 直接终态；PROCESSING 进入轮询
 * - 轮询终态判定与 30s 截止判定
 * - 库存不足/已结束 → 按钮禁用；重复秒杀不禁用
 */

describe('resolveAcceptFlow', () => {
  it('受理 SUCCESS（降级同步落库）→ 直接 SUCCESS 终态并携带订单号', () => {
    const accept: MiaoshaAcceptVo = { status: 'SUCCESS', orderId: 10086 }
    expect(resolveAcceptFlow(accept)).toEqual({
      phase: SECKILL_FLOW_PHASE.SUCCESS,
      orderId: 10086,
    })
  })

  it('受理 PROCESSING → 进入 PROCESSING 轮询态，无订单号', () => {
    const accept: MiaoshaAcceptVo = { status: 'PROCESSING', orderId: null }
    expect(resolveAcceptFlow(accept)).toEqual({
      phase: SECKILL_FLOW_PHASE.PROCESSING,
      orderId: null,
    })
  })
})

describe('isTerminalPollStatus', () => {
  it('SUCCESS / FAILED / NONE 是终态', () => {
    expect(isTerminalPollStatus('SUCCESS')).toBe(true)
    expect(isTerminalPollStatus('FAILED')).toBe(true)
    expect(isTerminalPollStatus('NONE')).toBe(true)
  })

  it('PROCESSING 不是终态，继续轮询', () => {
    expect(isTerminalPollStatus('PROCESSING')).toBe(false)
  })
})

describe('isPollTimeout', () => {
  it('未满 30 秒不算超时', () => {
    expect(isPollTimeout(1_000, 1_000 + POLL_TIMEOUT_MS - 1)).toBe(false)
  })

  it('满 30 秒（含恰好）判定超时', () => {
    expect(isPollTimeout(1_000, 1_000 + POLL_TIMEOUT_MS)).toBe(true)
    expect(isPollTimeout(1_000, 1_000 + POLL_TIMEOUT_MS + 5_000)).toBe(true)
  })
})

describe('shouldDisableAfterSubmitError', () => {
  it('库存不足（500214）→ 按钮禁用', () => {
    expect(shouldDisableAfterSubmitError(STOCK_EMPTY_CODE)).toBe(true)
  })

  it('秒杀已结束（500216）→ 按钮禁用', () => {
    expect(shouldDisableAfterSubmitError(MIAOSHA_OVER_CODE)).toBe(true)
  })

  it('重复秒杀（500212）等其余错误 → 不禁用，允许重试', () => {
    expect(shouldDisableAfterSubmitError(MIAOSHA_REPEAT_CODE)).toBe(false)
    expect(shouldDisableAfterSubmitError(-1)).toBe(false)
  })
})

describe('常量', () => {
  it('轮询间隔 1 秒、超时 30 秒', () => {
    expect(POLL_INTERVAL_MS).toBe(1_000)
    expect(POLL_TIMEOUT_MS).toBe(30_000)
  })

  it('超时兜底文案', () => {
    expect(POLL_TIMEOUT_MESSAGE).toBe('排队中，请稍后刷新查看')
  })

  it('终态结果状态枚举覆盖四态（类型完整性）', () => {
    const statuses: MiaoshaResultVo['status'][] = ['PROCESSING', 'SUCCESS', 'FAILED', 'NONE']
    expect(statuses.filter(isTerminalPollStatus)).toEqual(['SUCCESS', 'FAILED', 'NONE'])
  })
})
