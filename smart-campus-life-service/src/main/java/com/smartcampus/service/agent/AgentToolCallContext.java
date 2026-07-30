package com.smartcampus.service.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.smartcampus.dto.AgentCard;

/**
 * 一次同步 ChatClient 调用的服务端上下文。
 *
 * <p>模型只能看到 Tool 返回的文本；可点击卡片由工具把可信 ID 写入本上下文，再由
 * Controller 响应给前端。这样模型无法伪造店铺 ID、券 ID 或确认凭证。</p>
 *
 * <p>ChatClient 的一次工具调用在当前线程同步完成，因此可安全使用 ThreadLocal；无论成功、降级还是异常，
 * {@code finally} 块都会调用 {@link #clear()} 防止线程复用时泄漏上一次用户数据。</p>
 */
public final class AgentToolCallContext {
    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private AgentToolCallContext() {
    }

    /** 在调用模型前创建本轮上下文，并绑定当前用户和可选定位坐标。 */
    public static void begin(Long userId, Double x, Double y) {
        HOLDER.set(new Context(userId, x, y));
    }

    /** 供 @Tool 方法读取当前请求用户和定位信息；脱离 Agent 调用时可能为 null。 */
    public static Context current() {
        return HOLDER.get();
    }

    /** 返回本轮工具生成的卡片副本，避免调用方修改 ThreadLocal 内部集合。 */
    public static List<AgentCard> cards() {
        Context context = HOLDER.get();
        return context == null ? new ArrayList<>() : context.responseCards();
    }

    /** 请求结束必须清理，避免 Web 线程复用时发生跨用户数据泄漏。 */
    public static void clear() {
        HOLDER.remove();
    }

    public static final class Context {
        /** 当前登录用户，绝不从模型工具参数读取。 */
        private final Long userId;
        /** 当前用户授权后传入的经度，仅用于推荐距离排序。 */
        private final Double x;
        /** 当前用户授权后传入的纬度，仅用于推荐距离排序。 */
        private final Double y;
        /** 以 type + 业务 ID 去重，避免模型多次调用工具产生重复卡片。 */
        private final Map<String, AgentCard> cards = new LinkedHashMap<>();
        /** 本轮 searchShops 返回过的店铺候选，供最终展示工具校验，防止模型凭空拼装店铺卡片。 */
        private final Set<Long> candidateShopIds = new LinkedHashSet<>();
        /**
         * 模型最终明确推荐的店铺。存在该集合时，响应只返回这些店铺卡片，不能把查询阶段的候选券卡片混进来。
         */
        private final Set<Long> recommendedShopIds = new LinkedHashSet<>();

        private Context(Long userId, Double x, Double y) {
            this.userId = userId;
            this.x = x;
            this.y = y;
        }

        public Long getUserId() {
            return userId;
        }

        public Double getX() {
            return x;
        }

        public Double getY() {
            return y;
        }

        /** 记录本轮真实搜索返回的候选店铺 ID；每轮 Agent 调用开始时都会创建新上下文。 */
        public void setCandidateShopIds(Collection<Long> shopIds) {
            candidateShopIds.clear();
            if (shopIds != null) {
                candidateShopIds.addAll(shopIds);
            }
        }

        /** 判断模型选择的店铺是否来自本轮真实搜索结果。 */
        public boolean isCandidateShop(Long shopId) {
            return shopId != null && candidateShopIds.contains(shopId);
        }

        /**
         * 固化本轮最终推荐结果。只允许成功设置一次，防止模型重复调用展示工具导致回答与卡片不一致。
         *
         * @return 是否首次完成选择
         */
        public boolean selectRecommendations(Collection<Long> shopIds) {
            if (!recommendedShopIds.isEmpty() || shopIds == null || shopIds.isEmpty()) {
                return false;
            }
            recommendedShopIds.addAll(shopIds);
            return true;
        }

        /**
         * 保存可信卡片。
         * 同一类型、同一业务 ID 的后写结果会覆盖前写结果，保证前端 v-for key 唯一。
         */
        public void addCard(AgentCard card) {
            if (card == null) {
                return;
            }
            String id = card.getVoucherId() == null ? String.valueOf(card.getShopId()) : String.valueOf(card.getVoucherId());
            cards.put(card.getType() + ":" + id, card);
        }

        /**
         * 构建本轮最终响应卡片。
         *
         * <p>普通查券、查已领券等场景没有“最终推荐”概念，保留对应工具生成的卡片；找店场景一旦模型
         * 调用 selectShopRecommendations，则只返回该工具确认过的店铺卡片。这样回答里推荐两家，页面也恰好
         * 展示两家，不会夹带模型为了比较而查询过的其他候选店或其优惠券。</p>
         */
        private List<AgentCard> responseCards() {
            if (recommendedShopIds.isEmpty()) {
                return new ArrayList<>(cards.values());
            }
            List<AgentCard> result = new ArrayList<>();
            for (Long shopId : recommendedShopIds) {
                AgentCard card = cards.get("shop:" + shopId);
                if (card != null) {
                    result.add(card);
                }
            }
            return result;
        }
    }
}
