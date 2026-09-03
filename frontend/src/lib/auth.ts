/**
 * token 存取与鉴权状态。
 * token 持久化在 localStorage（键 miaosha_token），请求层从存储读取。
 */

const TOKEN_KEY = 'miaosha_token';

/** token 变更事件名（同文档内响应式同步登录态） */
export const AUTH_CHANGE_EVENT = 'miaosha:auth';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  if (localStorage.getItem(TOKEN_KEY) === token) return;
  localStorage.setItem(TOKEN_KEY, token);
  window.dispatchEvent(new Event(AUTH_CHANGE_EVENT));
}

export function clearToken(): void {
  if (!localStorage.getItem(TOKEN_KEY)) return;
  localStorage.removeItem(TOKEN_KEY);
  window.dispatchEvent(new Event(AUTH_CHANGE_EVENT));
}

/** 退出登录：清除凭证（页面跳转由路由守卫 / 调用方负责）。 */
export function logout(): void {
  clearToken();
}

export function isAuthenticated(): boolean {
  return Boolean(getToken());
}
