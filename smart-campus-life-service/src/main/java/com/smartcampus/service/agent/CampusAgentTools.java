package com.smartcampus.service.agent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.Resource;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.smartcampus.dto.AgentCard;
import com.smartcampus.dto.MyVoucherDTO;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.entity.Shop;
import com.smartcampus.entity.Voucher;
import com.smartcampus.entity.VoucherOrder;
import com.smartcampus.service.shop.IShopService;
import com.smartcampus.service.voucher.ISeckillVoucherService;
import com.smartcampus.service.voucher.IVoucherOrderService;
import com.smartcampus.service.voucher.IVoucherService;
import com.smartcampus.utils.auth.UserHolder;
import com.smartcampus.utils.redis.RedisConstants;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

/**
 * ChatClient 可调用的受控工具。
 *
 * <p>每个工具只读真实业务状态；同时把可信卡片写入 {@link AgentToolCallContext}，
 * 因此前端不会使用模型编造的 ID 或写操作参数。工具的 String 返回值供模型理解，
 * 卡片则由服务端直接交给前端，两者职责分离。</p>
 */
@Component
public class CampusAgentTools {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private IShopService shopService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private AgentActionTokenService actionTokenService;

    /**
     * 按关键词查询商户候选。
     *
     * <p>数据来自 MySQL 商户表和上架券表；定位仅影响排序，不会写入用户位置。
     * 本工具只将候选事实返回给模型，不直接生成前端卡片；模型完成比较后必须调用
     * {@link #selectShopRecommendations(List)} 选择最终推荐，避免候选集被误当成推荐结果。</p>
     */
    @Tool(description = "按名称、区域或地址关键词搜索校园店铺候选。用户询问附近餐饮、咖啡、推荐店铺时优先调用；系统会结合当前会话位置、评分和上架优惠券排序。该工具不会展示卡片，比较候选后必须再调用 selectShopRecommendations 选择最终要推荐的一到三家店铺。")
    public String searchShops(@ToolParam(description = "用户需求中的店铺名、区域、餐饮或咖啡等关键词；没有明确关键词时传空字符串", required = false) String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Shop> shops = shopService.list().stream()
                .filter(shop -> normalized.isEmpty() || containsShop(shop, normalized))
                .collect(Collectors.toList());
        if (shops.isEmpty() && !normalized.isEmpty()) {
            // 用户自然语言未直接命中字段时，返回候选而不是让模型虚构“没有店”。
            shops = shopService.list();
        }
        // 只统计上架券，排序规则为：有券优先 → 评分优先 → 距离优先。
        Map<Long, Long> voucherCounts = voucherService.query().eq("status", 1).list().stream()
                .collect(Collectors.groupingBy(Voucher::getShopId, Collectors.counting()));
        AgentToolCallContext.Context context = AgentToolCallContext.current();
        final Double x = context == null ? null : context.getX();
        final Double y = context == null ? null : context.getY();
        shops.sort(Comparator.comparing((Shop shop) -> voucherCounts.getOrDefault(shop.getId(), 0L)).reversed()
                .thenComparing((Shop shop) -> score(shop), Comparator.reverseOrder())
                .thenComparing(shop -> distance(x, y, shop)));
        List<Shop> result = shops.stream().limit(5).collect(Collectors.toList());
        if (context != null) {
            context.setCandidateShopIds(result.stream().map(Shop::getId).collect(Collectors.toList()));
        }
        return JSONUtil.toJsonStr(result.stream().map(this::shopView).collect(Collectors.toList()));
    }

