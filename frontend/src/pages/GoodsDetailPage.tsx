import { useQuery } from '@tanstack/react-query'
import { Button, Card, Col, Divider, Image, Modal, Result, Row, Skeleton, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/errorCode'
import { getGoodsDetail } from '../api/goods'
import { MIAOSHA_STATUS, type GoodsDetailVo } from '../api/types'
import placeholderImg from '../assets/goods-placeholder.svg'
import { useCountdown } from '../hooks/useCountdown'
import { useSeckill } from '../hooks/useSeckill'
import { formatDateTime, formatPrice, resolveGoodsImg } from '../utils/goods'
import { SECKILL_FLOW_PHASE } from '../utils/seckillFlow'
import { SECKILL_PHASE, formatCountdown, resolveSeckillPhase, type SeckillPhase } from '../utils/seckill'

/**
 * 商品详情页（issue 05 + 06）：
 * - GET /goods/detail/{goodsId}：大图、标题、详情、原价/秒杀价、库存与秒杀窗口
 * - 秒杀状态机以后端 miaoshaStatus 为权威：
 *   未开始（0）→ 倒计时按钮；进行中（1）→ 高亮抢购按钮；
 *   已结束（2）→ 禁用「已结束」
 * - 倒计时以后端 remainSeconds 驱动本地递减，归零后重拉详情校准，
 *   校准完成前保持禁用（CALIBRATING），不允许本地时钟归零直接点亮
 * - 抢购（issue 06）：点击受理 → PROCESSING 原地轮询结果；SUCCESS 弹窗展示订单号；
 *   业务失败 toast 提示；库存不足/已结束回到禁用终局；受理到终态期间按钮 loading
 * - 商品不存在（500104）：友好提示而非白屏
 */

const { Paragraph, Text, Title } = Typography

const IMG_BASE = import.meta.env.VITE_GOODS_IMG_BASE ?? ''

/** 后端业务错误码：GOODS_NOT_EXIST */
const GOODS_NOT_EXIST_CODE = 500104

/** 加载中：与大图 + 信息区同构的骨架屏，避免布局跳动 */
function DetailSkeleton() {
  return (
    <Card>
      <Title level={4}>商品详情</Title>
      <Row gutter={24}>
        <Col xs={24} md={10}>
          <Skeleton.Image active style={{ width: '100%', height: 320 }} />
        </Col>
        <Col xs={24} md={14}>
          <Skeleton active paragraph={{ rows: 5 }} />
        </Col>
      </Row>
    </Card>
  )
}

/** 秒杀按钮：按状态机形态渲染；busy 为受理/排队中 loading 防连点；finished 为禁用终局（库存不足/已结束） */
function SeckillButton({
  phase,
  remain,
  busy,
  finished,
  onSubmit,
}: {
  phase: SeckillPhase
  remain: number
  busy: boolean
  finished: boolean
  onSubmit: () => void
}) {
  if (finished) {
    return (
      <Button size="large" disabled style={{ minWidth: 200 }}>
        已抢完
      </Button>
    )
  }
  switch (phase) {
    case SECKILL_PHASE.READY:
      return (
        <Button type="primary" danger size="large" loading={busy} onClick={onSubmit} style={{ minWidth: 200 }}>
          {busy ? '抢购中…' : '立即抢购'}
        </Button>
      )
    case SECKILL_PHASE.COUNTDOWN:
      return (
        <Button type="primary" danger size="large" disabled style={{ minWidth: 200 }}>
          距开始 {formatCountdown(remain)}
        </Button>
      )
    case SECKILL_PHASE.CALIBRATING:
      return (
        <Button type="primary" danger size="large" disabled loading style={{ minWidth: 200 }}>
          正在校准状态…
        </Button>
      )
    case SECKILL_PHASE.OVER:
      return (
        <Button size="large" disabled style={{ minWidth: 200 }}>
          已结束
        </Button>
      )
  }
}

/** 商品主体：大图 + 信息区（价格/库存/窗口时间/秒杀按钮）+ 图文详情 */
function GoodsDetail({ detail, remain }: { detail: GoodsDetailVo; remain: number }) {
  const goods = detail.goods
  const phase = resolveSeckillPhase(detail.miaoshaStatus, remain)
  const imgSrc = resolveGoodsImg(goods.goodsImg, IMG_BASE) || placeholderImg

  // 抢购流转：受理 → 轮询 → 终态（issue 06）
  const seckill = useSeckill(goods.id)
  const [successOpen, setSuccessOpen] = useState(false)
  useEffect(() => {
    if (seckill.phase === SECKILL_FLOW_PHASE.SUCCESS) {
      setSuccessOpen(true)
    }
  }, [seckill.phase])

  return (
    <Card>
      <Title level={4}>{goods.goodsName}</Title>
      <Row gutter={24}>
        <Col xs={24} md={10}>
          <Image
            src={imgSrc}
            fallback={placeholderImg}
            alt={goods.goodsName}
            width="100%"
            style={{ maxHeight: 360, objectFit: 'contain' }}
          />
        </Col>
        <Col xs={24} md={14}>
          <Title level={5} style={{ marginTop: 0 }}>
            {goods.goodsTitle}
          </Title>

          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <Text type="danger" strong style={{ fontSize: 28 }}>
              {formatPrice(goods.miaoshaPrice)}
            </Text>
            <Text delete type="secondary">
              {formatPrice(goods.goodsPrice)}
            </Text>
          </div>

          <Paragraph type="secondary" style={{ marginTop: 12 }}>
            秒杀库存：{goods.stockCount > 0 ? `${goods.stockCount} 件` : '已抢完'}
          </Paragraph>
          <Paragraph type="secondary" style={{ marginBottom: 8 }}>
            秒杀时间：{formatDateTime(goods.startDate)} ~ {formatDateTime(goods.endDate)}
          </Paragraph>

          <div style={{ marginTop: 24 }}>
            <SeckillButton
              phase={phase}
              remain={remain}
              busy={seckill.isBusy}
              finished={seckill.finished}
              onSubmit={() => void seckill.submit()}
            />
          </div>
        </Col>
      </Row>

      <Divider />
      <Title level={5}>商品详情</Title>
      <Paragraph style={{ whiteSpace: 'pre-wrap' }}>
        {goods.goodsDetail?.trim() || '暂无详情'}
      </Paragraph>

      {/* 抢购成功：弹窗展示订单号，页面不跳转 */}
      <Modal
        open={successOpen}
        title="抢购成功"
        okText="知道了"
        cancelButtonProps={{ style: { display: 'none' } }}
        onOk={() => setSuccessOpen(false)}
        onCancel={() => setSuccessOpen(false)}
      >
        <Paragraph>
          恭喜您抢购成功！订单号：
          <Text strong copyable>
            {seckill.orderId}
          </Text>
        </Paragraph>
      </Modal>
    </Card>
  )
}

export default function GoodsDetailPage() {
  const { goodsId } = useParams<{ goodsId: string }>()
  const navigate = useNavigate()

  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['goods', 'detail', goodsId],
    queryFn: () => getGoodsDetail(goodsId as string),
    enabled: goodsId !== undefined,
  })

  // NOT_START：remainSeconds = 距开始；IN_PROGRESS：= 距结束（归零后同样重拉校准为已结束）
  const initialRemain =
    data && data.miaoshaStatus !== MIAOSHA_STATUS.OVER ? data.remainSeconds : 0
  // 倒计时归零 → 重拉详情，用后端权威状态校准按钮形态
  const handleCountdownExpire = useCallback(() => {
    void refetch()
  }, [refetch])
  const remain = useCountdown(initialRemain, handleCountdownExpire)

  if (isPending) {
    return <DetailSkeleton />
  }

  if (isError) {
    // 商品不存在：明确提示而非白屏
    if (error instanceof ApiError && error.code === GOODS_NOT_EXIST_CODE) {
      return (
        <Card>
          <Result
            status="404"
            title="商品不存在"
            subTitle={`商品 ${goodsId ?? ''} 不存在或已下架`}
            extra={
              <Button type="primary" onClick={() => navigate('/')}>
                返回商品列表
              </Button>
            }
          />
        </Card>
      )
    }
    return (
      <Card>
        <Result
          status="warning"
          title="商品详情加载失败"
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

  // isPending / isError 均已排除，data 必定存在
  return <GoodsDetail detail={data} remain={remain} />
}
