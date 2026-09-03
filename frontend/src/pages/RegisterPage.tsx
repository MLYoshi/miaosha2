import { useEffect, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { CircleCheckBig, Loader2, ShoppingCart, UserPlus } from 'lucide-react';

import { register } from '@/api/user';
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

/** 注册页：手机号 + 密码 + 确认密码，成功后自动登录并进入秒杀会场。 */

const MOBILE_PATTERN = /^1[3-9]\d{9}$/;

interface FormErrors {
  mobile?: string;
  password?: string;
  confirmPassword?: string;
}

function validate(mobile: string, password: string, confirmPassword: string): FormErrors {
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
  if (!confirmPassword) {
    errors.confirmPassword = '请再次输入密码';
  } else if (confirmPassword !== password) {
    errors.confirmPassword = '两次输入的密码不一致';
  }
  return errors;
}

export default function RegisterPage() {
  const navigate = useNavigate();
  const { loggedIn } = useAuth();

  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (loggedIn) navigate('/goods', { replace: true });
  }, [loggedIn, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const nextErrors = validate(mobile, password, confirmPassword);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    setSubmitting(true);
    try {
      // 注册成功返回 token 并自动登录（api 层已持久化）
      await register({ mobile: mobile.trim(), password });
      navigate('/goods', { replace: true });
    } catch {
      // 业务错误（如 500503 手机号已注册）已由请求拦截器统一 toast
    } finally {
      setSubmitting(false);
    }
  };

  if (loggedIn) return <Navigate to="/goods" replace />;

  return (
    <div className="mx-auto flex max-w-md flex-col items-center py-10 md:py-16">
      <Card className="w-full border-white/60 shadow-xl shadow-orange-100/50">
        <CardHeader className="space-y-3 text-center">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-orange-500 to-red-500 text-white shadow-lg shadow-orange-200">
            <ShoppingCart className="h-7 w-7" />
          </div>
          <CardTitle className="bg-gradient-to-r from-orange-500 to-red-500 bg-clip-text text-2xl font-bold text-transparent">
            注册账号
          </CardTitle>
          <CardDescription>注册成功后自动登录</CardDescription>
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
                autoComplete="new-password"
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  if (errors.password) setErrors((prev) => ({ ...prev, password: undefined }));
                }}
                aria-invalid={Boolean(errors.password)}
              />
              {errors.password && <p className="text-xs text-destructive">{errors.password}</p>}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="confirmPassword" className="text-sm font-medium">
                确认密码
              </label>
              <Input
                id="confirmPassword"
                name="confirmPassword"
                type="password"
                maxLength={32}
                placeholder="请再次输入密码"
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => {
                  setConfirmPassword(e.target.value);
                  if (errors.confirmPassword) {
                    setErrors((prev) => ({ ...prev, confirmPassword: undefined }));
                  }
                }}
                aria-invalid={Boolean(errors.confirmPassword)}
              />
              {errors.confirmPassword && (
                <p className="text-xs text-destructive">{errors.confirmPassword}</p>
              )}
            </div>

            <Button
              type="submit"
              disabled={submitting}
              className="w-full bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-md shadow-orange-200 transition-transform hover:-translate-y-0.5 hover:shadow-lg disabled:translate-y-0"
            >
              {submitting ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  注册中…
                </>
              ) : (
                <>
                  <UserPlus className="h-4 w-4" />
                  注册并登录
                </>
              )}
            </Button>

            <p className="flex items-center justify-center gap-1 text-xs text-muted-foreground">
              <CircleCheckBig className="h-3.5 w-3.5" />
              注册即代表同意活动规则
            </p>
          </form>
        </CardContent>

        <CardFooter className="justify-center text-sm text-muted-foreground">
          已有账号？
          <Button variant="link" size="sm" asChild>
            <Link to="/login">直接登录</Link>
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}
