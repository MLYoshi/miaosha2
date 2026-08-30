// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AuthProvider, useAuth, type AuthContextValue } from './AuthContext'
import { AUTH_UNAUTHORIZED_EVENT } from './events'
import { clearToken, getToken, setToken } from './token'

/**
 * 全局鉴权 Context 契约测试（对应 issue 03 验收项）：
 * - localStorage 持久化（刷新后仍保持登录）
 * - signIn/signOut 同步状态与存储
 * - http 拦截器派发 401 事件后状态清空
 */

// React 18.3 的 act 需要
;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('AuthContext', () => {
  let container: HTMLDivElement
  let root: Root | undefined

  beforeEach(() => {
    clearToken()
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    if (root) {
      act(() => root!.unmount())
      root = undefined
    }
    container.remove()
  })

  /** 渲染 AuthProvider + 捕获 useAuth() 值的探针组件 */
  function renderProbe(): () => AuthContextValue {
    let captured: AuthContextValue | undefined
    function Probe() {
      captured = useAuth()
      return null
    }
    act(() => {
      root = createRoot(container)
      root.render(
        <AuthProvider>
          <Probe />
        </AuthProvider>,
      )
    })
    return () => captured!
  }

  it('初始无 token：未认证', () => {
    const getAuth = renderProbe()
    expect(getAuth().isAuthenticated).toBe(false)
    expect(getAuth().token).toBeNull()
  })

  it('localStorage 已有 token：初始即认证（刷新保持登录）', () => {
    setToken('persisted-jwt')
    const getAuth = renderProbe()
    expect(getAuth().isAuthenticated).toBe(true)
    expect(getAuth().token).toBe('persisted-jwt')
  })

  it('signIn：更新全局状态并持久化 localStorage', () => {
    const getAuth = renderProbe()
    act(() => getAuth().signIn('jwt-new'))
    expect(getAuth().isAuthenticated).toBe(true)
    expect(getAuth().token).toBe('jwt-new')
    expect(getToken()).toBe('jwt-new')
  })

  it('signOut：清空全局状态与 localStorage', () => {
    setToken('jwt-old')
    const getAuth = renderProbe()
    act(() => getAuth().signOut())
    expect(getAuth().isAuthenticated).toBe(false)
    expect(getAuth().token).toBeNull()
    expect(getToken()).toBeNull()
  })

  it('收到 401 事件（http 拦截器派发）：全局状态同步清空', () => {
    setToken('expired-jwt')
    const getAuth = renderProbe()
    expect(getAuth().isAuthenticated).toBe(true)
    act(() => {
      window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
    })
    expect(getAuth().isAuthenticated).toBe(false)
  })

  it('useAuth 在 Provider 外使用：抛错', () => {
    const consoleError = console.error
    console.error = () => {}
    try {
      let thrown: unknown
      function Probe() {
        try {
          useAuth()
        } catch (e) {
          thrown = e
        }
        return null
      }
      act(() => {
        root = createRoot(container)
        root.render(<Probe />)
      })
      expect(thrown).toBeInstanceOf(Error)
    } finally {
      console.error = consoleError
    }
  })
})
