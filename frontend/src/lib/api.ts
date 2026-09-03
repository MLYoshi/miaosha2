import axios, { AxiosError } from 'axios';

import { clearToken, getToken } from './auth';
import { ApiError, SESSION_EXPIRED_CODE, resolveErrorMsg, toastError } from './errors';
import type { Result } from '@/types/api';

/**
 * axios 请求层：统一拦截器。
 *
 * - baseURL 默认 '/'，走 Vite proxy（/user /goods /miaosha /admin → gateway:8080）；
 *   可用 VITE_API_BASE_URL 覆盖。
 * - 请求拦截：自动注入 Authorization: Bearer <token>。
 * - 响应拦截：code === 0 视为成功并返回完整 Result；非 0 抛 ApiError 并 toast；
 *   500401（会话失效）清 token 跳登录并携带回跳地址。
 * - HTTP 层错误（网络/超时/非 2xx）归一化为 ApiError（code = -1）。
 */

const REDIRECT_KEY = 'redirect';

export function buildLoginPath(redirect?: string): string {
  if (!redirect) return '/login';
  return `/login?${REDIRECT_KEY}=${encodeURIComponent(redirect)}`;
}

function redirectToLogin(): void {
  const { pathname, search } = window.location;
  // 登录/注册页本身失效不跳转，避免循环
  if (pathname.startsWith('/login') || pathname.startsWith('/register')) return;
  window.location.replace(buildLoginPath(pathname + search));
}

/** 统一抛出业务错误 + toast。 */
function fail(code: number, msg?: string): never {
  const displayMsg = resolveErrorMsg(code, msg);
  if (code === SESSION_EXPIRED_CODE) {
    clearToken();
    redirectToLogin();
  }
  throw new ApiError(code, msg ?? displayMsg, displayMsg);
}

/** 会话失效（HTTP 401）走同一条 500401 通道。 */
function failUnauthorized(): never {
  fail(SESSION_EXPIRED_CODE);
}

export const request = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE_URL as string | undefined) || '/',
  timeout: 10_000,
});

// ---- 请求拦截：注入 Bearer token ----
request.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// ---- 响应拦截：统一判定 code === 0 ----
request.interceptors.response.use(
  (response) => {
    const result = response.data as Result<unknown>;
    // 非标准 Result 壳（理论上不应出现）原样放行
    if (result == null || typeof result.code !== 'number') {
      return response;
    }
    if (result.code === 0) {
      return response;
    }
    fail(result.code, result.msg);
  },
  (error: AxiosError<Result<unknown>>) => {
    if (error.response) {
      const { status, data } = error.response;
      if (status === 401) {
        failUnauthorized();
      }
      // 网关/服务返回的错误壳若携带业务码，按业务码处理
      if (data && typeof data.code === 'number' && data.code !== 0) {
        fail(data.code, data.msg);
      }
      fail(-1, `请求失败（HTTP ${status}）`);
    }
    // 网络错误 / 超时
    const msg = error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络异常，请检查连接';
    fail(-1, msg);
  },
);

/** 从响应中解出 data（api 层统一出口）。 */
export function unwrap<T>(response: { data: Result<T> }): T {
  return response.data.data;
}

/** 便捷封装：直接拿到 data，错误统一经拦截器抛 ApiError。 */
export async function getData<T>(config: Parameters<typeof request.request>[0]): Promise<T> {
  const response = await request.request<Result<T>>(config);
  return unwrap(response);
}

export { toastError };
