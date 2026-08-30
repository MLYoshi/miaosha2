/**
 * 与后端 VO 一一对应的 API 类型定义。
 *
 * 字段来源（backend/src/main/java/com/example/seckill）：
 * - common/Result.java                → Result
 * - domain/User.java                  → User
 * - domain/Goods.java + vo/GoodsVo.java → GoodsVo
 * - vo/GoodsDetailVo.java             → GoodsDetailVo
 * - vo/LoginVo.java                   → LoginParams
 * - vo/MiaoshaAcceptVo.java           → MiaoshaAcceptVo
 * - vo/MiaoshaResultVo.java           → MiaoshaResultVo
 */

/** 通用响应包装，code === 0 表示成功 */
export interface Result<T> {
  code: number
  msg: string
  data: T | null
}

/** 登录/注册参数（手机号 + 密码） */
export interface LoginParams {
  mobile: string
  password: string
}

/** 用户信息；GET /user/profile 中 password/salt 由后端置为 null */
export interface User {
  id: number
  nickname: string
  password: string | null
  salt: string | null
  head: string | null
  /** ISO 8601 时间字符串 */
  registerDate: string | null
  /** ISO 8601 时间字符串 */
  lastLoginDate: string | null
  loginCount: number | null
}

/** 商品列表项（含秒杀扩展字段） */
export interface GoodsVo {
  id: number
  goodsName: string
  goodsTitle: string
  goodsImg: string
  goodsDetail: string
  goodsPrice: number
  goodsStock: number
  /** 秒杀价 */
  miaoshaPrice: number
  /** 秒杀剩余库存（预扣口径） */
  stockCount: number
  /** 秒杀开始时间，ISO 8601 */
  startDate: string | null
  /** 秒杀结束时间，ISO 8601 */
  endDate: string | null
}

/** 秒杀窗口状态（MiaoshaStatus） */
export const MIAOSHA_STATUS = {
  NOT_START: 0,
  IN_PROGRESS: 1,
  OVER: 2,
} as const

export type MiaoshaStatus = (typeof MIAOSHA_STATUS)[keyof typeof MIAOSHA_STATUS]

/** 商品详情（含秒杀窗口） */
export interface GoodsDetailVo {
  goods: GoodsVo
  miaoshaStatus: MiaoshaStatus
  remainSeconds: number
}

/** 秒杀受理状态：PROCESSING=受理排队中；SUCCESS=降级同步落库直接拿单 */
export type MiaoshaAcceptStatus = 'PROCESSING' | 'SUCCESS'

export interface MiaoshaAcceptVo {
  status: MiaoshaAcceptStatus
  orderId: number | null
}

/** 秒杀结果四态：NONE=未参与 */
export type MiaoshaResultStatus = 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'NONE'

export interface MiaoshaResultVo {
  status: MiaoshaResultStatus
  orderId: number | null
}
