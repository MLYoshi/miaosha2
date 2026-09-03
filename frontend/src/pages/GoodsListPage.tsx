import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** 秒杀会场列表：任务 4 实现商品卡片流 */
export default function GoodsListPage() {
  return (
    <div className="space-y-6">
      <div className="rounded-xl bg-gradient-to-r from-orange-500 to-red-500 px-6 py-8 text-white shadow-lg">
        <h1 className="text-2xl font-bold">限时秒杀会场</h1>
        <p className="mt-1 text-sm opacity-90">好货低价，先到先得</p>
      </div>
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">商品列表</CardTitle>
          <CardDescription>商品卡片流将在任务 4 实现。</CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          数据来源：GET /goods/list（经 Vite proxy → gateway:8080）。
        </CardContent>
      </Card>
    </div>
  );
}
