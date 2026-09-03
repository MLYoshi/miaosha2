/**
 * token 存取与鉴权状态。
 * token 持久化在 localStorage（键 miaosha_token），请求层从存储读取。
 */

const TOKEN_KEY = 'miaosha_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/** 退出登录：清除凭证（页面跳转由路由守卫 / 调用方负责）。 */
export function logout(): void {
  clearToken();
}

export function isAuthenticated(): boolean {
  return Boolean(getToken());
}
