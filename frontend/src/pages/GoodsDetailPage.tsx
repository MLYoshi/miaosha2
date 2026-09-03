import { useParams } from 'react-router-dom';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** 商品详情 + 抢购：任务 4 实现倒计时与结果轮询弹层 */
export default function GoodsDetailPage() {
  const { id } = useParams();
  return (
    <div className="mx-auto max-w-3xl py-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">商品详情（ID: {id}）</CardTitle>
          <CardDescription>
            倒计时、抢购按钮与结果轮询弹层将在任务 4 实现。
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          数据来源：GET /goods/detail/{id}；抢购：POST /miaosha/do_miaosha；
          轮询：GET /miaosha/result。
        </CardContent>
      </Card>
    </div>
  );
}
