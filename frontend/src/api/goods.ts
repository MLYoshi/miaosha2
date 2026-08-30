import { request } from './http'
import type { GoodsDetailVo, GoodsVo } from './types'

/** 商品列表（含秒杀价/库存/时间窗口） */
export function listGoods(): Promise<GoodsVo[]> {
  return request<GoodsVo[]>({ method: 'GET', url: '/goods/list' })
}

/** 商品详情（含秒杀窗口状态与剩余秒数） */
export function getGoodsDetail(goodsId: number | string): Promise<GoodsDetailVo> {
  return request<GoodsDetailVo>({ method: 'GET', url: `/goods/detail/${goodsId}` })
}
