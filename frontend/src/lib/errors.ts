/**
 * 错误处理：CodeMsg 错误码 → 中文提示映射。
 *
 * 与 common/src/main/java/com/example/common/CodeMsg.java 保持同码同义，
 * 后端新增错误码时需同步维护此映射。
 */

/** 后端 CodeMsg 全量错误码。 */
export const CODE_MSG: Record<number, string> = {
  0: 'success',
  500100: '服务端异常',
  500104: '商品不存在',
  500105: '非法参数',
  500212: '不能重复秒杀',
  500214: '库存不足',
  500215: '秒杀未开始',
  500216: '秒杀已结束',
  500401: '未登录或token无效',
  500501: '手机号不存在',
  500502: '密码错误',
  500503: '手机号已注册',
};

/** 会话失效错误码（清 token + 跳登录）。 */
export const SESSION_EXPIRED_CODE = 500401;

/** 业务错误：code !== 0 的统一异常形态。 */
export class ApiError extends Error {
  /** 后端 Result.code，网络/HTTP 异常时为 -1 */
  readonly code: number;
  /** 展示给用户的中文提示 */
  readonly displayMsg: string;

  constructor(code: number, msg: string, displayMsg?: string) {
    super(displayMsg ?? msg);
    this.name = 'ApiError';
    this.code = code;
    this.displayMsg = displayMsg ?? msg;
  }
}

/** 解析用户可读的错误文案：优先用错误码映射，其次后端 msg，最后兜底。 */
export function resolveErrorMsg(code: number, msg?: string): string {
  return CODE_MSG[code] ?? (msg || '服务端异常，请稍后重试');
}

type ToastHandler = (message: string) => void;

let toastHandler: ToastHandler | null = null;

/** 注册全局 toast 出口（由 toast 组件在应用挂载时调用）。 */
export function registerToastHandler(handler: ToastHandler): () => void {
  toastHandler = handler;
  return () => {
    if (toastHandler === handler) toastHandler = null;
  };
}

/**
 * 业务错误统一经 toast 提示。
 * 有注册的 toast 处理器则走处理器，否则派发全局事件兜底（避免未挂载时丢失提示）。
 */
export function toastError(message: string): void {
  if (toastHandler) {
    toastHandler(message);
    return;
  }
  window.dispatchEvent(
    new CustomEvent('miaosha:toast', { detail: { message, variant: 'error' } }),
  );
}
