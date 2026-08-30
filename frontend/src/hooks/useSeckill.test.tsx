// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { message } from 'antd'
import { ApiError } from '../api/errorCode'
import { doMiaosha, getMiaoshaResult } from '../api/miaosha'
import type { MiaoshaAcceptVo, MiaoshaResultVo } from '../api/types'
import { POLL_TIMEOUT_MESSAGE, SECKILL_FLOW_PHASE } from '../utils/seckillFlow'
import { useSeckill, type UseSeckillResult } from './useSeckill'

/**
 * 秒杀受理 + 结果轮询 Hook 契约测试（对应 issue 06 验收项）：
 * - PROCESSING → 每 1 秒轮询直到终态（SUCCESS 携带订单号）
 * - 降级路径直接 SUCCESS，不进轮询
 * - 业务错误（重复秒杀/库存不足）toast 提示且不进入轮询；库存不足回到禁用态
 * - 轮询 30 秒自动停止并给兜底提示
 * - 受理发出到终态期间 submit 防连点
 */

vi.mock('../api/miaosha', () => ({
  doMiaosha: vi.fn(),
  getMiaoshaResult: vi.fn(),
}))

vi.mock('antd', () => ({
  message: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}))

// React 18.3 的 act 需要
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const processingResult: MiaoshaResultVo = { status: 'PROCESSING', orderId: null }

function acceptOf(status: 'PROCESSING' | 'SUCCESS', orderId: number | null = null): MiaoshaAcceptVo {
  return { status, orderId }
}

