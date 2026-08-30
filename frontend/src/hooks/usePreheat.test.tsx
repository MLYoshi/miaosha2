// @vitest-environment jsdom
import { message } from 'antd'
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/errorCode'
import { preheatStock } from '../api/admin'
import { usePreheat, type UsePreheatResult } from './usePreheat'

/**
 * 预热提交 Hook 契约测试（对应 issue 07 验收项 2/3）：
 * - 有效 goodsId → 成功 toast，结束后 submitting 复位
 * - 业务失败（如 500104 商品不存在）→ 展示后端错误文案，不成功 toast
 * - 提交期间防连点
 * - 非业务异常 → 兜底文案
 */

vi.mock('../api/admin', () => ({
  preheatStock: vi.fn(),
}))

vi.mock('antd', () => ({
  message: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}))

// React 18.3 的 act 需要
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void } {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('usePreheat', () => {
  let container: HTMLDivElement
  let root: Root | undefined
  let captured!: UsePreheatResult

  function Probe() {
    captured = usePreheat()
    return null
  }

  beforeEach(() => {
    vi.mocked(preheatStock).mockReset()
    vi.mocked(message.success).mockClear()
    vi.mocked(message.error).mockClear()
    container = document.createElement('div')
    document.body.appendChild(container)
    act(() => {
      root = createRoot(container)
      root.render(<Probe />)
    })
  })

  afterEach(() => {
    if (root) {
      act(() => root!.unmount())
      root = undefined
    }
    container.remove()
  })

  it('成功 → toast 预热完成，submitting 复位', async () => {
    vi.mocked(preheatStock).mockResolvedValue('ok')

    await act(async () => {
      await captured.submit(1)
    })

    expect(preheatStock).toHaveBeenCalledWith(1)
    expect(message.success).toHaveBeenCalledWith('预热完成')
    expect(captured.submitting).toBe(false)
    expect(message.error).not.toHaveBeenCalled()
  })

  it('业务失败（商品不存在）→ toast 后端错误文案，不成功 toast', async () => {
    vi.mocked(preheatStock).mockRejectedValue(new ApiError('商品不存在', 500104, 200))

    await act(async () => {
      await captured.submit(404)
    })

    expect(message.error).toHaveBeenCalledWith('商品不存在')
    expect(message.success).not.toHaveBeenCalled()
    expect(captured.submitting).toBe(false)
  })

  it('提交期间重复 submit 防连点', async () => {
    const gate = deferred<string>()
    vi.mocked(preheatStock).mockReturnValue(gate.promise)

    let first!: Promise<void>
    await act(async () => {
      first = captured.submit(1)
    })
    expect(captured.submitting).toBe(true)

    await act(async () => {
      await captured.submit(1)
    })
    expect(preheatStock).toHaveBeenCalledTimes(1)

    await act(async () => {
      gate.resolve('ok')
      await first
    })
    expect(captured.submitting).toBe(false)
    expect(message.success).toHaveBeenCalledWith('预热完成')
  })

  it('网络异常等非业务错误 → 兜底文案', async () => {
    vi.mocked(preheatStock).mockRejectedValue(new Error('network down'))

    await act(async () => {
      await captured.submit(2)
    })

    expect(message.error).toHaveBeenCalledWith('预热失败，请稍后重试')
    expect(captured.submitting).toBe(false)
  })
})
