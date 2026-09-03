import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, LogIn, ShoppingCart } from 'lucide-react';

import { login } from '@/api/user';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { useAuth } from '@/hooks/useAuth';

/** 登录页：手机号 + 密码表单，前端校验与后端 LoginVo 规则对齐。 */

const MOBILE_PATTERN = /^1[3-9]\d{9}$/;

interface FormErrors {
  mobile?: string;
  password?: string;
}

function validate(mobile: string, password: string): FormErrors {
  const errors: FormErrors = {};
  if (!mobile.trim()) {
    errors.mobile = '手机号不能为空';
  } else if (!MOBILE_PATTERN.test(mobile.trim())) {
    errors.mobile = '手机号格式错误';
  }
  if (!password) {
    errors.password = '密码不能为空';
  } else if (password.length < 6 || password.length > 32) {
    errors.password = '密码长度必须在6-32位之间';
  }
  return errors;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const { loggedIn } = useAuth();

  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitting, setSubmitting] = useState(false);

  // 登录页错误提示优先带 redirect 参数；路由守卫会带 state.from
  const from =
    searchParams.get('redirect') ?? (location.state as { from?: string } | null)?.from ?? '/goods';

  useEffect(() => {
    if (loggedIn) navigate(from, { replace: true });
  }, [loggedIn, from, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const nextErrors = validate(mobile, password);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    setSubmitting(true);
    try {
      // 成功后 api/user.login 已持久化 token，useAuth 响应式更新
      await login({ mobile: mobile.trim(), password });
      navigate(from, { replace: true });
    } catch {
      // 业务错误已由请求拦截器统一 toast
    } finally {
      setSubmitting(false);
    }
  };

  // 已带有效 token 的访问（如手动输入 /login）直接送走
  if (loggedIn) return <Navigate to={from} replace />;

  return (
    <div className="mx-auto flex max-w-md flex-col items-center py-10 md:py-16">
      <Card className="w-full border-white/60 shadow-xl shadow-orange-100/50">
        <CardHeader className="space-y-3 text-center">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-orange-500 to-red-500 text-white shadow-lg shadow-orange-200">
            <ShoppingCart className="h-7 w-7" />
          </div>
          <CardTitle className="bg-gradient-to-r from-orange-500 to-red-500 bg-clip-text text-2xl font-bold text-transparent">
            欢迎回来
          </CardTitle>
          <CardDescription>登录后开启限时秒杀之旅</CardDescription>
        </CardHeader>

        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <div className="space-y-1.5">
              <label htmlFor="mobile" className="text-sm font-medium">
                手机号
              </label>
              <Input
                id="mobile"
                name="mobile"
                type="tel"
                inputMode="numeric"
                maxLength={11}
                placeholder="请输入 11 位手机号"
                autoComplete="tel"
                value={mobile}
                onChange={(e) => {
                  setMobile(e.target.value);
                  if (errors.mobile) setErrors((prev) => ({ ...prev, mobile: undefined }));
                }}
                aria-invalid={Boolean(errors.mobile)}
              />
              {errors.mobile && <p className="text-xs text-destructive">{errors.mobile}</p>}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="text-sm font-medium">
                密码
              </label>
              <Input
                id="password"
                name="password"
                type="password"
                maxLength={32}
                placeholder="6-32 位密码"
                autoComplete="current-password"
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  if (errors.password) setErrors((prev) => ({ ...prev, password: undefined }));
                }}
                aria-invalid={Boolean(errors.password)}
              />
              {errors.password && <p className="text-xs text-destructive">{errors.password}</p>}
            </div>

            <Button
              type="submit"
              disabled={submitting}
              className="w-full bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-md shadow-orange-200 transition-transform hover:-translate-y-0.5 hover:shadow-lg disabled:translate-y-0"
            >
              {submitting ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  登录中…
                </>
              ) : (
                <>
                  <LogIn className="h-4 w-4" />
                  登录
                </>
              )}
            </Button>
          </form>
        </CardContent>

        <CardFooter className="justify-center text-sm text-muted-foreground">
          还没有账号？
          <Button variant="link" size="sm" asChild>
            <Link to="/register">立即注册</Link>
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}
