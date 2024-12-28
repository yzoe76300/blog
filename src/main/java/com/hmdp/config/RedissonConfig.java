package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedissonConfig {
        @Bean
        @Primary
        public RedissonClient redissonClient1() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://192.168.49.135:6379").setPassword("123321");
            return Redisson.create(config);
        }

//        @Bean
//        public RedissonClient redissonClient2() {
//            Config config = new Config();
//            config.useSingleServer().setAddress("redis://192.168.49.135:6380").setPassword("123321");
//            return Redisson.create(config);
//        }
//
//        @Bean
//        public RedissonClient redissonClient3() {
//            Config config = new Config();
//            config.useSingleServer().setAddress("redis://192.168.49.135:6381").setPassword("123321");
//            return Redisson.create(config);
//        }
}
