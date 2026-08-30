import { useQuery } from '@tanstack/react-query'
import { Button, Card, Result, Skeleton, Typography } from 'antd'
import { getProfile } from '../api/auth'
import { toProfileRows } from '../utils/profile'

/**
 * 个人中心（issue 07）：
 * - GET /user/profile 展示昵称、注册时间、最后登录时间、登录次数
 * - password/salt 由后端置空，页面不展示敏感字段
 * - 时间/次数空值由 toProfileRows 统一以 '—' 兜底
 */

const { Title } = Typography

/** 加载中：与 Descriptions 同构的骨架屏，避免布局跳动 */
function ProfileSkeleton() {
  return (
    <Card>
      <Title level={4}>个人信息</Title>
      <Skeleton active paragraph={{ rows: 4 }} />
    </Card>
  )
}

export default function ProfilePage() {
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['user', 'profile'],
    queryFn: getProfile,
  })

  if (isPending) {
    return <ProfileSkeleton />
  }

  if (isError) {
    return (
      <Card>
        <Result
          status="warning"
          title="个人信息加载失败"
          subTitle={error instanceof Error ? error.message : '请稍后重试'}
          extra={
            <Button type="primary" onClick={() => refetch()}>
              重试
            </Button>
          }
        />
      </Card>
    )
  }

  const rows = toProfileRows(data)
  return (
    <Card>
      <Title level={4}>个人信息</Title>
      <dl style={{ margin: 0 }}>
        {rows.map((row) => (
          <div
            key={row.label}
            style={{
              display: 'flex',
              alignItems: 'baseline',
              gap: 24,
              padding: '12px 0',
              borderBottom: '1px solid #f0f0f0',
            }}
          >
            <dt style={{ width: 120, margin: 0, color: 'rgba(0,0,0,0.45)' }}>{row.label}</dt>
            <dd style={{ margin: 0, fontSize: 16 }}>{row.value}</dd>
          </div>
        ))}
      </dl>
    </Card>
  )
}
