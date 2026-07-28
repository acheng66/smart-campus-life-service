package com.smartcampus.utils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

@Component
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
}
