import { getData } from '@/lib/api';
import type { MiaoshaAcceptVo, MiaoshaResultVo } from '@/types/api';

/** 秒杀领域 API：提交秒杀 / 结果轮询。 */

/**
 * 提交秒杀（POST /miaosha/do_miaosha）。
 * - PROCESSING：预扣成功、消息入队，需轮询 result 拿单；
 * - SUCCESS：降级同步落库成功，直接携带订单号。
 */
export function doMiaosha(goodsId: number): Promise<MiaoshaAcceptVo> {
  return getData<MiaoshaAcceptVo>({
    url: '/miaosha/do_miaosha',
    method: 'post',
    params: { goodsId },
  });
}

/**
 * 轮询秒杀结果（GET /miaosha/result）。
 * 四态：PROCESSING / SUCCESS / FAILED / NONE。
 */
export function getMiaoshaResult(goodsId: number): Promise<MiaoshaResultVo> {
  return getData<MiaoshaResultVo>({
    url: '/miaosha/result',
    method: 'get',
    params: { goodsId },
  });
}
