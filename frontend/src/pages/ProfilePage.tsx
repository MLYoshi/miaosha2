import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** 个人中心：任务 3 实现用户信息展示 */
export default function ProfilePage() {
  return (
    <div className="mx-auto max-w-md py-8">
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">个人中心</CardTitle>
          <CardDescription>用户信息展示将在任务 3 实现。</CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          展示昵称、头像、注册时间、最近登录、登录次数（GET /user/profile）。
        </CardContent>
      </Card>
    </div>
  );
}
