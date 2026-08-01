package com.smartcampus.service.agent;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * 服务端轻量意图守卫。
 *
 * <p>它不是替代大模型做完整语义规划，而是为最终卡片建立稳定的最小权限边界。
 * 复杂约束仍由 ChatClient 理解；这里仅识别四类与前端业务卡片直接相关的高置信意图。</p>
 */
@Component
public class AgentIntentResolver {
    private static final Pattern VOUCHER_ID = Pattern.compile("(优惠券|券)\\s*(ID|id|编号)?\\s*[:：#]?\\s*\\d+");

    public AgentIntent resolve(String message) {
        if (StrUtil.isBlank(message)) {
            return AgentIntent.GENERAL;
        }
        String text = message.trim();
        if (isEligibilityQuestion(text)) {
            return AgentIntent.ELIGIBILITY_CHECK;
        }
        if (containsAny(text, "我的券", "我的优惠券", "我领的券", "已领取的券", "已经领取的券", "我有哪些券",
                "我有哪些优惠券", "我有哪些已领取", "我有哪些已经领取", "我领了哪些", "快到期的券", "快过期的券")) {
            return AgentIntent.MY_VOUCHER_QUERY;
        }
        if (containsAny(text, "附近", "周边", "推荐", "适合", "评分高", "哪家店", "哪里吃", "晚餐店", "午餐店",
                "早餐店", "咖啡店")) {
            return AgentIntent.SHOP_RECOMMENDATION;
        }
        if (containsAny(text, "有什么券", "哪些券", "可领券", "可以领什么券", "店铺优惠券", "商家优惠券")
                || (containsAny(text, "券", "优惠券", "秒杀") && containsAny(text, "店", "窗口", "食堂", "商家"))) {
            return AgentIntent.SHOP_VOUCHER_QUERY;
        }
        return AgentIntent.GENERAL;
    }

    private boolean isEligibilityQuestion(String text) {
        boolean asksEligibility = containsAny(text, "是否已领取", "有没有领取", "已经领过", "还能不能领", "能不能领",
                "是不是领过", "领取资格", "库存", "活动开始", "活动结束", "这张券能不能用");
        return asksEligibility && (VOUCHER_ID.matcher(text).find() || containsAny(text, "这张券", "该券"));
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
