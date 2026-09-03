import { getData } from '@/lib/api';
import type { MiaoshaConfig } from '@/types/api';

/** 管理端领域 API：预热库存 / 重置秒杀配置。 */

/** 预热指定商品 Redis 库存。 */
export function preheatStock(goodsId: number): Promise<string> {
  return getData<string>({
    url: '/admin/preheat',
    method: 'post',
    params: { goodsId },
  });
}

/**
 * 重置某商品秒杀配置：时间窗对齐（now → now + durationMinutes）+ 可选重置库存，
 * 同时重写 Redis 预扣库存 Key。
 */
export function resetMiaosha(
  goodsId: number,
  durationMinutes: number,
  stockCount?: number,
): Promise<MiaoshaConfig> {
  return getData<MiaoshaConfig>({
    url: `/admin/goods/${goodsId}/miaosha`,
    method: 'post',
    params: {
      durationMinutes,
      ...(stockCount != null ? { stockCount } : {}),
    },
  });
}
