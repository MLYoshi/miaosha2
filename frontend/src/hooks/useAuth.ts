import { useCallback, useSyncExternalStore } from 'react';

import { AUTH_CHANGE_EVENT, clearToken, getToken } from '@/lib/auth';

/**
 * 鉴权状态 hook：登录态响应式（登录/退出/会话失效清 token 后自动重渲染）。
 *
 * 订阅 lib/auth 的 token 变更事件，getSnapshot 读取 localStorage 实时值。
 */
function subscribe(onChange: () => void): () => void {
  window.addEventListener(AUTH_CHANGE_EVENT, onChange);
  // 兼容跨标签页（storage 事件仅在其他标签页修改时触发）
  window.addEventListener('storage', onChange);
  return () => {
    window.removeEventListener(AUTH_CHANGE_EVENT, onChange);
    window.removeEventListener('storage', onChange);
  };
}

function getSnapshot(): string | null {
  return getToken();
}

export function useAuth() {
  const token = useSyncExternalStore(subscribe, getSnapshot, () => null);

  const logout = useCallback(() => {
    clearToken();
  }, []);

  return {
    /** 当前 JWT（未登录为 null） */
    token,
    /** 是否已登录 */
    loggedIn: Boolean(token),
    /** 退出登录（仅清凭证，跳转由调用方负责） */
    logout,
  };
}
