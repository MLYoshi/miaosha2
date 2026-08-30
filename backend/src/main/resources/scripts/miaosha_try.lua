-- 秒杀预扣库存（原子操作）
-- KEYS[1] = miaosha:stock:{goodsId}
-- KEYS[2] = miaosha:user:{goodsId}:{userId}
-- KEYS[3] = miaosha:result:{goodsId}:{userId}
-- ARGV[1] = requestId
-- ARGV[2] = ttlSeconds
-- 返回: 0=成功 1=库存不足 2=重复下单

-- 1. 用户是否已经抢购 / 处理中
if redis.call('EXISTS', KEYS[2]) == 1 then
  return 2
end

-- 2. 库存是否大于 0
local stock = redis.call('GET', KEYS[1])
if (not stock) or tonumber(stock) <= 0 then
  return 1
end

-- 3. 预扣库存
redis.call('DECR', KEYS[1])

-- 4. 标记该用户已获得秒杀资格，记录结果为 PROCESSING
redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])
redis.call('SET', KEYS[3], 'PROCESSING', 'EX', ARGV[2])

return 0
