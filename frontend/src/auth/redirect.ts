/**
 * 登录回跳相关纯函数（无副作用，便于单测）。
 *
 * redirect 参数约定：受控页被守卫拦下时，把"当前完整站内路径（path + search + hash）"
 * encodeURIComponent 后放进 /login?redirect=...；登录/注册成功后回跳该地址。
 */

/** 只放行站内相对路径，防开放重定向（绝对 URL / 协议相对 // / 反斜杠绕过一律回退首页） */
export function sanitizeRedirect(raw: string | null | undefined): string {
  if (!raw) return '/'
  let decoded = raw
  try {
    decoded = decodeURIComponent(raw)
  } catch {
    // 非法编码：保持原值，走后续白名单判断
  }
  if (decoded.startsWith('/') && !decoded.startsWith('//') && !decoded.includes('\\')) {
    return decoded
  }
  return '/'
}

/** 构造携带 redirect 参数的登录页地址 */
export function buildLoginUrl(currentPath: string): string {
  return `/login?redirect=${encodeURIComponent(currentPath)}`
}
