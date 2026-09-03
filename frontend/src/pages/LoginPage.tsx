import { Link } from 'react-router-dom';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Button } from '@/components/ui/button';

/** 登录页：任务 3 实现手机号/密码表单 */
export default function LoginPage() {
  return (
    <div className="mx-auto max-w-md py-16">
      <Card>
        <CardHeader className="text-center">
          <CardTitle className="bg-gradient-to-r from-orange-500 to-red-500 bg-clip-text text-transparent">
            欢迎回来
          </CardTitle>
          <CardDescription>登录后开启限时秒杀之旅</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4 text-center text-sm text-muted-foreground">
          <p>登录表单将在任务 3 实现（手机号 + 密码）。</p>
          <Button variant="link" asChild>
            <Link to="/register">还没有账号？去注册</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
