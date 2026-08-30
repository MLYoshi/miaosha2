import type { User } from '../api/types'
import { formatDateTime } from './goods'

/** 个人中心展示行：label + 已格式化/兜底后的 value */
export interface ProfileRow {
  label: string
  value: string
}

/**
 * 将后端 User 组装为个人中心展示行（issue 07）：
 * - 只取安全字段（昵称/注册时间/最后登录时间/登录次数），不触碰 password/salt
 * - 空值统一以 '—' 占位，时间走 formatDateTime（兼容空格分隔的 ISO 串）
 */
export function toProfileRows(user: User | null | undefined): ProfileRow[] {
  const nickname = (user?.nickname ?? '').trim()
  return [
    { label: '昵称', value: nickname || '—' },
    { label: '注册时间', value: formatDateTime(user?.registerDate) },
    { label: '最后登录时间', value: formatDateTime(user?.lastLoginDate) },
    { label: '登录次数', value: user?.loginCount == null ? '—' : String(user.loginCount) },
  ]
}
