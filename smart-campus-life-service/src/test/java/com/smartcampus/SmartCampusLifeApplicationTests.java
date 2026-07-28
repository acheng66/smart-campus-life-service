package com.smartcampus;

import com.smartcampus.entity.Shop;
import com.smartcampus.service.IShopService;
import com.smartcampus.utils.CachClient;
import com.smartcampus.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class SmartCampusLifeApplicationTests {
    @Resource
    private CachClient cachClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;

    private static final ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        //多线程下测试
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void loadShopData(){
        //1. 查询所有店铺信息
        List<Shop> list = shopService.list();
        //2. 将店铺分组，按照typeId分组，typeId相同的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3. 写入redis GEOADD key 经度 纬度 member
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            //写入redis
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = value.stream().map(shop -> new RedisGeoCommands.GeoLocation<>(
                    shop.getId().toString(),
                    new Point(shop.getX(), shop.getY())
            )).collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(key, geoLocations);
//            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().
//                        add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
//            }
        }
    }
}
