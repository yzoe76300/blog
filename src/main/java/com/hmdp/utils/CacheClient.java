package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R  queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                           Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1,从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2,缓存中没有，从数据库查询 存在则直接返回
        if (StrUtil.isNotBlank(json)){
            // 存在，直接返回(把得到的stirng类型转换成type类型返还)
            return JSONUtil.toBean(json, type);
        }
        // 记住在这里专门把特殊情况列出来防止空指针放缓存穿透无效
        // 数据库中有对应的key，但是值为空
        if (json != null){
            // 存在，但是是空值
            // 返回空值
            return null;
        }
        // 3,数据库查询
        // 实现缓存重建,互斥锁操作
        R r = dbFallback.apply(id);
        // 不存在，返回错误
        if (r == null){
            // 获取失败，休眠并重试(递归操作）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在
        this.set(key, r, time, unit);        // 成功，释放锁
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                        Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = CACHE_SHOP_KEY + id;
        // 1,从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2,缓存中没有，从数据库查询 存在则直接返回
        // 逻辑过期如果缓存没有则直接使用现有数据，每次只单独建一个线程来查询，所以不会有穿透现象（只有一个穿透）
        if (StrUtil.isBlank(json)){
            // 不存在，直接返回null, 说明数据库里面没有或者不是热点key
            return null;
        }
        // 3, 存在，查询
        // 3.1 命中，把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
        LocalDateTime expireTime= redisData.getExpireTime();
        // 3.2 判断是否过期
        // 3.3 未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 3.4 过期，更新缓存
        // 3.4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){
            // 再次检查是否过期
            if (expireTime.isAfter(LocalDateTime.now())){
                return r;
            }
            // 成功，开启新线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }

    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
