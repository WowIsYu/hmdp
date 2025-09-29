package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.stringRedisTemplate = redisTemplate;
    }

    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程的标识
        long threadId = Thread.currentThread().getId();

        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.MINUTES);
        // 自动拆箱避免空指针
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        // 释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
