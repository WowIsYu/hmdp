package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private ExecutorService cacheRebuildExecutor;

    @PostConstruct
    public void init() {
        cacheRebuildExecutor = Executors.newFixedThreadPool(10);
    }
    
    @PreDestroy
    public void destroy() {
        if (cacheRebuildExecutor != null && !cacheRebuildExecutor.isShutdown()) {
            cacheRebuildExecutor.shutdown();
        }
    }

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            return null;
        }

        // 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 判断是否存在
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", time,  unit);
            // 不存在，返回错误
            return null;
        }

        // 如果存在，写入redis
        this.set(key, r, time, unit);

        // 返回
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 存在，直接返回
            return null;
        }
        // 4. 命中需要判断过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 5. 判断是否过期
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 5.2 已过期 需要缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 6. 缓存重建
        // 6.1 获取互斥锁
        boolean isLock = tryLock(lockKey);
        // 6.2 判断是否获取成功
        if (isLock) {
            // 6.3 成功，开启独立线程，实现缓存重建
            cacheRebuildExecutor.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4 返回过期的商铺信息
        return r;
    }

    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}