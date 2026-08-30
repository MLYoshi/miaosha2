import { describe, expect, it } from 'vitest'
import { formatDateTime, formatPrice, resolveGoodsImg } from './goods'

/**
 * 商品展示纯函数接缝测试（对应 issue 04 验收项）：
 * - 图片路径需拼 VITE_GOODS_IMG_BASE 前缀；空值/绝对 URL 场景各自处理
 * - 价格统一格式化为两位小数
 */

describe('resolveGoodsImg', () => {
  it('空值（null/undefined/空白串）返回空串，表示走占位图', () => {
    expect(resolveGoodsImg(null, 'http://cdn.example.com')).toBe('')
    expect(resolveGoodsImg(undefined, 'http://cdn.example.com')).toBe('')
    expect(resolveGoodsImg('   ', 'http://cdn.example.com')).toBe('')
    expect(resolveGoodsImg('', '')).toBe('')
  })

  it('相对路径拼接 base 前缀：base 尾部多余斜杠被归一', () => {
    expect(resolveGoodsImg('/img/iphonex.png', 'http://localhost:8080')).toBe(
      'http://localhost:8080/img/iphonex.png',
    )
    expect(resolveGoodsImg('/img/iphonex.png', 'http://localhost:8080/')).toBe(
      'http://localhost:8080/img/iphonex.png',
    )
    expect(resolveGoodsImg('/img/mi6.png', 'http://localhost:8080//')).toBe(
      'http://localhost:8080/img/mi6.png',
    )
  })

  it('相对路径不带前导斜杠时补斜杠', () => {
    expect(resolveGoodsImg('img/iphone8.png', 'http://localhost:8080')).toBe(
      'http://localhost:8080/img/iphone8.png',
    )
  })

  it('base 为空时原样返回相对路径', () => {
    expect(resolveGoodsImg('/img/meta10.png', '')).toBe('/img/meta10.png')
    expect(resolveGoodsImg('/img/meta10.png', undefined)).toBe('/img/meta10.png')
  })

  it('已是完整 http(s)/协议相对/data URL 时不重复拼接 base', () => {
    expect(resolveGoodsImg('http://cdn.example.com/img/a.png', 'http://localhost:8080')).toBe(
      'http://cdn.example.com/img/a.png',
    )
    expect(resolveGoodsImg('https://cdn.example.com/img/a.png', 'http://localhost:8080')).toBe(
      'https://cdn.example.com/img/a.png',
    )
    expect(resolveGoodsImg('//cdn.example.com/img/a.png', 'http://localhost:8080')).toBe(
      '//cdn.example.com/img/a.png',
    )
    expect(resolveGoodsImg('data:image/png;base64,xxxx', 'http://localhost:8080')).toBe(
      'data:image/png;base64,xxxx',
    )
  })

  it('路径首尾空白被裁剪', () => {
    expect(resolveGoodsImg('  /img/iphonex.png  ', 'http://localhost:8080')).toBe(
      'http://localhost:8080/img/iphonex.png',
    )
  })
})

describe('formatPrice', () => {
  it('格式化为人民币两位小数', () => {
    expect(formatPrice(8765)).toBe('¥8765.00')
    expect(formatPrice(0.01)).toBe('¥0.01')
    expect(formatPrice(3212.5)).toBe('¥3212.50')
  })

  it('空值/非法值返回占位符', () => {
    expect(formatPrice(null)).toBe('—')
    expect(formatPrice(undefined)).toBe('—')
    expect(formatPrice(Number.NaN)).toBe('—')
  })
})

describe('formatDateTime', () => {
  it('空值返回占位符', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime(undefined)).toBe('—')
    expect(formatDateTime('')).toBe('—')
    expect(formatDateTime('   ')).toBe('—')
  })

  it('空格分隔的 LocalDateTime 串（yyyy-MM-dd HH:mm:ss）正常解析', () => {
    // 无时区信息，按本地时间解析后再本地格式化，输出应与输入一致
    expect(formatDateTime('2026-08-24 10:00:00')).toBe('2026-08-24 10:00:00')
  })

  it('ISO T 分隔串同样支持，输出统一为空格分隔', () => {
    expect(formatDateTime('2026-01-02T03:04:05')).toBe('2026-01-02 03:04:05')
  })

  it('无法解析的字符串原样返回', () => {
    expect(formatDateTime('not-a-date')).toBe('not-a-date')
  })
})
