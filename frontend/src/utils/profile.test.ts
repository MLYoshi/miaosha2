import { describe, expect, it } from 'vitest'
import type { User } from '../api/types'
import { toProfileRows } from './profile'

/**
 * 个人中心展示行组装（对应 issue 07 验收项 1）：
 * - 四个展示字段齐全：昵称/注册时间/最后登录时间/登录次数
 * - 时间字段空值与格式化兜底
 * - loginCount 空值兜底
 */

function userOf(partial: Partial<User>): User {
  return {
    id: 1,
    nickname: 'nick',
    password: null,
    salt: null,
    head: null,
    registerDate: '2026-01-02 03:04:05',
    lastLoginDate: '2026-08-24 10:00:00',
    loginCount: 3,
    ...partial,
  }
}

describe('toProfileRows', () => {
  it('完整数据 → 四行展示且时间被格式化', () => {
    const rows = toProfileRows(userOf({}))

    expect(rows.map((r) => r.label)).toEqual(['昵称', '注册时间', '最后登录时间', '登录次数'])
    expect(rows[0].value).toBe('nick')
    expect(rows[1].value).toBe('2026-01-02 03:04:05')
    expect(rows[2].value).toBe('2026-08-24 10:00:00')
    expect(rows[3].value).toBe('3')
  })

  it('时间字段为 null → 占位符兜底', () => {
    const rows = toProfileRows(userOf({ registerDate: null, lastLoginDate: null }))
    expect(rows[1].value).toBe('—')
    expect(rows[2].value).toBe('—')
  })

  it('loginCount 为 null → 占位符兜底', () => {
    const rows = toProfileRows(userOf({ loginCount: null }))
    expect(rows[3].value).toBe('—')
  })

  it('昵称为空白串 → 占位符兜底', () => {
    const rows = toProfileRows(userOf({ nickname: '   ' }))
    expect(rows[0].value).toBe('—')
  })

  it('user 为 null/undefined → 各行占位符', () => {
    for (const rows of [toProfileRows(null), toProfileRows(undefined)]) {
      expect(rows).toHaveLength(4)
      expect(rows.every((r) => r.value === '—')).toBe(true)
    }
  })
})
