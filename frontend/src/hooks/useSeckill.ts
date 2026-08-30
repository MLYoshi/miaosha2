import { useQuery, useQueryClient } from '@tanstack/react-query'
import { message } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../api/errorCode'
import { doMiaosha, getMiaoshaResult } from '../api/miaosha'
import {
  POLL_INTERVAL_MS,
  POLL_TIMEOUT_MESSAGE,
  SECKILL_FLOW_PHASE,
  isPollTimeout,
  isTerminalPollStatus,
  resolveAcceptFlow,
  shouldDisableAfterSubmitError,
  type SeckillFlowState,
} from '../utils/seckillFlow'

/**
 * 秒杀下单 + 结果轮询（issue 06）：
 *
 * - submit：POST /miaosha/do_miaosha；SUCCESS 直接终态展示订单号，
 *   PROCESSING 进入轮询；SUBMITTING/PROCESSING 期间 isBusy 防连点
 * - 轮询：TanStack Query refetchInterval 每 1 秒调 GET /miaosha/result，
 *   出现 SUCCESS/FAILED/NONE 终态即停
 * - 30 秒未出结果自动停止，message.info 兜底提示
 * - 业务失败（重复秒杀/库存不足/未开始/已结束等）toast 提示且不进轮询；
 *   库存不足/已结束置 finished，让按钮回到禁用终局
 */

const IDLE_STATE: SeckillFlowState = { phase: SECKILL_FLOW_PHASE.IDLE, orderId: null }

export interface UseSeckillResult {
  phase: SeckillFlowState['phase']
  orderId: number | null
  /** 受理发出到终态期间为 true：按钮应 loading 防重复提交 */
  isBusy: boolean
  /** 库存不足/已结束：按钮应回到禁用终局 */
  finished: boolean
  /** 发起抢购（busy 中调用为空操作） */
  submit: () => Promise<void>
}

export function useSeckill(goodsId: number | string): UseSeckillResult {
  const [flow, setFlow] = useState<SeckillFlowState>(IDLE_STATE)
  const [finished, setFinished] = useState(false)
  const queryClient = useQueryClient()
  const resultQueryKey = useMemo(() => ['miaosha', 'result', String(goodsId)] as const, [goodsId])
  const pollStartedAtRef = useRef<number | null>(null)

  // 切换商品（同路由复用组件）时复位上一件的流转状态
  useEffect(() => {
    setFlow(IDLE_STATE)
    setFinished(false)
    pollStartedAtRef.current = null
  }, [goodsId])

  // 结果轮询：仅 PROCESSING 时启用，每 1 秒一次
  const resultQuery = useQuery({
    queryKey: resultQueryKey,
    queryFn: () => getMiaoshaResult(goodsId),
    enabled: flow.phase === SECKILL_FLOW_PHASE.PROCESSING,
    refetchInterval: POLL_INTERVAL_MS,
    retry: false,
  })

  // 轮询结果 → 终态流转
  useEffect(() => {
    if (flow.phase !== SECKILL_FLOW_PHASE.PROCESSING) return
    const result = resultQuery.data
    if (!result || !isTerminalPollStatus(result.status)) return

    if (result.status === 'SUCCESS') {
      setFlow({ phase: SECKILL_FLOW_PHASE.SUCCESS, orderId: result.orderId })
      return
    }
    if (result.status === 'FAILED') {
      message.error('很遗憾，抢购失败')
    } else {
      message.error('未查询到本次抢购记录')
    }
    setFlow({ phase: result.status, orderId: null })
  }, [resultQuery.data, flow.phase])

  // 30 秒兜底：到时停止轮询并提示（不依赖是否有数据返回）
  useEffect(() => {
    if (flow.phase !== SECKILL_FLOW_PHASE.PROCESSING) return
    const startedAt = pollStartedAtRef.current
    if (startedAt === null) return

    const timer = setInterval(() => {
      if (isPollTimeout(startedAt, Date.now())) {
        message.info(POLL_TIMEOUT_MESSAGE)
        setFlow({ phase: SECKILL_FLOW_PHASE.TIMEOUT, orderId: null })
      }
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [flow.phase])

  const submit = useCallback(async (): Promise<void> => {
    if (flow.phase === SECKILL_FLOW_PHASE.SUBMITTING || flow.phase === SECKILL_FLOW_PHASE.PROCESSING) {
      return
    }
    setFlow({ phase: SECKILL_FLOW_PHASE.SUBMITTING, orderId: null })
    try {
      const accept = await doMiaosha(goodsId)
      const next = resolveAcceptFlow(accept)
      if (next.phase === SECKILL_FLOW_PHASE.PROCESSING) {
        // 清掉上一轮可能残留的轮询缓存，避免旧结果直接触发终态流转
        pollStartedAtRef.current = Date.now()
        queryClient.removeQueries({ queryKey: resultQueryKey })
      }
      setFlow(next)
    } catch (error) {
      setFlow(IDLE_STATE)
      if (error instanceof ApiError) {
        // 文案已由 http 层按服务端 msg / 错误码映射表归一
        message.error(error.message)
        if (shouldDisableAfterSubmitError(error.code)) {
          setFinished(true)
        }
      } else {
        message.error('网络异常，请稍后重试')
      }
    }
  }, [flow.phase, goodsId, queryClient, resultQueryKey])

  return {
    phase: flow.phase,
    orderId: flow.orderId,
    isBusy: flow.phase === SECKILL_FLOW_PHASE.SUBMITTING || flow.phase === SECKILL_FLOW_PHASE.PROCESSING,
    finished,
    submit,
  }
}
