package com.smartcampus.utils.cache;

import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.smartcampus.entity.Shop;
import com.smartcampus.mapper.shop.ShopMapper;
import com.smartcampus.utils.redis.RedisConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Redisson RBloomFilter 的共享 Bloom Filter：用于拦截明显不存在的店铺 ID。
 *
 * <p>重建期间会移除 ready 标记，此时所有查询直接放行，避免重建窗口中出现
 * Bloom Filter 假阴性。</p>
 */
@Component
@Slf4j
public class ShopBloomFilter {
    /**
     * 预计纳入过滤器的商铺 ID 数量。不是硬上限，超过后仍可写入，
     * 但 Bloom Filter 的实际假阳性率会逐渐升高。
     */
    private static final long EXPECTED_INSERTIONS = 100_000L;

    /**
     * 目标假阳性率为 1%。不存在的 ID 可能被判定为“可能存在”，
     * 这类请求会继续走空值缓存和数据库兜底；不会误拦截真实存在的 ID。
     * 值越小，误判越少，但 Redis 位图占用的内存越多。
     */
    private static final double FALSE_POSITIVE_PROBABILITY = 0.01D;

    @Resource
    private ShopMapper shopMapper;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void initialize() {
        if (!isReady()) {
            rebuild();
        }
    }

    /**
     * 应用首次启动和定时任务都会调用该方法。返回 false 表示其他实例正在重建。
     */
    public boolean rebuild() {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_SHOP_BLOOM_REBUILD_KEY);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            // 重建期间一律放行，防止删除旧过滤器后把真实店铺误拦截。
            stringRedisTemplate.delete(RedisConstants.SHOP_BLOOM_READY_KEY);
            RBloomFilter<String> bloomFilter = bloomFilter();
            bloomFilter.delete();
            if (!bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_PROBABILITY)) {
                throw new IllegalStateException("初始化店铺 Bloom Filter 失败");
            }
            List<Shop> shops = shopMapper.selectList(null);
            for (Shop shop : shops) {
                if (shop.getId() != null) {
                    bloomFilter.add(shop.getId().toString());
                }
            }
            stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_BLOOM_READY_KEY, "1");
            log.info("店铺 Bloom Filter 重建完成，店铺数：{}", shops.size());
            return true;
        } catch (Exception e) {
            // 失败时不写 ready 标记，查询链路会降级为全部放行。
            log.error("店铺 Bloom Filter 重建失败，将降级为全部放行", e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 默认每天凌晨 03:00 重建。多实例部署时由 Redisson 锁保证只执行一次。
     */
    @Scheduled(cron = "${cache.shop-bloom.rebuild-cron:0 0 3 * * ?}")
    public void scheduledRebuild() {
        if (rebuild()) {
            log.info("店铺 Bloom Filter 定期重建完成");
        }
    }

    public boolean mightContain(Long shopId) {
        if (shopId == null || !isReady()) {
            return true;
        }
        try {
            return bloomFilter().contains(shopId.toString());
        } catch (Exception e) {
            // Redis 异常不应把请求误判为不存在，交给后续缓存和数据库处理。
            log.warn("查询店铺 Bloom Filter 失败，将降级为放行, shopId={}", shopId, e);
            return true;
        }
    }

    /**
     * 新增店铺后调用。Bloom Filter 不支持精确删除，删除店铺由空值缓存兜底，
     * 并由 Spring 定时任务定期全量重建。
     */
    public void add(Long shopId) {
        if (shopId == null) {
            return;
        }
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_SHOP_BLOOM_REBUILD_KEY);
        lock.lock();
        try {
            // 新增刚好与重建重叠时，在锁内重建可保证新 ID 不会漏写。
            if (!isReady()) {
                rebuildInsideLock();
            }
            bloomFilter().add(shopId.toString());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void rebuildInsideLock() {
        stringRedisTemplate.delete(RedisConstants.SHOP_BLOOM_READY_KEY);
        RBloomFilter<String> bloomFilter = bloomFilter();
        bloomFilter.delete();
        if (!bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_PROBABILITY)) {
            throw new IllegalStateException("初始化店铺 Bloom Filter 失败");
        }
        List<Shop> shops = shopMapper.selectList(null);
        for (Shop shop : shops) {
            if (shop.getId() != null) {
                bloomFilter.add(shop.getId().toString());
            }
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_BLOOM_READY_KEY, "1");
    }

    private boolean isReady() {
        return "1".equals(stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_BLOOM_READY_KEY));
    }

    private RBloomFilter<String> bloomFilter() {
        return redissonClient.getBloomFilter(RedisConstants.SHOP_BLOOM_FILTER_KEY);
    }
}
