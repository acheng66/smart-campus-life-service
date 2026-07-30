package com.smartcampus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.smartcampus.entity.Shop;
import com.smartcampus.dto.AgentChatRequest;
import com.smartcampus.dto.AgentChatResponse;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.service.agent.ICampusAgentService;
import com.smartcampus.service.shop.IShopService;
import com.smartcampus.utils.auth.UserHolder;
import com.smartcampus.utils.cache.CachClient;
import com.smartcampus.utils.redis.RedisIdWorker;

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
    @Resource
    private ICampusAgentService campusAgentService;

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

    /**
     * 回归测试：找店时模型回答中点名的店铺，必须和前端最终展示的卡片一一对应。
     * 候选店、为了比较而查询的优惠券，都不能混入最终推荐结果。
     */
    @Test
    void agentChatShouldFallbackToBusinessQuery() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        try {
            AgentChatRequest request = new AgentChatRequest();
            request.setMessage("帮我找附近有优惠券的晚餐店");
            Result result = campusAgentService.chat(request);
            org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(result.getSuccess()),
                    () -> "Agent 查询失败：" + result.getErrorMsg());
            AgentChatResponse response = (AgentChatResponse) result.getData();
            org.junit.jupiter.api.Assertions.assertNotNull(response);
            org.junit.jupiter.api.Assertions.assertFalse(response.getAnswer().contains("|")
                    || response.getAnswer().contains("**") || response.getAnswer().contains("#"),
                    () -> "模型回答未清理 Markdown：" + response.getAnswer());
            org.junit.jupiter.api.Assertions.assertTrue(response.getAnswer().length() <= 180,
                    () -> "模型摘要过长：" + response.getAnswer());
            long recommendedShopCards = response.getCards().stream()
                    .filter(card -> "shop".equals(card.getType())).count();
            org.junit.jupiter.api.Assertions.assertTrue(recommendedShopCards >= 1 && recommendedShopCards <= 3,
                    "一次店铺推荐应展示一到三张最终推荐卡片");
            org.junit.jupiter.api.Assertions.assertEquals(recommendedShopCards, response.getCards().size(),
                    "找店场景只应返回最终推荐店铺卡片，不能混入候选店或候选券卡片");
            response.getCards().stream().filter(card -> "shop".equals(card.getType())).forEach(card ->
                    org.junit.jupiter.api.Assertions.assertTrue(response.getAnswer().contains(card.getTitle()),
                            () -> "回答与店铺卡片不一致：" + card.getTitle() + " / " + response.getAnswer()));
        } finally {
            UserHolder.removeUser();
        }
    }
}
