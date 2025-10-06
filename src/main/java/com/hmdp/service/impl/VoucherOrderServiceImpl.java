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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列
    @PostConstruct // 初始化完成后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    // 线程任务
    private class VoucherOrderHandler implements Runnable {
    String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // 4 ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 如果获取失败，说明pending-list没有消息
                        break;
                    }

                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // 4 ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }


    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 线程任务
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();

        if (!isLock) {
            // 获取锁失败,返回失败
            log.info("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            // 因为是子线程，并不能够获取代理对象，所以在调用之前获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();

        long orderId = redisIdWorker.nextId("order");

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        // 在脚本中完成了逻辑，所以不需要判断结果
//        // 2.判断结果是否为0
        int i = result.intValue();
        if (i != 0) {
            // 2.1 不为零，代表没有购买资格
            return Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }
//
//        // 2.2 为0， 有购买资格， 把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.3 生成订单id
//        voucherOrder.setId(orderId);
//        // 2.4 用户Id
//        voucherOrder.setUserId(userId);
//        // 2.5 优惠券Id
//        voucherOrder.setVoucherId(voucherId);
//        // 2.6 放入阻塞队列中
//        orderTasks.add(voucherOrder);

        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.返回订单id
        return Result.ok(orderId);
    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//
//        // 3. 判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//
//        // 4. 判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // userId.toString().intern(): 创建一个字符串常量池对象,保证锁对象唯一
//
//        // 创建锁对象
/// /        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            // 获取锁失败,返回失败
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5. 一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1 创建锁对象
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        // 5.2 判断是否存在
        if (count > 0) {
            // 存在，返回错误信息
            log.info("用户已经购买过一次");
            return ;
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)        // 乐观锁
                .update();
        if (!success) {
            log.info("库存不足");
            return ;
        }
        // 6. 创建订单
        save(voucherOrder);
    }
}
