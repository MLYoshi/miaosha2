import { message } from 'antd'
import { useCallback, useState } from 'react'
import { preheatStock } from '../api/admin'
import { ApiError } from '../api/errorCode'

export interface UsePreheatResult {
  /** 提交中（含防连点） */
  submitting: boolean
  /** 提交预热：成功 toast「预热完成」；失败 toast 后端/兜底文案（不抛出） */
  submit: (goodsId: number) => Promise<void>
}

/**
 * 管理员预热工具页提交逻辑（issue 07）：
 * 错误文案由 http.ts 归一为 ApiError.message（服务端 msg 优先），
 * 非业务异常统一兜底，页面不会因失败白屏。
 */
export function usePreheat(): UsePreheatResult {
  const [submitting, setSubmitting] = useState(false)

  const submit = useCallback(
    async (goodsId: number) => {
      if (submitting) return
      setSubmitting(true)
      try {
        await preheatStock(goodsId)
        message.success('预热完成')
      } catch (e) {
        // 业务失败（ApiError）透出归一后的后端文案；网络异常等统一兜底
        message.error(e instanceof ApiError ? e.message : '预热失败，请稍后重试')
      } finally {
        setSubmitting(false)
      }
    },
    [submitting],
  )

  return { submitting, submit }
}