describe('useSeckill', () => {
  let container: HTMLDivElement
  let root: Root | undefined
  let queryClient: QueryClient

  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(doMiaosha).mockReset()
    vi.mocked(getMiaoshaResult).mockReset()
    vi.mocked(message.success).mockClear()
    vi.mocked(message.error).mockClear()
    vi.mocked(message.info).mockClear()
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    if (root) {
      act(() => root!.unmount())
      root = undefined
    }
    queryClient.clear()
    container.remove()
    vi.useRealTimers()
  })

  /** 渲染捕获 hook 返回值的探针组件（挂真实 QueryClientProvider 以驱动轮询） */
  function renderProbe(goodsId: number) {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    let captured!: UseSeckillResult
    function Probe() {
      captured = useSeckill(goodsId)
      return null
    }
    act(() => {
      root = createRoot(container)
      root.render(
        <QueryClientProvider client={queryClient}>
          <Probe />
        </QueryClientProvider>,
      )
    })
    return {
      get flow(): UseSeckillResult {
        return captured
      },
    }
  }

  async function advance(ms: number): Promise<void> {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  }

  /**
   * 冲刷订阅通知：query-core 的 notifyManager 用 setTimeout(cb, 0) 宏任务派发
   * 缓存更新，fake timers 下需再推进一小段时间通知才会送达并触发重渲染。
   */
  async function flushNotifications(): Promise<void> {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
      await Promise.resolve()
    })
  }

  it('受理 PROCESSING → 每 1 秒轮询，直到 SUCCESS 携带订单号', async () => {
    vi.mocked(doMiaosha).mockResolvedValue(acceptOf('PROCESSING'))
    const results: MiaoshaResultVo[] = [
      processingResult,
      processingResult,
      { status: 'SUCCESS', orderId: 10086 },
    ]
    vi.mocked(getMiaoshaResult).mockImplementation(
      () => Promise.resolve(results.shift() ?? processingResult),
    )

    const probe = renderProbe(1)
    await act(async () => {
      await probe.flow.submit()
    })
    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.PROCESSING)
    expect(probe.flow.isBusy).toBe(true)

    // 启用后立即发出首次查询
    await advance(0)
    await flushNotifications()
    expect(vi.mocked(getMiaoshaResult).mock.calls.length).toBeGreaterThanOrEqual(1)
    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.PROCESSING)

    // 1 秒后再次查询，仍为 PROCESSING
    await advance(1_000)
    await flushNotifications()
    expect(vi.mocked(getMiaoshaResult).mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.PROCESSING)

    // 再 1 秒拿到 SUCCESS 终态，轮询停止
    await advance(1_000)
    await flushNotifications()
    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.SUCCESS)
    expect(probe.flow.orderId).toBe(10086)
    expect(probe.flow.isBusy).toBe(false)
    const callsAtTerminal = vi.mocked(getMiaoshaResult).mock.calls.length
    await advance(3_000)
    await flushNotifications()
    expect(vi.mocked(getMiaoshaResult).mock.calls.length).toBe(callsAtTerminal)
  })

  it('受理 SUCCESS（降级路径）不进轮询，立即拿到订单号', async () => {
    vi.mocked(doMiaosha).mockResolvedValue(acceptOf('SUCCESS', 20001))

    const probe = renderProbe(2)
    await act(async () => {
      await probe.flow.submit()
    })

    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.SUCCESS)
    expect(probe.flow.orderId).toBe(20001)
    await advance(3_000)
    expect(getMiaoshaResult).not.toHaveBeenCalled()
  })

  it('受理 PROCESSING 期间重复 submit 不再发受理请求（防连点）', async () => {
    vi.mocked(doMiaosha).mockResolvedValue(acceptOf('PROCESSING'))
    vi.mocked(getMiaoshaResult).mockResolvedValue(processingResult)

    const probe = renderProbe(3)
    await act(async () => {
      await probe.flow.submit()
    })
    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.PROCESSING)

    await act(async () => {
      await probe.flow.submit()
    })
    expect(doMiaosha).toHaveBeenCalledTimes(1)
  })

  it('重复秒杀（500212）→ toast 提示且不进入轮询，按钮可重试', async () => {
    vi.mocked(doMiaosha).mockRejectedValue(new ApiError('不能重复秒杀', 500212, 200))

    const probe = renderProbe(4)
    await act(async () => {
      await probe.flow.submit()
    })

    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.IDLE)
    expect(probe.flow.isBusy).toBe(false)
    expect(probe.flow.finished).toBe(false)
    expect(message.error).toHaveBeenCalledWith('不能重复秒杀')
    await advance(3_000)
    expect(getMiaoshaResult).not.toHaveBeenCalled()
  })

  it('库存不足（500214）→ toast 提示并回到禁用终局', async () => {
    vi.mocked(doMiaosha).mockRejectedValue(new ApiError('库存不足', 500214, 200))

    const probe = renderProbe(5)
    await act(async () => {
      await probe.flow.submit()
    })

    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.IDLE)
    expect(probe.flow.finished).toBe(true)
    expect(message.error).toHaveBeenCalledWith('库存不足')
    expect(getMiaoshaResult).not.toHaveBeenCalled()
  })

  it('轮询结果 FAILED → toast 提示并停止轮询', async () => {
    vi.mocked(doMiaosha).mockResolvedValue(acceptOf('PROCESSING'))
    vi.mocked(getMiaoshaResult).mockResolvedValue({ status: 'FAILED', orderId: null })

    const probe = renderProbe(6)
    await act(async () => {
      await probe.flow.submit()
    })
    await advance(0)
    await flushNotifications()

    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.FAILED)
    expect(probe.flow.isBusy).toBe(false)
    expect(message.error).toHaveBeenCalledTimes(1)
    const callsAtTerminal = vi.mocked(getMiaoshaResult).mock.calls.length
    await advance(3_000)
    await flushNotifications()
    expect(vi.mocked(getMiaoshaResult).mock.calls.length).toBe(callsAtTerminal)
  })

  it('轮询结果 NONE → toast 提示未参与并停止轮询', async () => {
    vi.mocked(doMiaosha).mockResolvedValue(acceptOf('PROCESSING'))
    vi.mocked(getMiaoshaResult).mockResolvedValue({ status: 'NONE', orderId: null })

    const probe = renderProbe(7)
    await act(async () => {
      await probe.flow.submit()
    })
    await advance(0)
    await flushNotifications()

    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.NONE)
    expect(message.error).toHaveBeenCalledTimes(1)
  })

  it('轮询 30 秒未出结果 → 自动停止并给兜底提示', async () => {
    vi.mocked(doMiaosha).mockResolvedValue(acceptOf('PROCESSING'))
    vi.mocked(getMiaoshaResult).mockResolvedValue(processingResult)

    const probe = renderProbe(8)
    await act(async () => {
      await probe.flow.submit()
    })

    await advance(30_000)
    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.TIMEOUT)
    expect(message.info).toHaveBeenCalledWith(POLL_TIMEOUT_MESSAGE)
    expect(probe.flow.isBusy).toBe(false)

    // 停止后不再发轮询
    const callsAtTimeout = vi.mocked(getMiaoshaResult).mock.calls.length
    await advance(5_000)
    expect(vi.mocked(getMiaoshaResult).mock.calls.length).toBe(callsAtTimeout)
  })

  it('网络异常等非业务错误 → toast 兜底提示并回到 IDLE', async () => {
    vi.mocked(doMiaosha).mockRejectedValue(new Error('network down'))

    const probe = renderProbe(9)
    await act(async () => {
      await probe.flow.submit()
    })

    expect(probe.flow.phase).toBe(SECKILL_FLOW_PHASE.IDLE)
    expect(probe.flow.finished).toBe(false)
    expect(message.error).toHaveBeenCalledWith('网络异常，请稍后重试')
  })
})