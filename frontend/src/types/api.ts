/**
 * 类型契约层：与后端 VO / Result 1:1 对齐，杜绝字段名漂移。
 *
 * 来源对照：
 * - common/src/main/java/com/example/common/Result.java
 * - user-service  User / LoginVo
 * - goods-service GoodsVo / GoodsDetailVo
 * - miaosha-service MiaoshaAcceptVo / MiaoshaResultVo
 * - miaosha AdminController 返回的 MiaoshaConfig（对齐 goods MiaoshaConfigVo）
 */

/** 后端统一响应壳（Result<T>）。code === 0 为成功。 */
export interface Result<T> {
  code: number;
  msg: string;
  data: T;
}

/** 用户信息（miaosha_user 表，敏感字段后端已置 null）。 */
export interface User {
  id: number;
  nickname: string;
  /** 后端隐藏敏感信息后为 null */
  password: string | null;
  salt: string | null;
  head: string | null;
  /** LocalDateTime 序列化为 ISO 字符串，如 2026-09-03T10:00:00 */
  registerDate: string | null;
  lastLoginDate: string | null;
  loginCount: number | null;
}

/** 登录 / 注册请求体（LoginVo）。 */
export interface LoginParams {
  mobile: string;
  password: string;
}

/** 商品列表项（GoodsVo = Goods + 秒杀字段）。 */
export interface GoodsVo {
  id: number;
  goodsName: string;
  goodsTitle: string;
  goodsImg: string;
  goodsDetail: string;
  /** 原价 */
  goodsPrice: number;
  goodsStock: number;
  /** 秒杀价 */
  miaoshaPrice: number;
  /** 秒杀剩余库存 */
  stockCount: number;
  startDate: string;
  endDate: string;
}

/** 秒杀状态：0 未开始 / 1 进行中 / 2 已结束 */
export type MiaoshaStatus = 0 | 1 | 2;

/** 商品详情（含时间窗状态与剩余秒数，用于倒计时）。 */
export interface GoodsDetailVo {
  goods: GoodsVo;
  miaoshaStatus: MiaoshaStatus;
  /** 未开始时为距开始的秒数，其余为 0 */
  remainSeconds: number;
}

/** 秒杀受理响应（do_miaosha）。 */
export type MiaoshaAcceptVo =
  | { status: 'PROCESSING'; orderId: null }
  | { status: 'SUCCESS'; orderId: number };

/** 秒杀结果四态（result 轮询）。 */
export type MiaoshaResultStatus = 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'NONE';

export type MiaoshaResultVo =
  | { status: 'PROCESSING'; orderId: null }
  | { status: 'SUCCESS'; orderId: number }
  | { status: 'FAILED'; orderId: null }
  | { status: 'NONE'; orderId: null };

/** 重置秒杀配置后的回显（goods MiaoshaConfigVo）。 */
export interface MiaoshaConfig {
  goodsId: number;
  startDate: string;
  endDate: string;
  stockCount: number | null;
}
