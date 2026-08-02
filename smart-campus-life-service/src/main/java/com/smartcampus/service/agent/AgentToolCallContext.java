package com.smartcampus.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.smartcampus.dto.AgentCard;
import com.smartcampus.dto.MyVoucherDTO;
import com.smartcampus.entity.Voucher;
import com.smartcampus.service.agent.stream.AgentStreamEventContext;

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

    /** 在调用模型前创建本轮上下文，并绑定当前用户、坐标和服务端判定的主要意图。 */
    public static void begin(Long userId, Double x, Double y, AgentIntent intent) {
        HOLDER.set(new Context(userId, x, y, intent));
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

    /** 记录一个真实进入执行阶段的 @Tool 方法，供自动评测校验模型工具规划。 */
    public static void recordTool(String toolName) {
        Context context = HOLDER.get();
        if (context != null && toolName != null) {
            context.toolCalls.add(toolName);
            AgentStreamEventContext.publishTool(toolName);
        }
    }

    /** 返回本轮工具调用顺序的副本，必须在 {@link #clear()} 之前读取。 */
    public static List<String> toolCalls() {
        Context context = HOLDER.get();
        return context == null ? new ArrayList<>() : new ArrayList<>(context.toolCalls);
    }

    /** 返回真实搜索候选店名，供评测判断回答是否提及了未展示卡片的候选店。 */
    public static List<String> candidateShopTitles() {
        Context context = HOLDER.get();
        return context == null ? new ArrayList<>() : new ArrayList<>(context.candidateShopTitles.values());
    }

    /** 返回本轮服务端意图，供执行轨迹和评测使用。 */
    public static AgentIntent intent() {
        Context context = HOLDER.get();
        return context == null ? AgentIntent.GENERAL : context.intent;
    }

    /** 返回已完成的最终展示类型；查询工具本身不会改变该值。 */
    public static AgentPresentationType presentationType() {
        Context context = HOLDER.get();
        return context == null ? AgentPresentationType.NONE : context.presentationType;
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
        /** 候选 ID 对应的真实店名；LinkedHashMap 保持搜索排序。 */
        private final Map<Long, String> candidateShopTitles = new LinkedHashMap<>();
        /** queryShopVouchers 查到的真实券；最终展示工具只能从这里选择。 */
        private final Map<Long, Voucher> queriedVouchers = new LinkedHashMap<>();
        /** queryMyVouchers 查到的真实本人券；最终展示工具只能从这里选择。 */
        private final Map<Long, MyVoucherDTO> queriedMyVouchers = new LinkedHashMap<>();
        /** 实际进入 Java 方法的工具调用顺序，不依赖模型声称自己调用过什么。 */
        private final List<String> toolCalls = new ArrayList<>();
        /** 服务端判定的主要意图，决定本轮唯一允许的最终卡片类型。 */
        private final AgentIntent intent;
        /** 查询阶段保持 NONE，只有最终展示工具可以设置一次。 */
        private AgentPresentationType presentationType = AgentPresentationType.NONE;

        private Context(Long userId, Double x, Double y, AgentIntent intent) {
            this.userId = userId;
            this.x = x;
            this.y = y;
            this.intent = intent == null ? AgentIntent.GENERAL : intent;
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

        public AgentIntent getIntent() {
            return intent;
        }

        public AgentPresentationType getPresentationType() {
            return presentationType;
        }

        /** 保存候选店铺 ID 和真实名称，供最终选择校验与自动评测共同使用。 */
        public void setCandidateShops(Map<Long, String> shops) {
            candidateShopIds.clear();
            candidateShopTitles.clear();
            if (shops != null) {
                candidateShopIds.addAll(shops.keySet());
                candidateShopTitles.putAll(shops);
            }
        }

        /** 判断模型选择的店铺是否来自本轮真实搜索结果。 */
        public boolean isCandidateShop(Long shopId) {
            return shopId != null && candidateShopIds.contains(shopId);
        }

        /** 保存查询阶段获取的真实店铺券，查询本身不生成前端卡片。 */
        public void registerQueriedVouchers(List<Voucher> vouchers) {
            if (vouchers != null) {
                for (Voucher voucher : vouchers) {
                    if (voucher != null && voucher.getId() != null) {
                        queriedVouchers.put(voucher.getId(), voucher);
                    }
                }
            }
        }

        public Voucher getQueriedVoucher(Long voucherId) {
            return queriedVouchers.get(voucherId);
        }

        /** 保存查询阶段获取的当前用户券，不能由模型传入其他用户数据。 */
        public void registerQueriedMyVouchers(List<MyVoucherDTO> vouchers) {
            if (vouchers != null) {
                for (MyVoucherDTO voucher : vouchers) {
                    if (voucher != null && voucher.getVoucherId() != null) {
                        queriedMyVouchers.put(voucher.getVoucherId(), voucher);
                    }
                }
            }
        }

        public MyVoucherDTO getQueriedMyVoucher(Long voucherId) {
            return queriedMyVouchers.get(voucherId);
        }

        /**
         * 最终展示工具申请唯一展示类型。
         *
         * <p>类型必须与服务端意图一致，并且本轮尚未被其他展示工具确定；模型多余或冲突的展示调用会被拒绝。</p>
         */
        public boolean beginPresentation(AgentPresentationType requestedType) {
            if (requestedType == null || requestedType == AgentPresentationType.NONE
                    || presentationType != AgentPresentationType.NONE || !allows(requestedType)) {
                return false;
            }
            presentationType = requestedType;
            return true;
        }

        private boolean allows(AgentPresentationType requestedType) {
            switch (intent) {
                case SHOP_RECOMMENDATION:
                    return requestedType == AgentPresentationType.SHOP;
                case SHOP_VOUCHER_QUERY:
                    return requestedType == AgentPresentationType.VOUCHER;
                case MY_VOUCHER_QUERY:
                    return requestedType == AgentPresentationType.MY_VOUCHER;
                default:
                    return false;
            }
        }

        /**
         * 保存可信卡片。
         * 同一类型、同一业务 ID 的后写结果会覆盖前写结果；与最终展示类型不一致的卡片会被服务端拒绝。
         */
        public boolean addCard(AgentCard card) {
            if (card == null || !matchesPresentation(card)) {
                return false;
            }
            String id = card.getVoucherId() == null ? String.valueOf(card.getShopId()) : String.valueOf(card.getVoucherId());
            cards.put(card.getType() + ":" + id, card);
            return true;
        }

        private boolean matchesPresentation(AgentCard card) {
            return (presentationType == AgentPresentationType.SHOP && "shop".equals(card.getType()))
                    || (presentationType == AgentPresentationType.VOUCHER && "voucher".equals(card.getType()))
                    || (presentationType == AgentPresentationType.MY_VOUCHER && "my-voucher".equals(card.getType()));
        }

        /**
         * 构建本轮最终响应卡片。
         *
         * <p>查询工具只登记事实，不会进入 cards；因此这里只可能返回与服务端意图和唯一展示类型一致的卡片。</p>
         */
        private List<AgentCard> responseCards() {
            return new ArrayList<>(cards.values());
        }
    }
}
