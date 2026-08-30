import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { AUTH_UNAUTHORIZED_EVENT } from '../auth/events'
import { buildLoginUrl } from '../auth/redirect'
import { clearToken, getToken } from '../auth/token'
import { ApiError, resolveErrorMessage } from './errorCode'
import type { Result } from './types'

/**
 * 统一 axios 实例与响应约定：
 *
 * - 请求拦截器：自动携带 `Authorization: Bearer <token>`
 * - 响应拦截器处理三种形态：
 *   1. HTTP 401（JWT 缺失/失效）：清除 token 跳登录页，reject ApiError
 *   2. HTTP 200 但 code !== 0（业务失败）：按业务错误 reject ApiError，文案映射表兜底
 *   3. HTTP 400（@Valid 参数校验失败）：透出后端 msg，reject ApiError
 * - 其余 HTTP 错误 / 网络异常：统一归一为 ApiError
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function redirectToLogin(): void {
  if (window.location.pathname !== '/login') {
    // 携带当前完整路径，登录成功后回跳原目标页
    const { pathname, search, hash } = window.location
    window.location.href = buildLoginUrl(pathname + search + hash)
  }
}

http.interceptors.response.use(
  (response) => {
    const result = response.data as Result<unknown> | undefined
    // 后端所有接口都返回 Result 包装；防御性判断非 Result 结构直接放行
    if (result && typeof result === 'object' && typeof result.code === 'number') {
      if (result.code !== 0) {
        return Promise.reject(
          new ApiError(resolveErrorMessage(result.code, result.msg), result.code, response.status),
        )
      }
    }
    return response
  },
  (error: AxiosError<Result<unknown>>) => {
    if (error.response) {
      const { status, data } = error.response

      // 1. 未登录 / token 失效：清 token，派发事件让 AuthContext 同步，再带 redirect 跳登录
      if (status === 401) {
        clearToken()
        window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
        redirectToLogin()
        return Promise.reject(new ApiError('登录已过期，请重新登录', 401, 401))
      }

      // 2. 参数校验失败：透出后端 msg（GlobalExceptionHandler 返回 Result{code:400,msg}）
      if (status === 400) {
        return Promise.reject(
          new ApiError(resolveErrorMessage(data?.code ?? 400, data?.msg ?? '参数校验失败'), data?.code ?? 400, 400),
        )
      }

      // 3. 其他 HTTP 错误（含 405 等）：有 body 用 body，否则按状态码兜底
      return Promise.reject(
        new ApiError(
          resolveErrorMessage(data?.code ?? status, data?.msg ?? `请求失败（HTTP ${status}）`),
          data?.code ?? status,
          status,
        ),
      )
    }

    // 网络异常 / 超时 / 请求被取消
    return Promise.reject(new ApiError('网络异常，请稍后重试', -1))
  },
)

/** 发起请求并解包 Result，成功时直接返回 data（调用方拿到的即业务数据） */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<Result<T>>(config)
  return response.data.data as T
}

export default http
