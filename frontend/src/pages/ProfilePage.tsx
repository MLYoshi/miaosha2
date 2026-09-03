import { useCallback, useEffect, useState } from 'react';
import { CircleUserRound, Clock, LogIn, RefreshCw, Star } from 'lucide-react';

import { getProfile } from '@/api/user';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { useAuth } from '@/hooks/useAuth';
import { formatDateTime } from '@/lib/utils';
import type { User } from '@/types/api';

/** 个人中心：展示昵称、头像、注册时间、最近登录、登录次数（GET /user/profile）。 */

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; user: User }
  | { status: 'error' };

export default function ProfilePage() {
  const { loggedIn } = useAuth();
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  const load = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const user = await getProfile();
      setState({ status: 'ready', user });
    } catch {
      // 业务错误（含会话失效跳登录）已由请求拦截器统一处理
      setState({ status: 'error' });
    }
  }, []);

  useEffect(() => {
    if (loggedIn) void load();
  }, [loggedIn, load]);

  if (state.status === 'loading') {
    return (
      <div className="mx-auto max-w-md space-y-4 py-8">
        <Card>
          <CardContent className="flex items-center gap-4 pt-6">
            <div className="h-16 w-16 animate-pulse rounded-full bg-muted" />
            <div className="flex-1 space-y-2">
              <div className="h-5 w-28 animate-pulse rounded bg-muted" />
              <div className="h-4 w-20 animate-pulse rounded bg-muted" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="space-y-3 pt-6">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-9 animate-pulse rounded bg-muted" />
            ))}
          </CardContent>
        </Card>
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <div className="mx-auto max-w-md py-8">
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-8 text-center">
            <p className="text-sm text-muted-foreground">加载个人信息失败，请稍后重试</p>
            <Button size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重新加载
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  const { user } = state;
  const infoItems = [
    { icon: Star, label: '用户 ID', value: `#${user.id}` },
    { icon: Clock, label: '注册时间', value: formatDateTime(user.registerDate) },
    { icon: LogIn, label: '最近登录', value: formatDateTime(user.lastLoginDate) },
    {
      icon: Star,
      label: '登录次数',
      value: user.loginCount == null ? '—' : `${user.loginCount} 次`,
    },
  ];

  return (
    <div className="mx-auto max-w-md space-y-4 py-8">
      <Card className="overflow-hidden border-white/60 shadow-lg shadow-orange-100/50">
        <div className="h-24 bg-gradient-to-r from-orange-500 to-red-500" />
        <CardContent className="-mt-10 pb-6">
          <div className="flex items-end gap-4">
            {user.head ? (
              <img
                src={user.head}
                alt={user.nickname || '头像'}
                className="h-20 w-20 rounded-full border-4 border-white object-cover shadow-md"
              />
            ) : (
              <div className="flex h-20 w-20 items-center justify-center rounded-full border-4 border-white bg-gradient-to-br from-orange-100 to-red-100 text-orange-500 shadow-md">
                <CircleUserRound className="h-10 w-10" />
              </div>
            )}
            <div className="pb-1">
              <div className="flex items-center gap-2">
                <h1 className="text-lg font-bold">{user.nickname || `用户 ${user.id}`}</h1>
                <Badge className="bg-gradient-to-r from-orange-500 to-red-500 text-white">
                  秒杀会员
                </Badge>
              </div>
              <p className="text-sm text-muted-foreground">欢迎来到秒杀商城</p>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card className="border-white/60 shadow-lg shadow-orange-100/50">
        <CardHeader>
          <CardTitle className="text-base">账户信息</CardTitle>
          <CardDescription>与秒杀活动相关的账户资料</CardDescription>
        </CardHeader>
        <CardContent className="divide-y">
          {infoItems.map(({ icon: Icon, label, value }) => (
            <div key={label} className="flex items-center justify-between py-3 text-sm">
              <span className="flex items-center gap-2 text-muted-foreground">
                <Icon className="h-4 w-4" />
                {label}
              </span>
              <span className="font-medium">{value}</span>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
