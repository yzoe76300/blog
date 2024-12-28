--1. 参数列表
--1.1 优惠券id
local voucherId = ARGV[1]
--1.2 用户id
local userId = ARGV[2]
--1.3 订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1 库存key
local stockKey = "voucher:stock:".. voucherId
-- 2.2 订单key
local orderKey = "voucher:order:".. voucherId

-- 3.脚本业务
-- 3.1 校验库存
if (tonumber(redis.call('GET', stockKey)) <= 0) then
    return 1 -- 库存不足
end
-- 3.2 校验订单(防止重复下单)
-- 存在返回1  不存在返回0,不需要tomember
if (redis.call('SISMEMBER', orderKey, userId) == 1) then
    return 2 -- 重复下单
end
-- 3.3 扣减库存
redis.call('INCREBY', stockKey, -1)
redis.call('SADD', orderKey, userId)
-- 发送消息到队列
redis.call('XADD', 'stream.orders','*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0-- 成功
