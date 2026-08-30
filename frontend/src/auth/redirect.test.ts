import { describe, expect, it } from 'vitest'
import { buildLoginUrl, sanitizeRedirect } from './redirect'

/**
 * redirect 参数契约测试：只放行站内相对路径（防开放重定向），
 * buildLoginUrl 负责编码完整站内路径。
 */

describe('sanitizeRedirect', () => {
  it('空值/缺失：回退首页', () => {
    expect(sanitizeRedirect(null)).toBe('/')
    expect(sanitizeRedirect(undefined)).toBe('/')
    expect(sanitizeRedirect('')).toBe('/')
  })

  it('合法站内路径（含 query/hash）：原样返回', () => {
    expect(sanitizeRedirect('/goods/1?from=list')).toBe('/goods/1?from=list')
    expect(sanitizeRedirect('/')).toBe('/')
  })

  it('URL 编码的合法路径：解码后返回', () => {
    expect(sanitizeRedirect(encodeURIComponent('/goods/1?tab=detail'))).toBe('/goods/1?tab=detail')
  })

  it('绝对 URL：拒绝回首页', () => {
    expect(sanitizeRedirect('https://evil.com')).toBe('/')
    expect(sanitizeRedirect(encodeURIComponent('http://evil.com'))).toBe('/')
  })

  it('协议相对 //：拒绝回首页', () => {
    expect(sanitizeRedirect('//evil.com')).toBe('/')
  })

  it('反斜杠绕过：拒绝回首页', () => {
    expect(sanitizeRedirect('/\\evil.com')).toBe('/')
    expect(sanitizeRedirect('\\/evil.com')).toBe('/')
  })

  it('非 / 开头的相对路径：拒绝回首页', () => {
    expect(sanitizeRedirect('goods/1')).toBe('/')
  })
})

describe('buildLoginUrl', () => {
  it('拼接编码后的 redirect 参数', () => {
    expect(buildLoginUrl('/profile')).toBe('/login?redirect=%2Fprofile')
    expect(buildLoginUrl('/goods/1?tab=detail')).toBe(
      `/login?redirect=${encodeURIComponent('/goods/1?tab=detail')}`,
    )
  })
})
