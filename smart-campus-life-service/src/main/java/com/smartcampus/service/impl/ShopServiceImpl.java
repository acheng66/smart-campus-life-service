package com.smartcampus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.Shop;
import com.smartcampus.mapper.ShopMapper;
import com.smartcampus.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.utils.CachClient;
import com.smartcampus.utils.RedisConstants;
import com.smartcampus.utils.RedisData;
import com.smartcampus.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CachClient cachClient;

    /**
     * 根据id查询商铺信息
     * 
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        // 缓存穿透
        // Shop shop = cachClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,
        // id, Shop.class,
        // this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
        // Shop shop = queryWithLogicalExpire(id);
        // Shop shop = cachClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,
        // id, Shop.class,
        // this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 逻辑过期解决缓存击穿
     * 
     * @param id
     * @return
     */

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从缓存中查询
        String shopJason = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJason)) {
            // 2.缓存中有，返回
            return null;
        }
        // 3.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJason, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONUtil.parseObj(redisData.getData())), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 5.缓存过期，需要缓存重建
        // 5.1获取互斥锁
        String lock = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lock);
        // 5.2判断是否获取成功
        if (flag) {
            // 5.3再次检测redis缓存是否过期
            shopJason = stringRedisTemplate.opsForValue().get(key);
            RedisData redisData2 = JSONUtil.toBean(shopJason, RedisData.class);
            if (redisData2.getExpireTime().isAfter(LocalDateTime.now())) {
                // 别的线程已经更新了，这个线程无需再构建
                unLock(lock);
                return JSONUtil.toBean(JSONUtil.parseObj(redisData2.getData()), Shop.class);
            }
            // 5.4开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lock);
                }
            });
        }
        return shop;
    }

    /**
     * 根据id查询商铺信息-互斥锁解决缓存击穿
     * 
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从缓存中查询
        String shopJason = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJason)) {
            // 2.缓存中有，返回
            Shop shop = JSONUtil.toBean(shopJason, Shop.class);
            return shop;
        }
        // 2.判断命中的是否是空值
        if (shopJason != null) {
            return null;
        }
        // 3.缓存中没有，查询数据库
        Shop shop = getById(id);
        // 4.缓存重建
        // 4.1获取互斥锁
        String lock = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            boolean flag = tryLock(lock);
            // 4.2 判断是否获取成功
            if (!flag) {
                // 4.3失败，休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4成功，查询数据库
            shop = getById(id);
            // 5.数据库中查不到，返回错误,将空值写入缓存
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.数据库中查到，写入缓存
            // 添加随机TTL（0-5分钟）防止缓存雪崩
            long randomTtl = RedisConstants.CACHE_SHOP_TTL + ThreadLocalRandom.current().nextInt(6);
            String shopJson = JSONUtil.toJsonStr(shop);
            if (shopJson != null) {
                stringRedisTemplate.opsForValue()
                        .set(key, shopJson,
                                randomTtl, TimeUnit.MINUTES);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放锁
            unLock(lock);
        }
        // 8.返回
        return shop;
    }

    /**
     * 尝试获取锁
     * 
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * 
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据id查询商铺信息-缓存穿透
     * 
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从缓存中查询
        String shopJason = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJason)) {
            // 2.缓存中有，返回
            Shop shop = JSONUtil.toBean(shopJason, Shop.class);
            return shop;
        }
        // 2.判断命中的是否是空值
        if (shopJason != null) {
            return null;
        }
        // 3.缓存中没有，查询数据库
        Shop shop = getById(id);
        // 4.数据库中查不到，返回错误,将空值写入缓存
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库中查到,写入缓存
        // 添加随机TTL(0-5分钟)防止缓存雪崩
        long randomTtl = RedisConstants.CACHE_SHOP_TTL + ThreadLocalRandom.current().nextInt(6);
        String shopJson = JSONUtil.toJsonStr(shop);
        if (shopJson != null) {
            stringRedisTemplate.opsForValue()
                    .set(key, shopJson,
                            randomTtl, TimeUnit.MINUTES);
        }
        // 6.返回
        return shop;
    }

    /**
     * 将店铺信息保存到redis
     * 
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(redisData));

    }

    /**
     * 更新商铺信息 - 延迟双删策略
     * 
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1.第一次删除缓存
        stringRedisTemplate.delete(key);

        // 2.更新数据库
        updateById(shop);

        // 3.延迟双删 - 异步延迟500ms后再次删除缓存
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                // 延迟500ms，确保其他线程的查询操作完成
                Thread.sleep(500);
                // 第二次删除缓存，清除可能的脏数据
                stringRedisTemplate.delete(key);
            } catch (InterruptedException e) {
                log.error("延迟双删失败,key: " + key, e);
            }
        });

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            // 根据类型分页查询
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3.查询redis，按照距离排序，分页。结果：shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo().radius(key,
                new Circle(new Point(x, y),
                        new Distance(5000)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(end));
        if (geoResults == null) {
            return Result.ok(Collections.emptyList());
        }
        // 4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = geoResults.getContent();
        if (list.size() <= from) {
            // 没有下一页
            return Result.ok(Collections.emptyList());
        }
        // 4.1 分页，截取从from到end的店铺
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2 获取店铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            // 4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        // 5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 6.将距离赋值给shop
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
