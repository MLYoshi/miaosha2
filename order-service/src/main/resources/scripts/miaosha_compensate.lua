-- 秒杀补偿：DB 下单失败后回补库存（复制自 miaosha-service，双端语义必须一致）
-- KEYS[1] = miaosha:stock:{goodsId}
-- KEYS[2] = miaosha:user:{goodsId}:{userId}
-- KEYS[3] = miaosha:result:{goodsId}:{userId}
-- ARGV[1] = requestId
-- ARGV[2] = ttlSeconds
-- 返回: 1=补偿成功 0=requestId 不匹配，跳过（避免误操作其它请求）

local current = redis.call('GET', KEYS[2])
if current == ARGV[1] then
  redis.call('INCR', KEYS[1])
  redis.call('DEL', KEYS[2])
  redis.call('SET', KEYS[3], 'FAILED', 'EX', ARGV[2])
  return 1
end
return 0
