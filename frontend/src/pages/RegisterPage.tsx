import { Link } from 'react-router-dom';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Button } from '@/components/ui/button';

/** 注册页：任务 3 实现注册表单 */
export default function RegisterPage() {
  return (
    <div className="mx-auto max-w-md py-16">
      <Card>
        <CardHeader className="text-center">
          <CardTitle>注册账号</CardTitle>
          <CardDescription>注册成功后自动登录</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4 text-center text-sm text-muted-foreground">
          <p>注册表单将在任务 3 实现（手机号 + 密码）。</p>
          <Button variant="link" asChild>
            <Link to="/login">已有账号？去登录</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
