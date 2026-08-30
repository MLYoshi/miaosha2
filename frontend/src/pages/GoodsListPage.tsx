import { useQuery } from '@tanstack/react-query'
import { Button, Card, Col, Empty, Image, Result, Row, Skeleton, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { listGoods } from '../api/goods'
import type { GoodsVo } from '../api/types'
import placeholderImg from '../assets/goods-placeholder.svg'
import { formatPrice, resolveGoodsImg } from '../utils/goods'

/**
 * 商品列表页（登录后首页）：
 * - GET /goods/list 渲染商品卡片网格，点击卡片进入详情
 * - 图片：相对路径拼 VITE_GOODS_IMG_BASE 前缀；空值/加载失败兜底本地占位图
 * - 价格：秒杀价高亮 + 原价划线对比
 * - 库存：展示秒杀剩余库存（stockCount，预扣口径）
 * - 加载中（骨架屏）/ 空（Empty）/ 失败（Result + 重试）三态齐全
 */

const { Paragraph, Text, Title } = Typography

const IMG_BASE = import.meta.env.VITE_GOODS_IMG_BASE ?? ''

/** 加载中：与商品网格同构的骨架屏，避免布局跳动 */
function ListSkeleton() {
  return (
    <Row gutter={[16, 16]}>
      {Array.from({ length: 8 }, (_, i) => (
        <Col key={i} xs={24} sm={12} md={8} lg={6}>
          <Card>
            <Skeleton.Image active style={{ width: '100%', height: 200 }} />
            <Skeleton active paragraph={{ rows: 2 }} style={{ marginTop: 16 }} />
          </Card>
        </Col>
      ))}
    </Row>
  )
}

function GoodsCard({ goods }: { goods: GoodsVo }) {
  const navigate = useNavigate()
  const imgSrc = resolveGoodsImg(goods.goodsImg, IMG_BASE) || placeholderImg

  return (
    <Card
      hoverable
      onClick={() => navigate(`/goods/${goods.id}`)}
      cover={
        <Image
          src={imgSrc}
          fallback={placeholderImg}
          alt={goods.goodsName}
          height={200}
          style={{ objectFit: 'cover', padding: 8 }}
          preview={false}
        />
      }
    >
      <Card.Meta
        title={
          <Text strong ellipsis={{ tooltip: goods.goodsName }} style={{ width: '100%' }}>
            {goods.goodsName}
          </Text>
        }
        description={
          <Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ marginBottom: 0, minHeight: 44 }}>
            {goods.goodsTitle}
          </Paragraph>
        }
      />
      <div style={{ marginTop: 12, display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <Text type="danger" strong style={{ fontSize: 20 }}>
          {formatPrice(goods.miaoshaPrice)}
        </Text>
        <Text delete type="secondary">
          {formatPrice(goods.goodsPrice)}
        </Text>
      </div>
      <div style={{ marginTop: 4 }}>
        <Text type={goods.stockCount > 0 ? 'secondary' : 'danger'}>
          秒杀库存：{goods.stockCount > 0 ? `${goods.stockCount} 件` : '已抢完'}
        </Text>
      </div>
    </Card>
  )
}

export default function GoodsListPage() {
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['goods', 'list'],
    queryFn: listGoods,
  })

  if (isPending) {
    return (
      <Card>
        <Title level={4}>商品列表</Title>
        <ListSkeleton />
      </Card>
    )
  }

  if (isError) {
    return (
      <Card>
        <Result
          status="warning"
          title="商品列表加载失败"
          subTitle={error instanceof Error ? error.message : '请稍后重试'}
          extra={
            <Button type="primary" onClick={() => refetch()}>
              重试
            </Button>
          }
        />
      </Card>
    )
  }

  if (!data || data.length === 0) {
    return (
      <Card>
        <Title level={4}>商品列表</Title>
        <Empty description="暂无商品" />
      </Card>
    )
  }

  return (
    <Card>
      <Title level={4}>商品列表</Title>
      <Row gutter={[16, 16]}>
        {data.map((goods) => (
          <Col key={goods.id} xs={24} sm={12} md={8} lg={6}>
            <GoodsCard goods={goods} />
          </Col>
        ))}
      </Row>
    </Card>
  )
}
