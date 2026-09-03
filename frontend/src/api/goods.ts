import { getData } from '@/lib/api';
import type { GoodsDetailVo, GoodsVo } from '@/types/api';

/** 商品领域 API：列表 / 详情。 */

/** 秒杀会场商品列表。 */
export function listGoods(): Promise<GoodsVo[]> {
  return getData<GoodsVo[]>({ url: '/goods/list', method: 'get' });
}

/** 商品详情（含 miaoshaStatus / remainSeconds，驱动倒计时与状态展示）。 */
export function getGoodsDetail(goodsId: number): Promise<GoodsDetailVo> {
  return getData<GoodsDetailVo>({ url: `/goods/detail/${goodsId}`, method: 'get' });
}
