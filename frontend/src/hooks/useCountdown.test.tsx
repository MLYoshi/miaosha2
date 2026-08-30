// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCountdown } from './useCountdown'

/**
 * 倒计时 Hook 契约测试（对应 issue 05 验收项）：
 * - 由后端 remainSeconds 驱动，每秒本地递减
 * - 归零时触发一次 onExpire（调用方据此重拉详情做权威校准）
 * - initialSeconds 变化（重拉后新值）时重新校准并重新计时
 */

// React 18.3 的 act 需要
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('useCountdown', () => {
  let container: HTMLDivElement
  let root: Root | undefined

  beforeEach(() => {
    vi.useFakeTimers()
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    if (root) {
      act(() => root!.unmount())
      root = undefined
    }
    container.remove()
    vi.useRealTimers()
  })

  /** 渲染捕获 hook 返回值的探针组件，支持以新 initialSeconds 重渲染（模拟重拉校准） */
  function renderProbe(initial: number, onExpire: () => void) {
    let captured = initial
    function Probe({ seconds }: { seconds: number }) {
      captured = useCountdown(seconds, onExpire)
      return null
    }
    act(() => {
      root = createRoot(container)
      root.render(<Probe seconds={initial} />)
    })
    return {
      get remain() {
        return captured
      },
      rerender(seconds: number) {
        act(() => root!.render(<Probe seconds={seconds} />))
      },
    }
  }

  it('初始值立即生效，每秒递减一次', () => {
    const onExpire = vi.fn()
    const probe = renderProbe(3, onExpire)

    expect(probe.remain).toBe(3)
    act(() => vi.advanceTimersByTime(1000))
    expect(probe.remain).toBe(2)
    act(() => vi.advanceTimersByTime(2000))
    expect(probe.remain).toBe(0)
  })

  it('归零时触发一次 onExpire，之后保持为 0 不再重复触发', () => {
    const onExpire = vi.fn()
    const probe = renderProbe(2, onExpire)

    act(() => vi.advanceTimersByTime(2000))
    expect(probe.remain).toBe(0)
    expect(onExpire).toHaveBeenCalledTimes(1)

    act(() => vi.advanceTimersByTime(5000))
    expect(probe.remain).toBe(0)
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('初始值 <= 0 时不启动计时也不触发 onExpire', () => {
    const onExpire = vi.fn()
    const probe = renderProbe(0, onExpire)

    expect(probe.remain).toBe(0)
    act(() => vi.advanceTimersByTime(10_000))
    expect(probe.remain).toBe(0)
    expect(onExpire).not.toHaveBeenCalled()
  })

  it('initialSeconds 变化（重拉校准）：以新值重新计时', () => {
    const onExpire = vi.fn()
    const probe = renderProbe(5, onExpire)

    act(() => vi.advanceTimersByTime(2000))
    expect(probe.remain).toBe(3)

    // 模拟重拉返回新的 remainSeconds（如后端校准为 10）
    probe.rerender(10)
    expect(probe.remain).toBe(10)
    act(() => vi.advanceTimersByTime(1000))
    expect(probe.remain).toBe(9)
  })

  it('归零触发 onExpire 后重拉得到新值：能再次完整倒计时并再次触发', () => {
    const onExpire = vi.fn()
    const probe = renderProbe(1, onExpire)

    act(() => vi.advanceTimersByTime(1000))
    expect(onExpire).toHaveBeenCalledTimes(1)

    // 重拉后后端仍显示未开始且还有 2 秒（时钟偏差场景）
    probe.rerender(2)
    act(() => vi.advanceTimersByTime(2000))
    expect(probe.remain).toBe(0)
    expect(onExpire).toHaveBeenCalledTimes(2)
  })

  it('重拉后归零为新值 0（后端确认进入下一状态）：不再触发 onExpire', () => {
    const onExpire = vi.fn()
    const probe = renderProbe(1, onExpire)

    act(() => vi.advanceTimersByTime(1000))
    expect(onExpire).toHaveBeenCalledTimes(1)

    probe.rerender(0)
    expect(probe.remain).toBe(0)
    act(() => vi.advanceTimersByTime(5000))
    expect(onExpire).toHaveBeenCalledTimes(1)
  })
})
