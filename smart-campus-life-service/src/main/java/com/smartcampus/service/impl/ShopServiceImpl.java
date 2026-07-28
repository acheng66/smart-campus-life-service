package com.smartcampus.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.Shop;
import com.smartcampus.mapper.ShopMapper;
import com.smartcampus.service.IShopService;
import com.smartcampus.utils.RedisConstants;
import com.smartcampus.utils.ShopBloomFilter;
import com.smartcampus.utils.SystemConstants;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

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
    private RedissonClient redissonClient;
    @Resource
    private ShopBloomFilter shopBloomFilter;

    /**
     * 根据id查询商铺信息
     * 
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        // 互斥锁 + 空值缓存 + 随机 TTL，处理击穿、穿透与雪崩。
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
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
        // 2.普通缓存未命中后由 Bloom Filter 拦截确定不存在的 ID。
        // 对误判为“可能存在”的请求，后续空值缓存仍会兜底。
        if (!shopBloomFilter.mightContain(id)) {
            return null;
        }

        // 3.判断命中的是否是空值。
        if (shopJason != null) {
            return null;
        }

        // 4.先获取分布式锁，不能在这里之前回源，否则冷缓存
        // 会让所有并发请求同时访问数据库。
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_SHOP_KEY + id);
        boolean locked = false;
        try {
            // 不传 leaseTime，Redisson 看门狗会在业务尚未完成时自动续期。
            locked = lock.tryLock(2L, TimeUnit.SECONDS);
            if (!locked) {
                // 等待持锁请求回填后再读一次；极端慢查询时宁可直接回源，
                // 也不能把“暂时拿不到锁”误报成“店铺不存在”。
                shopJason = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(shopJason)) {
                    return JSONUtil.toBean(shopJason, Shop.class);
                }
                if (shopJason != null) {
                    return null;
                }
                return getById(id);
            }

            // 4. 获取锁后双重检查，避免等待锁期间其他请求已经完成回填。
            shopJason = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJason)) {
                return JSONUtil.toBean(shopJason, Shop.class);
            }
            if (shopJason != null) {
                return null;
            }

            // 5. 回源并重建缓存
            Shop shop = getById(id);
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
            shopBloomFilter.add(shop.getId());
            return shop;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待商铺缓存锁时被中断", e);
        } finally {
            // 只有当前线程真正持有锁时才释放，避免误删其他请求的锁。
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
        Shop oldShop = getById(id);
        if (oldShop == null) {
            return Result.fail("店铺不存在");
        }

        // 先更新数据库。缓存清理必须发生在事务提交后，否则读请求可能把
        // 事务提交前的旧数据重新写回缓存。
        if (!updateById(shop)) {
            return Result.fail("店铺更新失败");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                evictShopCache(id);
                refreshShopGeoIndex(oldShop, id);
            }
        });

        return Result.ok();
    }

    private void evictShopCache(Long id) {
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
    }

    @Override
    public boolean save(Shop shop) {
        boolean saved = super.save(shop);
        if (saved) {
            // 即使外层事务之后回滚，留下的只是 Bloom Filter 假阳性，不会误拦截真实数据。
            shopBloomFilter.add(shop.getId());
        }
        return saved;
    }

    /**
     * GEO 索引不是带 TTL 的查询缓存，需要在店铺变更提交后显式维护。
     */
    private void refreshShopGeoIndex(Shop oldShop, Long shopId) {
        try {
            if (oldShop.getTypeId() != null) {
                stringRedisTemplate.opsForGeo().remove(
                        RedisConstants.SHOP_GEO_KEY + oldShop.getTypeId(), shopId.toString());
            }
            Shop latestShop = getById(shopId);
            if (latestShop != null && latestShop.getTypeId() != null
                    && latestShop.getX() != null && latestShop.getY() != null) {
                stringRedisTemplate.opsForGeo().add(
                        RedisConstants.SHOP_GEO_KEY + latestShop.getTypeId(),
                        new Point(latestShop.getX(), latestShop.getY()), shopId.toString());
            }
        } catch (Exception e) {
            // 缓存索引失败不能影响已提交的主数据；记录后由运维任务重建 GEO 索引。
            log.error("同步店铺 GEO 索引失败, shopId=" + shopId, e);
        }
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
