import { Button, Card, InputNumber, Typography } from 'antd'
import { useState } from 'react'
import { usePreheat } from '../hooks/usePreheat'

/**
 * 管理员预热工具页（issue 07）：
 * 联调定位——输入 goodsId 调 POST /admin/preheat?goodsId=，
 * 将 DB 库存写入 Redis（带 TTL），预热完成后该商品可正常抢购。
 * 后端无角色体系，仅需普通 JWT；商品不存在等业务失败以 toast 透出后端文案，不白屏。
 */

const { Paragraph, Title, Text } = Typography

export default function PreheatPage() {
  const [goodsId, setGoodsId] = useState<number | null>(null)
  const { submitting, submit } = usePreheat()

  return (
    <Card style={{ maxWidth: 560 }}>
      <Title level={4}>预热库存（联调工具）</Title>
      <Paragraph type="secondary">
        将指定商品的秒杀库存从 DB 预热到 Redis（带 TTL）。预热完成后，该商品在秒杀窗口内可正常抢购。
      </Paragraph>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 24 }}>
        <InputNumber
          min={1}
          precision={0}
          placeholder="商品 ID（goodsId）"
          value={goodsId}
          onChange={(v) => setGoodsId(typeof v === 'number' ? v : null)}
          style={{ width: 200 }}
        />
        <Button
          type="primary"
          loading={submitting}
          disabled={goodsId == null}
          onClick={() => void submit(goodsId as number)}
        >
          预热库存
        </Button>
      </div>

      <Paragraph type="secondary" style={{ marginTop: 24 }}>
        提示：商品不存在时后端返回 <Text code>500104</Text>，页面将以 toast 提示错误信息。
      </Paragraph>
    </Card>
  )
}
