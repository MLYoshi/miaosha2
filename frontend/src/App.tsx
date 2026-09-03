import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Layout } from '@/components/layout/Layout';
import { isAuthenticated } from '@/lib/auth';
import LoginPage from '@/pages/LoginPage';
import RegisterPage from '@/pages/RegisterPage';
import GoodsListPage from '@/pages/GoodsListPage';
import GoodsDetailPage from '@/pages/GoodsDetailPage';
import ProfilePage from '@/pages/ProfilePage';
import AdminPage from '@/pages/AdminPage';

/** 受保护路由：无 token 重定向 /login，登录后回跳原地址 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  if (!isAuthenticated()) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        {/* 公开页面 */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        {/* 受保护页面 */}
        <Route
          path="/goods"
          element={
            <RequireAuth>
              <GoodsListPage />
            </RequireAuth>
          }
        />
        <Route
          path="/goods/:id"
          element={
            <RequireAuth>
              <GoodsDetailPage />
            </RequireAuth>
          }
        />
        <Route
          path="/profile"
          element={
            <RequireAuth>
              <ProfilePage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin"
          element={
            <RequireAuth>
              <AdminPage />
            </RequireAuth>
          }
        />

        <Route path="/" element={<Navigate to="/goods" replace />} />
        <Route path="*" element={<Navigate to="/goods" replace />} />
      </Route>
    </Routes>
  );
}
