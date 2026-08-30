import { Route, Routes } from 'react-router-dom'
import RequireAuth from './auth/RequireAuth'
import AppLayout from './layouts/AppLayout'
import GoodsListPage from './pages/GoodsListPage'
import GoodsDetailPage from './pages/GoodsDetailPage'
import LoginPage from './pages/LoginPage'
import NotFoundPage from './pages/NotFoundPage'
import PreheatPage from './pages/PreheatPage'
import ProfilePage from './pages/ProfilePage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* 受控页面统一挂在守卫之下：未登录 → /login?redirect=原路径 */}
      <Route element={<RequireAuth />}>
        <Route path="/" element={<AppLayout />}>
          <Route index element={<GoodsListPage />} />
          <Route path="goods/:goodsId" element={<GoodsDetailPage />} />
          <Route path="profile" element={<ProfilePage />} />
          {/* 预热工具页（联调用）：后端无角色体系，仅需登录 */}
          <Route path="admin/preheat" element={<PreheatPage />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
