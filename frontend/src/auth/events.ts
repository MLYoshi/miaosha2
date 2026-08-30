/**
 * http 拦截器（非 React 环境）与 AuthContext（React 环境）之间的解耦信号：
 * 任意请求收到 HTTP 401 时，拦截器清掉 token 后派发该事件，
 * AuthProvider 监听并同步自身状态，避免依赖整页刷新。
 */
export const AUTH_UNAUTHORIZED_EVENT = 'seckill:auth-unauthorized'
