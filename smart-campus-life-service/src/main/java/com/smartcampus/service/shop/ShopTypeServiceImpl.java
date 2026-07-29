package com.smartcampus.service.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.ShopType;
import com.smartcampus.mapper.shop.ShopTypeMapper;
import com.smartcampus.service.shop.IShopTypeService;
import com.smartcampus.utils.redis.RedisConstants;

import cn.hutool.json.JSONUtil;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    /**
     * 查询商铺类型列表
     *
     * @return
     */
    @Override
    public Result queryShopTypeList() {
        List<ShopType> cached = readCache();
        if (cached != null) {
            return Result.ok(cached);
        }

        RLock lock = redissonClient.getLock(RedisConstants.LOCK_SHOP_TYPE_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(2L, TimeUnit.SECONDS);
            if (!locked) {
                // 锁持有者通常已在回填；若仍未出现缓存，直接回源保证正确性。
                cached = readCache();
                return Result.ok(cached != null ? cached : query().orderByAsc("sort").list());
            }

            // 双重检查，防止等待锁的期间出现重复写入。
            cached = readCache();
            if (cached != null) {
                return Result.ok(cached);
            }
            List<ShopType> shopTypeList = query().orderByAsc("sort").list();
            if (shopTypeList == null || shopTypeList.isEmpty()) {
                return Result.fail("店铺类型不存在");
            }
            List<String> jsonList = new ArrayList<>(shopTypeList.size());
            for (ShopType shopType : shopTypeList) {
                jsonList.add(JSONUtil.toJsonStr(shopType));
            }
            stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, jsonList);
            stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_KEY,
                    RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
            return Result.ok(shopTypeList);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待商铺分类缓存锁时被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private List<ShopType> readCache() {
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList()
                .range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        if (shopTypeJsonList == null || shopTypeJsonList.isEmpty()) {
            return null;
        }
        List<ShopType> shopTypeList = new ArrayList<>(shopTypeJsonList.size());
        for (String str : shopTypeJsonList) {
            shopTypeList.add(JSONUtil.toBean(str, ShopType.class));
        }
        return shopTypeList;
    }

}
