import { request } from './http'
import type { MiaoshaAcceptVo, MiaoshaResultVo } from './types'

/**
 * 秒杀领域 API：
 * - doMiaosha：受理（goodsId 走 query 参数）；SUCCESS=降级同步落库直接拿单，PROCESSING=已入队
 * - getMiaoshaResult：结果轮询；SUCCESS/FAILED/NONE 为终态
 */

/** 发起秒杀抢购（POST /miaosha/do_miaosha?goodsId=） */
export function doMiaosha(goodsId: number | string): Promise<MiaoshaAcceptVo> {
  return request<MiaoshaAcceptVo>({ method: 'POST', url: '/miaosha/do_miaosha', params: { goodsId } })
}

/** 查询秒杀结果（GET /miaosha/result?goodsId=），轮询用 */
export function getMiaoshaResult(goodsId: number | string): Promise<MiaoshaResultVo> {
  return request<MiaoshaResultVo>({ method: 'GET', url: '/miaosha/result', params: { goodsId } })
}
