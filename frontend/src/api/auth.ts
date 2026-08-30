import { request } from './http'
import type { LoginParams, User } from './types'

/** 登录：成功返回 JWT（放行接口，无需 token） */
export function login(params: LoginParams): Promise<string> {
  return request<string>({ method: 'POST', url: '/user/login', data: params })
}

/** 注册：成功直接返回 JWT（免登录） */
export function register(params: LoginParams): Promise<string> {
  return request<string>({ method: 'POST', url: '/user/register', data: params })
}

/** 当前登录用户信息（password/salt 为 null） */
export function getProfile(): Promise<User> {
  return request<User>({ method: 'GET', url: '/user/profile' })
}
