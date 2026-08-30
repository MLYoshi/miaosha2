import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { AUTH_UNAUTHORIZED_EVENT } from './events'
import { clearToken, getToken, setToken } from './token'

export interface AuthContextValue {
  /** 当前 JWT；未登录为 null */
  token: string | null
  isAuthenticated: boolean
  /** 登录/注册成功后写入 token（localStorage 持久化 + 更新全局状态） */
  signIn: (token: string) => void
  /** 退出登录：清空 token 与全局状态 */
  signOut: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

/** 全局鉴权 Context：初始值取自 localStorage，刷新页面登录态不丢 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken())

  const signIn = useCallback((next: string) => {
    setToken(next)
    setTokenState(next)
  }, [])

  const signOut = useCallback(() => {
    clearToken()
    setTokenState(null)
  }, [])

  // http 拦截器收到 401 时已 clearToken 并派发事件；此处直接置空状态（事件语义 = 未授权）
  useEffect(() => {
    const onUnauthorized = () => setTokenState(null)
    window.addEventListener(AUTH_UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(AUTH_UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ token, isAuthenticated: token !== null, signIn, signOut }),
    [token, signIn, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth 必须在 <AuthProvider> 内使用')
  }
  return ctx
}
