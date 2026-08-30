import { Layout, Menu, Space, Button } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

const { Header, Content, Footer } = Layout

/**
 * 带顶部导航的页面壳。
 * 未登录拦截由外层 RequireAuth 守卫统一处理（携带 redirect 回跳）。
 */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { signOut } = useAuth()

  const selectedKey = location.pathname.startsWith('/admin/preheat')
    ? 'admin/preheat'
    : location.pathname === '/profile'
      ? 'profile'
      : 'goods'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center' }}>
        <div style={{ color: '#fff', fontSize: 18, fontWeight: 600, marginRight: 32 }}>秒杀商城</div>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key === 'goods' ? '/' : `/${key}`)}
          items={[
            { key: 'goods', label: '商品列表' },
            { key: 'profile', label: '个人信息' },
            { key: 'admin/preheat', label: '预热工具' },
          ]}
          style={{ flex: 1, minWidth: 0 }}
        />
        <Space>
          <Button
            type="text"
            style={{ color: 'rgba(255,255,255,0.85)' }}
            onClick={() => {
              signOut()
              navigate('/login', { replace: true })
            }}
          >
            退出登录
          </Button>
        </Space>
      </Header>
      <Content style={{ padding: 24 }}>
        <Outlet />
      </Content>
      <Footer style={{ textAlign: 'center' }}>Seckill Demo ©2026</Footer>
    </Layout>
  )
}
