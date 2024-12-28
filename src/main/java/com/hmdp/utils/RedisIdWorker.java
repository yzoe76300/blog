package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// 使用UUID构造订单的数据库表
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMO = 1640995200l;
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;
    // 构造方法，接受 StringRedisTemplate 作为参数并初始化
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        // 1, 时间戳
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long now = time.toEpochSecond(ZoneOffset.UTC);
        long timestamp = now - BEGIN_TIMESTAMO;

        // 2, 序列号
        // 2.1 获取当前日期，精确到天
        String date = time.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 统计交易数量
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        timestamp = timestamp << COUNT_BITS;
        long id = timestamp | count;
        return id;
    }
}
