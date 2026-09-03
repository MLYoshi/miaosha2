import { getData } from '@/lib/api';
import { setToken } from '@/lib/auth';
import type { LoginParams, User } from '@/types/api';

/** 用户领域 API：登录 / 注册 / 个人信息。 */

/** 登录，成功后自动持久化 JWT。 */
export async function login(params: LoginParams): Promise<string> {
  const token = await getData<string>({ url: '/user/login', method: 'post', data: params });
  setToken(token);
  return token;
}

/** 注册，成功返回 token 并自动登录。 */
export async function register(params: LoginParams): Promise<string> {
  const token = await getData<string>({ url: '/user/register', method: 'post', data: params });
  setToken(token);
  return token;
}

/** 当前登录用户信息（敏感字段后端已置 null）。 */
export function getProfile(): Promise<User> {
  return getData<User>({ url: '/user/profile', method: 'get' });
}
