// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import http, { request } from './http'
import { ApiError } from './errorCode'
import { clearToken, getToken, setToken } from '../auth/token'

/**
 * 拦截器契约测试：用自定义 axios adapter 模拟后端三种响应形态，
 * 锁定 http.ts 的归一行为（对应 issue 02 验收项）。
 */

type Responder = (config: InternalAxiosRequestConfig) => { status: number; data: unknown }

function mockAdapter(responder: Responder) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(http.defaults as any).adapter = async (config: InternalAxiosRequestConfig) => {
    const { status, data } = responder(config)
    const response: AxiosResponse = {
      data,
      status,
      statusText: String(status),
      headers: {},
      config,
    }
    if (status >= 400) {
      const error = new Error(`Request failed with status code ${status}`) as Error & {
        config: InternalAxiosRequestConfig
        response: AxiosResponse
        isAxiosError: boolean
      }
      error.config = config
      error.response = response
      error.isAxiosError = true
      throw error
    }
    return response
  }
}

beforeEach(() => {
  clearToken()
})

describe('请求拦截器', () => {
  it('有 token 时自动携带 Authorization: Bearer <token>', async () => {
    setToken('jwt-abc')
    let authHeader: unknown
    mockAdapter((config) => {
      authHeader = config.headers?.Authorization
      return { status: 200, data: { code: 0, msg: 'success', data: null } }
    })
    await request({ url: '/user/profile' })
    expect(authHeader).toBe('Bearer jwt-abc')
  })

  it('无 token 时不携带 Authorization 头', async () => {
    let authHeader: unknown
    mockAdapter((config) => {
      authHeader = config.headers?.Authorization
      return { status: 200, data: { code: 0, msg: 'success', data: null } }
    })
    await request({ url: '/goods/list' })
    expect(authHeader).toBeUndefined()
  })
})

describe('响应拦截器', () => {
  it('HTTP 200 + code=0：解包返回 data', async () => {
    mockAdapter(() => ({
      status: 200,
      data: { code: 0, msg: 'success', data: { status: 'PROCESSING', orderId: null } },
    }))
    const data = await request<{ status: string; orderId: number | null }>({ url: '/miaosha/result' })
    expect(data).toEqual({ status: 'PROCESSING', orderId: null })
  })

  it('HTTP 200 + code!==0：按业务错误 reject，透出服务端 msg', async () => {
    mockAdapter(() => ({
      status: 200,
      data: { code: 500214, msg: '库存不足', data: null },
    }))
    const error = await request({ url: '/miaosha/do_miaosha' }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.code).toBe(500214)
    expect(apiError.message).toBe('库存不足')
  })

  it('HTTP 200 + code!==0 且服务端 msg 为空：使用错误码映射表兜底', async () => {
    mockAdapter(() => ({
      status: 200,
      data: { code: 500212, msg: '', data: null },
    }))
    const error = await request({ url: '/miaosha/do_miaosha' }).catch((e: unknown) => e)
    expect((error as ApiError).code).toBe(500212)
    expect((error as ApiError).message).toBe('不能重复秒杀')
  })

  it('未知业务码：映射表兜底为"请求失败（错误码 xx）"', async () => {
    mockAdapter(() => ({
      status: 200,
      data: { code: 500999, msg: '', data: null },
    }))
    const error = await request({ url: '/miaosha/do_miaosha' }).catch((e: unknown) => e)
    expect((error as ApiError).message).toBe('请求失败（错误码 500999）')
  })

  it('HTTP 400（参数校验失败）：透出后端 msg 并 reject', async () => {
    mockAdapter(() => ({
      status: 400,
      data: { code: 400, msg: '手机号格式错误', data: null },
    }))
    const error = await request({ url: '/user/login' }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.httpStatus).toBe(400)
    expect(apiError.message).toBe('手机号格式错误')
  })

  it('HTTP 401：清除 token 并 reject ApiError', async () => {
    setToken('expired-jwt')
    mockAdapter(() => ({ status: 401, data: undefined }))
    const error = await request({ url: '/user/profile' }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).httpStatus).toBe(401)
    expect(getToken()).toBeNull()
  })

  it('网络异常（无 response）：reject 归一化的 ApiError', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(http.defaults as any).adapter = async () => {
      throw new Error('Network Error')
    }
    const error = await request({ url: '/goods/list' }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(-1)
    expect((error as ApiError).message).toBe('网络异常，请稍后重试')
  })
})
