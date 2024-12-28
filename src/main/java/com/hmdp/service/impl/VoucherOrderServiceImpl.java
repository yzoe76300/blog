package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 指定脚本返回类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 存在消息队列中让线程根据自己的能力去处理订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // spring自带一旦初始化完毕就执行VoucherOrderHandler
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    // 1. 从消息队列中获取订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        continue;
                    }

                    // 3.
                    // 3.1 从消息队列中获取消息并封装成订单对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.2 处理订单
                    handleVoucherOrder(voucherOrder);
                    // 4. ACK确认消息已处理成功
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        HandlePendingList();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }
        }

        private void HandlePendingList() throws InterruptedException {
            while(true){
                try {
                    // 1. 从消息队列中获取订单
                    // 为什么不需要设置block呢？因为这里出现问题了才会到这里，没有就直接break回到正常程序，有就反复执行直到解决
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        break;
                    }

                    // 3.
                    // 3.1 从消息队列中获取消息并封装成订单对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.2 处理订单
                    handleVoucherOrder(voucherOrder);
                    // 4. ACK确认消息已处理成功
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("PendingList处理订单异常", e);
                    // 这里是异常休眠，与消息队列的无消息休眠不同
                    Thread.sleep(50);
                }

            }
        }

    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 因为是多线程的操作模式所以不能从UserHolder里面取用户id了
        // 获取用户
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("clock:order:" + userId);
        boolean isLock = lock.tryLock();
        // 是根据userID来加锁的，所以出现抢不到锁的情况肯定是因为集群同时用一个user来抢
        if (!isLock){
            log.error("抢购失败,请稍后再试");
            return;
        }
        try{
            // 由于spring事务管理,所以这里不是代理对象Transactional会失效
            // 调用proxy获取代理对象(事务createVoucherOrder)
            proxy.createVoucherOrder(voucherOrder);
        }
        finally {
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
//        进行抢单操作
        Long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
//        1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
//        2. 判断是否为 0
        int r = result.intValue();
        if (r != 0){
            return Result.fail(r == 1? "库存不足" : "不能重复下单");
        }
        // 生成UUID并在订单表中对应值 + 1
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
        // 这里redis的操作就完全结束了接下来由Handler线程异步进行下单操作
    }



    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getUserId()).count();
            if (count > 0) {
                log.error("用户:{}已经领取过该优惠券", userId);
                return;
            }
            // 扣减库存
            // update seckill_voucher set stock = stock - 1 where voucher_id = #{voucherId}
            boolean result = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getUserId())
                    .gt("stock", 0).update();

            if (!result) {
                log.error("库存不足");
                return;
            }

            // 返回id
            save(voucherOrder);
    }
}
