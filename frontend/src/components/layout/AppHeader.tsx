import { Link, useNavigate } from 'react-router-dom';
import { LogOut, ShoppingCart, Shield, User } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { clearToken, isAuthenticated } from '@/lib/auth';

export function AppHeader() {
  const navigate = useNavigate();
  const loggedIn = isAuthenticated();

  return (
    <header className="sticky top-0 z-50 border-b border-white/40 bg-white/70 backdrop-blur-md">
      <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4">
        <Link to="/goods" className="flex items-center gap-2 font-bold text-primary">
          <ShoppingCart className="h-5 w-5" />
          <span className="bg-gradient-to-r from-orange-500 to-red-500 bg-clip-text text-lg text-transparent">
            秒杀商城
          </span>
        </Link>

        <nav className="ml-4 hidden items-center gap-1 text-sm md:flex">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/goods">商品</Link>
          </Button>
          {loggedIn && (
            <>
              <Button variant="ghost" size="sm" asChild>
                <Link to="/profile">个人中心</Link>
              </Button>
              <Button variant="ghost" size="sm" asChild>
                <Link to="/admin">
                  <Shield className="h-4 w-4" />
                  管理端
                </Link>
              </Button>
            </>
          )}
        </nav>

        <div className="ml-auto">
          {loggedIn ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                clearToken();
                navigate('/login');
              }}
            >
              <LogOut className="h-4 w-4" />
              退出
            </Button>
          ) : (
            <Button size="sm" asChild>
              <Link to="/login">
                <User className="h-4 w-4" />
                登录
              </Link>
            </Button>
          )}
        </div>
      </div>
    </header>
  );
}
