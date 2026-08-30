/**
 * 测试环境垫片：jsdom 环境未注入 localStorage 时（jsdom 30 默认不提供），
 * 用内存实现补齐，保证 token 模块可被单测覆盖。
 */
if (typeof globalThis.localStorage === 'undefined' || typeof window.localStorage === 'undefined') {
  const store = new Map<string, string>()
  const storage: Storage = {
    get length(): number {
      return store.size
    },
    clear: () => store.clear(),
    getItem: (key: string) => (store.has(key) ? (store.get(key) as string) : null),
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    removeItem: (key: string) => void store.delete(key),
    setItem: (key: string, value: string) => void store.set(key, String(value)),
  }
  Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true })
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true })
}
