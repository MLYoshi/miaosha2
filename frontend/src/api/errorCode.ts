/**
 * 业务错误码 → 中文文案映射表。
 *
 * 与后端 CodeMsg 枚举（common/CodeMsg.java）对齐，作为服务端 msg 缺失时的兜底文案。
 */
export const ERROR_MESSAGE_MAP: Record<number, string> = {
  0: 'success',
  500100: '服务端异常',
  500104: '商品不存在',
  500212: '不能重复秒杀',
  500214: '库存不足',
  500215: '秒杀未开始',
  500216: '秒杀已结束',
  500501: '手机号不存在',
  500502: '密码错误',
  500503: '手机号已注册',
}

/** 服务端 msg 优先，映射表兜底，最后以错误码占位 */
export function resolveErrorMessage(code: number, serverMsg?: string | null): string {
  if (serverMsg && serverMsg.trim() !== '') {
    return serverMsg
  }
  return ERROR_MESSAGE_MAP[code] ?? `请求失败（错误码 ${code}）`
}

/** 统一 API 错误：业务失败（code !== 0 / HTTP 400）与协议失败（401 / 网络异常）都会 reject 为该类型 */
export class ApiError extends Error {
  /** 业务错误码；协议层错误时为 HTTP 状态码或 -1（网络异常） */
  readonly code: number
  /** HTTP 状态码；网络异常时为 undefined */
  readonly httpStatus?: number

  constructor(message: string, code: number, httpStatus?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}
