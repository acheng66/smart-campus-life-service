package com.smartcampus.utils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class CachClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CachClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 存储普通缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr( value), time, unit);
    }
    /**
     * 存储逻辑过期缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决
     * @param id
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从缓存中查询
        String jason = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(jason)) {
            //2.缓存中有，返回
            return JSONUtil.toBean(jason, type);
        }
        //2.判断命中的是否是空值
        if (jason != null) {
            return null;
        }
        //3.缓存中没有，查询数据库
        R r = dbFallback.apply(id);
        //4.数据库中查不到，返回错误,将空值写入缓存
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return  null;
        }
        //5.数据库中查到，写入缓存
        this.set(key, r, time, unit);
        //6.返回
        return r;
    }
    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue( flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                        Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从缓存中查询
        String Jason = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(Jason)) {
            //2.缓存中有，返回
            return null;
        }
        //3.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Jason, RedisData.class);
        R r = JSONUtil.toBean((JSONUtil.parseObj(redisData.getData())), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //5.缓存过期，需要缓存重建
        //5.1获取互斥锁
        String lock = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lock);
        //5.2判断是否获取成功
        if (flag) {
            //5.3再次检测redis缓存是否过期
            Jason = stringRedisTemplate.opsForValue().get(key);
            RedisData redisData2 = JSONUtil.toBean(Jason, RedisData.class);
            if(redisData2.getExpireTime().isAfter(LocalDateTime.now())){
                // 别的线程已经更新了，这个线程无需再构建
                unLock(lock);
                return JSONUtil.toBean(JSONUtil.parseObj(redisData2.getData()), type);
            }
            //5.4开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lock);
                }
            });
        }
        return r;
    }


}
