package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock {


    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String key_prefix = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String id = ID_PREFIX + Thread.currentThread().getId();
        // Redis直接用布尔值返回给我们
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key_prefix + name, id + "",timeoutSec, TimeUnit.SECONDS);
        // 防止空指针异常
        return Boolean.TRUE.equals(flag);
    }
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 这里找的是Resource
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }
    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(key_prefix + name),
                ID_PREFIX + Thread.currentThread().getId());
//        String id = ID_PREFIX + Thread.currentThread().getId();
//        // 只有当前线程持有锁才可以释放锁
//        if (stringRedisTemplate.opsForValue().get(key_prefix + name).equals(id)) {
//            stringRedisTemplate.delete(key_prefix + name);
//        }
    }
}
