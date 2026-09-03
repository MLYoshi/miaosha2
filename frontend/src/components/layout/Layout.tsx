import { Outlet } from 'react-router-dom';
import { AppHeader } from '@/components/layout/AppHeader';

/** 页面壳：吸顶导航 + 内容区 */
export function Layout() {
  return (
    <div className="min-h-screen">
      <AppHeader />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
