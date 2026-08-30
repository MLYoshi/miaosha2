/**
 * 商品展示纯函数：图片 URL 拼接与价格格式化。
 *
 * 后端 goods_img 存的是相对路径（如 /img/iphonex.png），
 * 实际资源部署在独立图床/静态服务上，需拼 VITE_GOODS_IMG_BASE 前缀。
 */

/** 判定无需拼接前缀的完整 URL（http(s) / 协议相对 / data URI） */
const ABSOLUTE_URL_RE = /^(https?:)?\/\//i
const DATA_URI_RE = /^data:/i

/**
 * 解析商品图片地址：
 * - 空值/空白 → 返回 ''（调用方据此直接使用本地占位图）
 * - 完整 URL（http/https/协议相对/data URI）→ 原样返回
 * - 相对路径 → 拼接 base 前缀（base 尾部与路径前导的重复斜杠会被归一）
 */
export function resolveGoodsImg(goodsImg: string | null | undefined, base?: string): string {
  const path = (goodsImg ?? '').trim()
  if (!path) return ''
  if (ABSOLUTE_URL_RE.test(path) || DATA_URI_RE.test(path)) return path

  const prefix = (base ?? '').trim().replace(/\/+$/, '')
  const suffix = path.startsWith('/') ? path : `/${path}`
  return prefix ? `${prefix}${suffix}` : suffix
}

/** 价格格式化：两位小数人民币；空值/非法值返回占位符 */
export function formatPrice(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  return `¥${value.toFixed(2)}`
}

/**
 * 后端时间串格式化（yyyy-MM-dd HH:mm:ss）：
 * - 后端 LocalDateTime 序列化为空格分隔，Safari 等环境不接受，统一替换为 'T' 再解析
 * - 解析失败时原样返回，空值返回占位符
 */
export function formatDateTime(value: string | null | undefined): string {
  const raw = (value ?? '').trim()
  if (!raw) return '—'

  const date = new Date(raw.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return raw

  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}
