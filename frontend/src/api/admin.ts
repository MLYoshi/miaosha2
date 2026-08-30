import { request } from './http'

/** 管理端：预热某商品的 Redis 秒杀库存（当前无角色体系，仅需登录） */
export function preheatStock(goodsId: number): Promise<string> {
  return request<string>({
    method: 'POST',
    url: '/admin/preheat',
    params: { goodsId },
  })
}
