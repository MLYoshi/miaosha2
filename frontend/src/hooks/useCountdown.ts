import { useEffect, useRef, useState } from 'react'

/**
 * 本地秒级倒计时：
 *
 * - 由后端权威 remainSeconds 驱动（initialSeconds 变化即重新校准重新计时）
 * - 每秒本地递减，仅作展示；归零时触发一次 onExpire，
 *   调用方应在 onExpire 中重拉后端状态做权威校准（不允许本地归零直接切换业务形态）
 * - initialSeconds <= 0 时不启动计时、不触发 onExpire
 *
 * @returns 当前剩余秒数（始终 >= 0）
 */
export function useCountdown(initialSeconds: number, onExpire?: () => void): number {
  const [remain, setRemain] = useState(() => normalize(initialSeconds))
  // onExpire 透传 ref，避免调用方回调身份变化导致计时器重建
  const onExpireRef = useRef(onExpire)
  onExpireRef.current = onExpire

  useEffect(() => {
    const start = normalize(initialSeconds)
    setRemain(start)
    if (start <= 0) return

    let current = start
    const timer = setInterval(() => {
      current -= 1
      setRemain(current)
      if (current <= 0) {
        clearInterval(timer)
        onExpireRef.current?.()
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [initialSeconds])

  return remain
}

function normalize(seconds: number): number {
  return Math.max(0, Math.floor(seconds))
}
