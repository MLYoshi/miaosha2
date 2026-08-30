import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import { buildLoginUrl } from './redirect'

/**
 * 路由守卫：未登录访问任何受控页面（挂在守卫之下的路由）时，
 * 重定向到登录页并携带 redirect 参数（当前完整站内路径），登录成功后回跳。
 */
export default function RequireAuth() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to={buildLoginUrl(location.pathname + location.search + location.hash)} replace />
  }
  return <Outlet />
}