    /**
     * 将模型从搜索候选中选出的最终店铺写成可信前端卡片。
     *
     * <p>模型只能提供 shopId，展示字段仍由服务端重新查库并构建，不能把模型生成的店名、评分或价格直接交给前端。
     * 每轮对话仅应调用一次，店铺数量与模型最终回答中点名的店铺数量一致，因而用户看到的是
     * “最终推荐”而不是全部候选。</p>
     */
    @Tool(description = "展示最终推荐的店铺卡片。必须传入 searchShops 返回候选中的 shopIds，按回答里提及店铺的顺序传入；一次最多 3 家且只调用一次。不要传入回答里未提及的店铺。")
    public String selectShopRecommendations(@ToolParam(description = "最终回答中会明确提及的店铺 ID 列表，必须来自本轮 searchShops 返回结果，最多 3 个", required = true) List<Long> shopIds) {
        AgentToolCallContext.Context context = AgentToolCallContext.current();
        if (shopIds == null || shopIds.isEmpty()) {
            return "缺少店铺 ID，无法展示推荐。";
        }
        List<Long> selectedIds = shopIds.stream().filter(java.util.Objects::nonNull).distinct().limit(3)
                .collect(Collectors.toList());
        if (selectedIds.isEmpty() || context == null || selectedIds.stream().anyMatch(id -> !context.isCandidateShop(id))) {
            return "只能展示本轮搜索返回的店铺候选。";
        }
        if (!context.selectRecommendations(selectedIds)) {
            return "本轮已经完成最终店铺选择，不能重复展示推荐卡片。";
        }
        List<Shop> shops = shopService.listByIds(selectedIds);
        Map<Long, Shop> shopMap = shops.stream().collect(Collectors.toMap(Shop::getId, shop -> shop));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long shopId : selectedIds) {
            Shop shop = shopMap.get(shopId);
            if (shop == null) {
                continue;
            }
            long voucherCount = voucherService.query().eq("status", 1).eq("shop_id", shopId).count();
            addShopCard(shop, voucherCount, context.getX(), context.getY());
            Map<String, Object> view = new java.util.LinkedHashMap<>(shopView(shop));
            view.put("voucherCount", voucherCount);
            result.add(view);
        }
        return JSONUtil.toJsonStr(result);
    }

    /**
     * 查询当前登录用户已领取的券。
     *
     * <p>用户 ID 强制从 UserHolder 读取，模型没有 userId 参数，因此不能借工具越权查询其他人的券。</p>
     */
    @Tool(description = "查询当前已登录用户已领取的优惠券和有效期。只能查询当前会话用户，不能查询其他用户。")
    public String queryMyVouchers() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return "当前用户未登录，不能查询优惠券。";
        }
        Result result = voucherOrderService.queryMyVouchers();
        Object data = result.getData();
        if (data instanceof List) {
            for (Object item : (List<?>) data) {
                if (item instanceof MyVoucherDTO) {
                    addMyVoucherCard((MyVoucherDTO) item);
                }
            }
        }
        return JSONUtil.toJsonStr(data == null ? Collections.emptyList() : data);
    }

    /**
     * 查询指定商户的上架优惠券。
     *
     * <p>普通券读取数据库状态；秒杀券额外读取 Redis 预扣减后的库存。该方法不写库，
     * 仅在可领取时为卡片签发一次性确认 Token。</p>
     */
    @Tool(description = "查询指定店铺当前上架优惠券、金额、使用规则、秒杀时间和实时库存。只能查询，不会领取优惠券。若用户想领券，页面必须由用户点击确认领取。")
    public String queryShopVouchers(@ToolParam(description = "店铺 ID，应优先来自 searchShops 的返回结果", required = true) Long shopId) {
        if (shopId == null) {
            return "缺少店铺 ID，无法查询店铺优惠券。";
        }
        List<Voucher> vouchers = voucherService.query().eq("shop_id", shopId).eq("status", 1).list();
        for (Voucher voucher : vouchers) {
            addVoucherCard(voucher);
        }
        return JSONUtil.toJsonStr(vouchers.stream().map(this::voucherView).collect(Collectors.toList()));
    }

    /**
     * 校验当前用户对某张券的可见资格信息。
     *
     * <p>该结果只用于回答“是否已领、活动是否开始、当前库存”等问题；最终领取时仍必须调用业务服务二次校验。</p>
     */
    @Tool(description = "校验当前登录用户是否已领取某张券，并返回券状态、秒杀活动时间和 Redis 实时库存。只读，不会执行领取。")
    public String checkVoucherEligibility(@ToolParam(description = "优惠券 ID，应来自 queryShopVouchers 返回结果", required = true) Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return "当前用户未登录，不能校验资格。";
        }
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return "优惠券不存在。";
        }
        boolean received = voucherOrderService.query().eq("user_id", user.getId()).eq("voucher_id", voucherId).count() > 0;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("voucherId", voucherId);
        result.put("title", voucher.getTitle());
        result.put("status", voucher.getStatus());
        result.put("alreadyReceived", received);
        result.put("type", voucher.getType());
        result.put("rules", voucher.getRules());
        if (Integer.valueOf(1).equals(voucher.getType())) {
            SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
            result.put("beginTime", seckill == null ? null : seckill.getBeginTime());
            result.put("endTime", seckill == null ? null : seckill.getEndTime());
            result.put("redisStock", readRedisStock(voucherId));
        }
        return JSONUtil.toJsonStr(result);
    }

    /** 将真实商户实体转换为前端安全展示卡片，并保存到本轮 ThreadLocal 上下文。 */
    private void addShopCard(Shop shop, Long voucherCount, Double x, Double y) {
        AgentToolCallContext.Context context = AgentToolCallContext.current();
        if (context == null) {
            return;
        }
        AgentCard card = new AgentCard();
        card.setType("shop");
        card.setShopId(shop.getId());
        card.setTitle(shop.getName());
        String description = "评分 " + String.format("%.1f", score(shop) / 10D) + " · 人均 ¥" + (shop.getAvgPrice() == null ? "--" : shop.getAvgPrice());
        if (voucherCount > 0) {
            description += " · " + voucherCount + " 张上架券";
        }
        if (x != null && y != null && shop.getX() != null && shop.getY() != null) {
            description += " · 约 " + Math.round(distance(x, y, shop)) + " 米";
        }
        card.setDescription(description);
        context.addCard(card);
    }

    /** 将已有券 DTO 转换为只读卡片；“可使用”仅表示券状态和有效期，不代表订单结算已通过。 */
    private void addMyVoucherCard(MyVoucherDTO voucher) {
        AgentToolCallContext.Context context = AgentToolCallContext.current();
        if (context == null) {
            return;
        }
        AgentCard card = new AgentCard();
        card.setType("my-voucher");
        card.setVoucherId(voucher.getVoucherId());
        card.setShopId(voucher.getShopId());
        card.setTitle(defaultText(voucher.getTitle(), "优惠券"));
        boolean available = voucher.getEndTime() == null || !voucher.getEndTime().isBefore(LocalDateTime.now());
        card.setDescription(defaultText(voucher.getShopName(), "校园商家") + " · "
                + voucherRule(voucher.getPayValue(), voucher.getActualValue()) + " · "
                + (available ? "可使用" : "已失效"));
        context.addCard(card);
    }

    /**
     * 生成商户券卡片并决定是否提供确认按钮。
     *
     * <p>普通券只要未领取即可签发 RECEIVE_NORMAL；秒杀券还要同时满足活动时间和 Redis 实时库存。
     * 签发 Token 不会扣库存，真正操作发生在用户确认后的原业务接口。</p>
     */
    private void addVoucherCard(Voucher voucher) {
        AgentToolCallContext.Context context = AgentToolCallContext.current();
        if (context == null || context.getUserId() == null) {
            return;
        }
        boolean received = voucherOrderService.query().eq("user_id", context.getUserId())
                .eq("voucher_id", voucher.getId()).count() > 0;
        AgentCard card = new AgentCard();
        card.setType("voucher");
        card.setVoucherId(voucher.getId());
        card.setShopId(voucher.getShopId());
        card.setTitle(defaultText(voucher.getTitle(), "优惠券"));
        String description = voucherRule(voucher.getPayValue(), voucher.getActualValue());
        if (StrUtil.isNotBlank(voucher.getRules())) {
            description += " · " + StrUtil.subWithLength(voucher.getRules(), 0, 28);
        }
        String actionType = null;
        if (received) {
            description += " · 你已领取";
        } else if (Integer.valueOf(0).equals(voucher.getType())) {
            actionType = "RECEIVE_NORMAL";
            description += " · 可领取";
        } else {
            SeckillVoucher seckill = seckillVoucherService.getById(voucher.getId());
            Integer stock = readRedisStock(voucher.getId());
            LocalDateTime now = LocalDateTime.now();
            if (seckill != null && stock != null && stock > 0 && seckill.getBeginTime() != null && seckill.getEndTime() != null
                    && !now.isBefore(seckill.getBeginTime()) && !now.isAfter(seckill.getEndTime())) {
                actionType = "SECKILL";
                description += " · 秒杀进行中，库存 " + stock;
            } else if (seckill != null && seckill.getBeginTime() != null && now.isBefore(seckill.getBeginTime())) {
                description += " · " + seckill.getBeginTime().format(TIME_FORMAT) + " 开始";
            } else {
                description += " · 当前不可领取";
            }
        }
        card.setDescription(description);
        if (actionType != null) {
            card.setActionLabel("确认领取");
            card.setActionToken(actionTokenService.issue(context.getUserId(), voucher.getId(), actionType));
        }
        context.addCard(card);
    }

    /** 构造提供给模型阅读的券字段，避免把完整实体和无关字段暴露到 Prompt。 */
    private Map<String, Object> voucherView(Voucher voucher) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("voucherId", voucher.getId());
        item.put("title", voucher.getTitle());
        item.put("subTitle", voucher.getSubTitle());
        item.put("rules", voucher.getRules());
        item.put("payValue", voucher.getPayValue());
        item.put("actualValue", voucher.getActualValue());
        item.put("type", voucher.getType());
        if (Integer.valueOf(1).equals(voucher.getType())) {
            SeckillVoucher seckill = seckillVoucherService.getById(voucher.getId());
            item.put("beginTime", seckill == null ? null : seckill.getBeginTime());
            item.put("endTime", seckill == null ? null : seckill.getEndTime());
            item.put("redisStock", readRedisStock(voucher.getId()));
        }
        return item;
    }

    /** 构造提供给模型阅读的商户字段；真实跳转 ID 同时已写入可信卡片。 */
    private Map<String, Object> shopView(Shop shop) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("shopId", shop.getId());
        item.put("name", shop.getName());
        item.put("score", score(shop) / 10D);
        item.put("avgPrice", shop.getAvgPrice());
        item.put("address", shop.getAddress());
        item.put("openHours", shop.getOpenHours());
        return item;
    }

    /**
     * 读取秒杀 Redis 预扣减库存；不存在或格式异常都按不可确认处理，而不回退到数据库库存。
     */
    private Integer readRedisStock(Long voucherId) {
        String stock = stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        try {
            return StrUtil.isBlank(stock) ? null : Integer.valueOf(stock);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 关键词只匹配商户名、区域、地址，语义理解交由上层模型或 RAG 处理。 */
    private boolean containsShop(Shop shop, String keyword) {
        return (shop.getName() != null && shop.getName().toLowerCase().contains(keyword))
                || (shop.getArea() != null && shop.getArea().toLowerCase().contains(keyword))
                || (shop.getAddress() != null && shop.getAddress().toLowerCase().contains(keyword));
    }

    /**
     * 校园小范围内的经纬度近似米制距离，仅用于排序和展示，不替代地图模块的 Redis GEO 查询。
     */
    private double distance(Double x, Double y, Shop shop) {
        if (x == null || y == null || shop.getX() == null || shop.getY() == null) {
            return Double.MAX_VALUE;
        }
        double dx = (x - shop.getX()) * 85000D;
        double dy = (y - shop.getY()) * 111000D;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 商户评分在数据库中按十分制整数存储，空值按 0 参与排序。 */
    private int score(Shop shop) {
        return shop.getScore() == null ? 0 : shop.getScore();
    }

    /** 数据库存储分，展示给用户时换算为元。 */
    private String voucherRule(Long payValue, Long actualValue) {
        return payValue == null || actualValue == null ? "以使用规则为准"
                : "满 " + payValue / 100 + " 元减 " + actualValue / 100 + " 元";
    }

    private String defaultText(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value;
    }
}
