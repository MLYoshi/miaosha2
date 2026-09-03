import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** 管理端：任务 5 实现预热库存与重置秒杀 */
export default function AdminPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold">运营管理后台</h1>
        <p className="text-sm text-muted-foreground">管理端功能将在任务 5 实现。</p>
      </div>
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">管理操作</CardTitle>
          <CardDescription>
            预热 Redis 库存、重置秒杀时间窗与库存（/admin/* 内部接口）。
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Tab 分组「预热库存」与「重置秒杀」表单将在任务 5 实现。
        </CardContent>
      </Card>
    </div>
  );
}
