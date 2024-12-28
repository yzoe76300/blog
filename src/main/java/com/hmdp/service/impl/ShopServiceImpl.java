package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.dto.Result;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class ,this::getById,
                CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1,从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2,缓存中没有，从数据库查询 存在则直接返回
        // 逻辑过期如果缓存没有则直接使用现有数据，每次只单独建一个线程来查询，所以不会有穿透现象（只有一个穿透）
        if (StrUtil.isBlank(shopJson)){
            // 不存在，直接返回null, 说明数据库里面没有或者不是热点key
            return null;
        }
        // 3, 存在，查询
        // 3.1 命中，把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject)redisData.getData(), Shop.class);
        LocalDateTime expireTime= redisData.getExpireTime();
        // 3.2 判断是否过期
        // 3.3 未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 3.4 过期，更新缓存
        // 3.4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){
            // 再次检查是否过期
            if (expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }
            // 成功，开启新线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 3600L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1,从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2,缓存中没有，从数据库查询 存在则直接返回
        if (StrUtil.isNotBlank(shopJson)){
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 记住在这里专门把特殊情况列出来防止空指针放缓存穿透无效
        // 数据库中有对应的key，但是值为空
        if (shopJson != null){
            // 存在，但是是空值
            return null;
        }
        // 3,数据库查询
        // 实现缓存重建,互斥锁操作
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            if (!tryLock(lockKey)){
                // 获取失败，休眠并重试(递归操作）
                Thread.sleep(100);
                queryWithMutex(id);
            }


            shop = this.getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            // 成功，释放锁
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
    }



    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireTime) {
        // 1. 查询店铺数据
        Shop shop = this.getById(id);
        // 封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 3， 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
